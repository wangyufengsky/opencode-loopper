package io.opencode.loopper.service;

import io.opencode.loopper.domain.ExecutionCycleState;
import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.domain.SessionState;
import io.opencode.loopper.domain.TaskState;
import io.opencode.loopper.persistence.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Local human acceptance after deterministic execution; never rewrites a Judge verdict. */
@Service
public class TaskJudgeApprovalService {
    private final LoopperMapper mapper;
    private final TaskJudgeApprovalMapper approvals;
    private final TaskStateStore states;
    private final TaskExecutionCycleService cycles;
    private final TaskWorkspaceCheckpointService checkpoints;
    private final WorkspaceLeaseReconciliationService leases;
    private final TaskEventService events;
    private final TransactionTemplate transactions;

    public TaskJudgeApprovalService(LoopperMapper mapper, TaskJudgeApprovalMapper approvals, TaskStateStore states,
                                   TaskExecutionCycleService cycles, TaskWorkspaceCheckpointService checkpoints,
                                   WorkspaceLeaseReconciliationService leases, TaskEventService events,
                                   PlatformTransactionManager transactionManager) {
        this.mapper = mapper; this.approvals = approvals; this.states = states; this.cycles = cycles;
        this.checkpoints = checkpoints; this.leases = leases; this.events = events;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public record Request(Long expectedTaskVersion, String cycleId, Long expectedCycleVersion, String reviewBatchId) { }
    public record View(boolean available, boolean approved, long taskVersion, String cycleId,
                       Long cycleVersion, String reviewBatchId, String approvedAt) { }

    public View view(String taskId) {
        TaskRow task = task(taskId);
        TaskExecutionCycleRow cycle = cycles.latest(taskId);
        JudgeReviewBatchRow batch = latestBatch(taskId);
        var approval = cycle == null ? null : approvals.find(cycle.id()).orElse(null);
        return new View(approval == null && eligible(task, cycle, batch), approval != null, task.version(),
                cycle == null ? null : cycle.id(), cycle == null ? null : cycle.version(),
                batch == null ? null : batch.id(), approval == null ? null : approval.approvedAt());
    }

    public WorkspaceLeaseReconciliationService.Result approve(String taskId, Request request) {
        if (request == null || request.expectedTaskVersion() == null || request.expectedCycleVersion() == null
                || request.cycleId() == null) throw new BadRequestException("HUMAN_APPROVAL_REQUEST_REQUIRED", "请确认当前评审结果");
        transactions.executeWithoutResult(ignored -> {
            TaskRow task = task(taskId);
            TaskExecutionCycleRow cycle = cycles.latest(taskId);
            if (cycle == null || !Objects.equals(cycle.id(), request.cycleId())) throw stale();
            var existing = approvals.find(cycle.id()).orElse(null);
            if (existing != null) {
                if (!TaskState.AWAITING_DECISION.name().equals(task.state())
                        || existing.taskVersion() != request.expectedTaskVersion()
                        || existing.cycleVersion() != request.expectedCycleVersion()
                        || !Objects.equals(existing.reviewBatchId(), request.reviewBatchId())) throw stale();
                return;
            }
            JudgeReviewBatchRow batch = latestBatch(taskId);
            if (task.version() != request.expectedTaskVersion() || cycle.version() != request.expectedCycleVersion()
                    || !Objects.equals(batch == null ? null : batch.id(), request.reviewBatchId())) throw stale();
            if (!eligible(task, cycle, batch)) throw new ConflictException("HUMAN_APPROVAL_UNAVAILABLE",
                    "只有确定性验证通过且执行与评审会话均已停止的最终评审，才能人工认定通过");
            approvals.insert(new TaskJudgeApprovalMapper.Approval(cycle.id(), taskId, batch == null ? null : batch.id(),
                    task.version(), cycle.version(), Instant.now().toString()));
            cycles.finish(taskId, ExecutionCycleState.SUCCEEDED, null, "人工认定通过；AI 双评审保留为参考");
            Map<String, Object> evidence = Map.of("cycleId", cycle.id(), "reviewBatchId", batch == null ? "LEGACY" : batch.id(),
                    "acceptanceSource", "LOCAL_HUMAN");
            states.updateTask(states.taskState(task, TaskState.AWAITING_DECISION), LifecycleEvent.APPROVE, evidence);
            events.emit(taskId, "task.judge_human_approved", evidence);
        });
        return settle(taskId);
    }

    public List<WorkspaceLeaseReconciliationService.Result> recoverHandoffs() {
        var results = new java.util.ArrayList<WorkspaceLeaseReconciliationService.Result>();
        for (String taskId : approvals.pendingHandoffs()) {
            try { results.add(settle(taskId)); }
            catch (RuntimeException unavailable) {
                events.emit(taskId, "task.recovery_blocked", Map.of("code", "HUMAN_APPROVAL_HANDOFF_BLOCKED",
                        "message", "人工认定已保存，工作区交接尚未完成；请检查登记目录后重试"));
            }
        }
        return List.copyOf(results);
    }

    private WorkspaceLeaseReconciliationService.Result settle(String taskId) {
        TaskRow task = task(taskId);
        if (leases.ownsActiveLease(taskId)) checkpoints.freeze(task, cycles.latest(taskId));
        return leases.reconcileHolder(taskId, WorkspaceLeaseReconciliationService.TRIGGER_AUTO,
                "HUMAN_APPROVAL_CHECKPOINTED");
    }

    private boolean eligible(TaskRow task, TaskExecutionCycleRow cycle, JudgeReviewBatchRow batch) {
        if (!TaskState.WAITING_INPUT.name().equals(task.state()) || cycle == null || !"RUNNING".equals(cycle.state())
                || batch != null && (!cycle.id().equals(batch.executionCycleId()) || "RUNNING".equals(batch.state()))) return false;
        var stages = mapper.listStages(task.id());
        if (stages.isEmpty() || stages.stream().anyMatch(stage -> !"SUCCEEDED".equals(stage.state())
                || mapper.latestAttempt(stage.id()).filter(attempt -> "SUCCEEDED".equals(attempt.state())).isEmpty())) return false;
        String finalAttempt = mapper.latestAttempt(stages.getLast().id()).orElseThrow().id();
        if (batch != null && !finalAttempt.equals(batch.finalAttemptId())) return false;
        if (batch == null && mapper.listJudgeRuns(task.id()).stream().noneMatch(run -> finalAttempt.equals(run.attemptId()))) return false;
        if (mapper.listAttempts(task.id()).stream().anyMatch(row -> "RUNNING".equals(row.state()))
                || mapper.listSessions(task.id()).stream().anyMatch(row -> !SessionState.valueOf(row.state()).terminal())
                || !mapper.activeJudgeRuns(task.id()).isEmpty()
                || mapper.existsUnstoppedAcceptanceCandidateHandoffCleanupForTask(task.id())
                || approvals.hasUnstoppedCandidates(task.id())
                || mapper.listVerifierRuntimes(task.id()).stream().anyMatch(row ->
                    Set.of("STARTING", "RUNNING", "STOPPING", "DISCONNECTED").contains(row.state()))) return false;
        Set<String> confirmed = mapper.listErrors(task.id()).stream()
                .filter(row -> "SESSION_ABORT_CLEANUP_CONFIRMED".equals(row.code()))
                .map(ErrorEventRow::sessionId).collect(java.util.stream.Collectors.toSet());
        return mapper.listErrors(task.id()).stream().noneMatch(row -> "SESSION_ABORT_UNCONFIRMED".equals(row.code())
                && !confirmed.contains(row.sessionId()));
    }

    private JudgeReviewBatchRow latestBatch(String taskId) {
        return mapper.listJudgeReviewBatches(taskId).stream()
                .max(java.util.Comparator.comparingInt(JudgeReviewBatchRow::generation)).orElse(null);
    }
    private TaskRow task(String taskId) {
        return mapper.findTask(taskId).orElseThrow(() -> new NotFoundException("Task not found: " + taskId));
    }
    private ConflictException stale() { return new ConflictException("HUMAN_APPROVAL_STALE", "评审结果已变化，请刷新后重新确认"); }
}
