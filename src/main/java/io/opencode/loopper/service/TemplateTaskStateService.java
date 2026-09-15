package io.opencode.loopper.service;

import io.opencode.loopper.domain.*;
import io.opencode.loopper.persistence.*;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Short transactions for the two fixed stages. No filesystem or Provider work occurs here. */
@Service
public final class TemplateTaskStateService {
    private final LoopperMapper mapper;
    private final TemplateTaskMapper templates;
    private final TaskStateStore states;
    private final TaskExecutionCycleService cycles;
    private final TemplateWorkspaceService workspace;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final TaskEventService events;

    TemplateTaskStateService(LoopperMapper mapper, TemplateTaskMapper templates, TaskStateStore states,
            TaskExecutionCycleService cycles, TemplateWorkspaceService workspace, ObjectMapper json,
            PlatformTransactionManager manager, TaskEventService events) {
        this.mapper = mapper; this.templates = templates; this.states = states; this.cycles = cycles;
        this.workspace = workspace; this.json = json; this.events = events; this.transactions = new TransactionTemplate(manager);
    }

    public TaskRow start(String taskId, TemplateTaskContractFactory.Frozen contract) {
        TaskRow task = task(taskId);
        if (!task.state().equals("PENDING_START")) return task;
        var identity = workspace.prepareStart(task);
        transactions.executeWithoutResult(ignored -> {
            TaskRow current = task(taskId);
            if (!current.state().equals("PENDING_START")) return;
            cycles.ensureInitial(current, json.writeValueAsString(contract.spec().budget()));
            workspace.admitInTransaction(current, identity);
            states.updateTask(states.taskState(current, TaskState.QUEUED), LifecycleEvent.REQUEST_START);
        });
        return task(taskId);
    }

    public void prepare(String taskId) {
        workspace.requireWritable(task(taskId));
        String canonicalRoot = workspace.root(task(taskId)).toString();
        transactions.executeWithoutResult(ignored -> {
            var current = task(taskId);
            if (current.state().equals("QUEUED")) {
                states.updateTask(states.taskState(current, TaskState.PREPARING), LifecycleEvent.PREPARE);
                current = task(taskId);
            }
            if (current.state().equals("PREPARING")) {
                var run = templates.findRun(taskId).orElseThrow();
                var prepared = new TaskRow(current.id(), current.projectId(), current.loopDraftId(), current.title(),
                        current.state(), canonicalRoot, run.branchLabel(), run.branchRef(), null,
                        current.createdAt(), Instant.now().toString(), current.version(), current.taskProfileId(),
                        current.rolePackId(), current.rolePackVersion(), current.executionMode(), current.workspacePolicy());
                if (templates.bindWorkspace(prepared) != 1) throw new ConflictException("TEMPLATE_PREPARE_CONFLICT", "执行目录准备发生冲突");
                states.updateTask(states.taskState(task(taskId), TaskState.READY), LifecycleEvent.PREPARATION_SUCCEEDED);
            }
            if (task(taskId).state().equals("READY")) states.updateTask(states.taskState(task(taskId), TaskState.RUNNING), LifecycleEvent.START);
        });
    }

    public AttemptRow attempt(String taskId, int ordinal) {
        return transactions.execute(ignored -> {
            requireState(taskId, "RUNNING");
            var stage = mapper.listStages(taskId).get(ordinal);
            var latest = mapper.latestAttempt(stage.id()).orElse(null);
            if (latest != null && latest.state().equals("RUNNING")) return latest;
            if (!stage.state().equals("PENDING") && !stage.state().equals("RUNNING")) {
                throw new ConflictException("TEMPLATE_STAGE_NOT_RUNNABLE", "报告阶段当前不可执行");
            }
            var limits = json.readValue(templates.findRun(taskId).orElseThrow().contractJson(), TemplateTaskContractFactory.Frozen.class).spec().limits();
            if (mapper.listAttempts(taskId).size() >= limits.maxTaskAttempts()
                    || latest != null && latest.ordinal() >= limits.maxStageAttempts()) {
                throw new TaskFailure("TEMPLATE_ATTEMPT_LIMIT", "已达到冻结的任务或阶段尝试上限，请查看证据后重新发起任务");
            }
            if (stage.state().equals("PENDING")) states.updateStage(states.stageState(stage, StageState.RUNNING), LifecycleEvent.START);
            var row = new AttemptRow(UUID.randomUUID().toString(), taskId, stage.id(), cycles.active(taskId).id(),
                    latest == null ? 1 : latest.ordinal() + 1, "RUNNING", null, null, Instant.now().toString(), null, 0);
            states.createAttempt(row);
            return row;
        });
    }

