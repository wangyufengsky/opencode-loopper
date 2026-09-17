package io.opencode.loopper.service;

import io.opencode.loopper.domain.*;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.template.*;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Resumable fixed-template driver. Only one local worker per task may cross an external I/O boundary. */
@Service
public final class TemplateTaskCoordinator {
    private final LoopperMapper mapper;
    private final TemplateTaskMapper templates;
    private final TemplateTaskStateService states;
    private final TemplateRunEvidenceService evidence;
    private final TemplateBatchExecution batches;
    private final ObjectProvider<TaskService> tasks;
    private final TemplateHistoryReviewBatches history;
    private final TemplateGitCaptureGuard gitGuard;
    private final ObjectProvider<SnapshotReviewCoordinator> snapshotCoordinator;
    private final ObjectProvider<SnapshotReviewStore> snapshotStore;
    private final TemplateBatchRecoveryMapper recovery;
    private final TemplateBatchResilience resilience;
    private final ConcurrentHashMap<String, Thread> workers = new ConcurrentHashMap<>();
    private volatile boolean closing;

    TemplateTaskCoordinator(LoopperMapper mapper, TemplateTaskMapper templates, TemplateTaskStateService states,
            TemplateRunEvidenceService evidence, TemplateHistoryReviewBatches history,
            TemplateBatchExecution batches, ObjectProvider<TaskService> tasks, TemplateGitCaptureGuard gitGuard, ObjectProvider<SnapshotReviewCoordinator> snapshotCoordinator, ObjectProvider<SnapshotReviewStore> snapshotStore,
            TemplateBatchRecoveryMapper recovery, TemplateBatchResilience resilience) {
        this.snapshotCoordinator = snapshotCoordinator; this.snapshotStore = snapshotStore;
        this.mapper = mapper; this.templates = templates; this.states = states; this.evidence = evidence;
        this.gitGuard = gitGuard; this.history = history; this.batches = batches; this.tasks = tasks;
        this.recovery = recovery;
        this.resilience = resilience;
    }

    public TaskRow start(String taskId) {
        TaskRow current = states.task(taskId);
        if (!current.state().equals("PENDING_START")) {
            if (!TaskState.valueOf(current.state()).terminal()) dispatch(taskId);
            return current;
        }
        var task = states.start(taskId, evidence.contract(taskId));
        dispatch(taskId);
        return task;
    }

    @Scheduled(fixedDelayString = "${loopper.monitor-delay:2s}")
    void poll() {
        if (closing) return;
        for (String taskId : templates.activeTaskIds()) {
            if (!states.task(taskId).state().equals("STOPPING")) dispatch(taskId);
        }
    }

    public void dispatch(String taskId) {
        if (closing) return;
        Thread thread = Thread.ofVirtual().unstarted(() -> {
            try { executeCheckpoint(taskId); }
            finally { workers.remove(taskId, Thread.currentThread()); }
        });
        if (workers.putIfAbsent(taskId, thread) == null) thread.start();
    }

    void executeCheckpoint(String taskId) {
        try { advance(taskId); }
        catch (RuntimeException failure) { failed(taskId, failure); }
    }

    /** One bounded checkpoint per pass; exposed package-locally for deterministic integration tests. */
    void advance(String taskId) {
        var task = states.task(taskId);
        if (task.state().equals("STOPPING")) return;
        if (task.state().equals("WAITING_INPUT")) { repairIfEligible(task); return; }
        if (task.state().equals("AWAITING_DECISION")) { tasks.getObject().completeTemplateReport(taskId); return; }
        if (Set.of("QUEUED", "PREPARING", "READY").contains(task.state())) { states.prepare(taskId); return; }
        if (task.state().equals("JUDGING")) {
            var attempt = mapper.latestAttempt(mapper.listStages(taskId).get(1).id()).orElseThrow();
            tasks.getObject().launchRequiredJudges(task, attempt);
            return;
        }
        if (!task.state().equals("RUNNING")) return;
        if (SnapshotReview.applies(templates.findRun(taskId).orElseThrow().templateId())) {
            snapshotCoordinator.getObject().advance(taskId); return;
        }
        var contract = evidence.contract(taskId);
        var cycle = mapper.activeTaskExecutionCycle(taskId).orElseThrow();
        if (contract.spec().limits().timeoutsEnabled() && Duration.between(Instant.parse(cycle.startedAt()), Instant.now()).toSeconds() > contract.spec().limits().maxDurationSeconds()) {
            states.waiting(taskId, "TEMPLATE_DURATION_EXHAUSTED", "已达到本轮执行时限，请查看已生成证据后重新发起任务"); return;
        }
        if (!mapper.listStages(taskId).getFirst().state().equals("SUCCEEDED")) {
            var attempt = states.attempt(taskId, 0);
            var snapshot = evidence.freeze(taskId);
            states.completeStage(attempt, "已冻结 " + snapshot.commits().size() + " 个提交");
            return;
        }
        history.analyze(task, states.attempt(taskId, 1), contract);
    }