    public void completeStage(AttemptRow expected, String summary) {
        transactions.executeWithoutResult(ignored -> {
            requireState(expected.taskId(), "RUNNING");
            var attempt = mapper.findAttempt(expected.id()).orElseThrow();
            if (!attempt.state().equals("RUNNING")) throw new ConflictException("TEMPLATE_ATTEMPT_CHANGED", "报告执行轮次已经变化");
            var stage = mapper.findStage(attempt.stageId()).orElseThrow();
            mapper.insertVerification(new VerificationResultRow(UUID.randomUUID().toString(), attempt.id(), 0,
                    "TEMPLATE_REPORT", "PASS", summary, json.writeValueAsString(Map.of("summary", summary)), Instant.now().toString()));
            states.updateAttempt(states.finishAttempt(attempt, AttemptState.SUCCEEDED, null, summary));
            states.updateStage(states.stageState(stage, StageState.SUCCEEDED), LifecycleEvent.COMPLETE);
            states.updateTask(states.taskState(task(attempt.taskId()), TaskState.VERIFYING), LifecycleEvent.BEGIN_VERIFICATION);
            if (stage.ordinal() == 0) {
                states.updateTask(states.taskState(task(attempt.taskId()), TaskState.RUNNING), LifecycleEvent.ADVANCE_STAGE);
            } else if (json.readValue(templates.findRun(attempt.taskId()).orElseThrow().contractJson(),
                    TemplateTaskContractFactory.Frozen.class).requiresDualReview()) {
                states.updateTask(states.taskState(task(attempt.taskId()), TaskState.JUDGING), LifecycleEvent.BEGIN_FINAL_REVIEW);
            } else {
                var cycle = cycles.finish(attempt.taskId(), ExecutionCycleState.SUCCEEDED, null, summary);
                states.updateTask(states.taskState(task(attempt.taskId()), TaskState.AWAITING_DECISION),
                        LifecycleEvent.RECORD_CYCLE_RESULT, Map.of("cycleId", cycle.id(), "source", "TEMPLATE_REPORT_VALIDATED"));
            }
            events.emit(attempt.taskId(), "verification.template_stage_completed", Map.of("attemptId", attempt.id(), "stageId", stage.id()));
        });
    }

    public void waiting(String taskId, String code, String message) {
        transactions.executeWithoutResult(ignored -> {
            TaskRow task = task(taskId);
            if (!java.util.Set.of("RUNNING", "JUDGING", "VERIFYING", "QUEUED", "PREPARING", "READY").contains(task.state())) return;
            states.updateTask(states.taskState(task, TaskState.WAITING_INPUT), LifecycleEvent.REQUIRE_INPUT,
                    code, Map.of("message", message));
            mapper.insertError(new ErrorEventRow(UUID.randomUUID().toString(), taskId, null, null, null,
                    "TASK", code, message, false, "{}", Instant.now().toString()));
        });
    }

    /** A content repair preserves evidence/contract and gets fresh Attempt and Judge identities. */
    public boolean repair(String taskId) {
        return Boolean.TRUE.equals(transactions.execute(ignored -> {
            var task = task(taskId);
            if (!task.state().equals("WAITING_INPUT")) return false;
            var run = templates.findRun(taskId).orElseThrow();
            if (run.repairRound() >= 2 || !mapper.activeJudgeRuns(taskId).isEmpty()
                    || mapper.listSessions(taskId).stream().anyMatch(row -> !SessionState.valueOf(row.state()).terminal())) return false;
            var stage = mapper.listStages(taskId).get(1);
            var attempt = mapper.latestAttempt(stage.id()).orElse(null);
            if (attempt == null) return false;
            var limits = json.readValue(run.contractJson(), TemplateTaskContractFactory.Frozen.class).spec().limits();
            if (attempt.ordinal() >= limits.maxStageAttempts() || mapper.listAttempts(taskId).size() >= limits.maxTaskAttempts()) return false;
            if (attempt.state().equals("RUNNING")) states.updateAttempt(states.finishAttempt(attempt,
                    AttemptState.VERIFICATION_FAILED, "TEMPLATE_CONTENT_REPAIR", "结构化报告验证失败，按原合同修复"));
            if (stage.state().equals("SUCCEEDED")) states.updateStage(states.stageState(stage, StageState.PENDING), LifecycleEvent.RECOVER);
            if (templates.advanceRepair(taskId, run.version(), Instant.now().toString()) != 1) throw new ConflictException("TEMPLATE_REPAIR_CONFLICT", "返修轮次发生冲突");
            states.updateTask(states.taskState(task, TaskState.RUNNING), LifecycleEvent.RECOVER, Map.of("repairRound", run.repairRound() + 1));
            return true;
        }));
    }

    /** Explicit batch recovery does not reset execution time or task budget. */
    public void resumeBatch(String taskId) {
        transactions.executeWithoutResult(ignored -> {
            var task = task(taskId);
            if (task.state().equals("RUNNING")) return;
            String reason = TaskWaitingInputPolicy.reasonCode(task, mapper);
            if (!task.state().equals("WAITING_INPUT") || !java.util.Set.of("TEMPLATE_SUBMISSION_MISSING",
                    "TEMPLATE_MODEL_FAILED", "TEMPLATE_CONTENT_INVALID", "TEMPLATE_ANALYSIS_STALLED")
                    .contains(reason == null ? "" : reason))
                throw new ConflictException("TEMPLATE_RETRY_UNAVAILABLE", "请先处理当前任务的预算、时限或运行环境问题，再重试批次");
            states.updateTask(states.taskState(task, TaskState.RUNNING), LifecycleEvent.RECOVER,
                    Map.of("recovery", "EXPLICIT_BATCH_RETRY"));
        });
    }

    public void requestStop(String taskId) {
        transactions.executeWithoutResult(ignored -> {
            var task = task(taskId);
            if (!TaskState.valueOf(task.state()).terminal() && !task.state().equals("STOPPING")) {
                states.updateTask(states.taskState(task, TaskState.STOPPING), LifecycleEvent.CANCEL);
            }
        });
    }

    public TaskRow task(String id) { return mapper.findTask(id).orElseThrow(() -> new NotFoundException("模板任务不存在")); }
    private void requireState(String id, String state) {
        if (!task(id).state().equals(state)) throw new ConflictException("TEMPLATE_TASK_CHANGED", "任务状态已经变化，旧结果未被接受");
    }
}