    private void repairIfEligible(TaskRow task) {
        String code = TaskWaitingInputPolicy.reasonCode(task, mapper);
        // This wait was entered only after all batches stopped. A late polling pass must not stop an explicit retry.
        if ("TEMPLATE_BATCHES_FAILED".equals(code) || !states.task(task.id()).state().equals("WAITING_INPUT")) return;
        if (TemplateBatchFailurePolicy.resumable(code)) {
            // A historic transport wait preserves live identities; only explicit per-batch stop intents proceed.
            for (var attempt : mapper.listAttempts(task.id())) for (var batch : templates.batches(task.id(), attempt.id()))
                if (batch.state().equals("STOPPING") || recovery.find(batch.id()).filter(intent -> intent.action().equals("STOP")).isPresent())
                    batches.stop(batch);
            return;
        }
        if (Set.of("TEMPLATE_CONTENT_INVALID", "JUDGE_CONFLICT", "JUDGE_REVIEW_NOT_APPROVED").contains(code == null ? "" : code)) {
            if (!SnapshotReview.applies(templates.findRun(task.id()).orElseThrow().templateId()) && states.repair(task.id())) return;
        }
        // Budget, elapsed time, transport ambiguity and exhausted repairs never authorize a fresh model call.
        stopBatches(task.id());
    }

    private void failed(String taskId, RuntimeException failure) {
        TaskRow task = states.task(taskId);
        if (task.state().equals("STOPPING") || TaskState.valueOf(task.state()).terminal()) return;
        String code = failure instanceof TaskFailure typed ? typed.code() : failure instanceof SessionFailure session ? session.code() : "TEMPLATE_EXECUTION_INTERRUPTED";
        String message = failure instanceof TaskFailure || Set.of("OPENCODE_OUTPUT_LENGTH_EXHAUSTED",
                "TEMPLATE_SUBMISSION_MISSING", "TEMPLATE_MCP_REQUIRED", "TEMPLATE_ANALYSIS_STALLED").contains(code) ? failure.getMessage() : "执行检查未完成，请查看审计证据并检查运行环境";
        states.waiting(taskId, code, message);
    }

    /** Called before generic cancellation: a cancelled Future alone is not proof its I/O has returned. */
    public boolean stopBeforeCancellation(String taskId) {
        states.requestStop(taskId);
        Thread worker = workers.putIfAbsent(taskId, Thread.currentThread());
        if (worker != null && worker != Thread.currentThread()) {
            worker.interrupt();
            return false;
        }
        try { return gitGuard.stopped(taskId) && stopBatches(taskId); }
        finally { if (worker == null) workers.remove(taskId, Thread.currentThread()); }
    }

    private boolean stopBatches(String taskId) {
        boolean stopped = true;
        for (var attempt : mapper.listAttempts(taskId)) {
            for (var batch : templates.batches(taskId, attempt.id())) {
                try { if (!batches.stop(batch)) stopped = false; }
                catch (RuntimeException unavailable) { stopped = false; }
            }
        }
        return stopped;
    }

    void deleteBeforeAttempts(String taskId) {
        resilience.delete(taskId);
        recovery.deleteCommands(taskId);
        recovery.deleteObservations(taskId);
        recovery.deleteRecovery(taskId);
        snapshotStore.getObject().delete(taskId);
        templates.deleteReportBundlesForTask(taskId);
        templates.deletePlanForTask(taskId);
        templates.deleteCandidateSubmissionsForTask(taskId);
        templates.deleteContinuationsForTask(taskId);
        templates.deleteBatchesForTask(taskId);
        templates.deleteRunForTask(taskId);
    }

    @PreDestroy
    void close() {
        closing = true;
        workers.values().forEach(Thread::interrupt);
    }
}
