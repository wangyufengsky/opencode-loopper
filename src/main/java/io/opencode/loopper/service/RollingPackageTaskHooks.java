package io.opencode.loopper.service;

import io.opencode.loopper.domain.ExecutionCycleKind;
import io.opencode.loopper.domain.AttemptState;
import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.domain.StageState;
import io.opencode.loopper.domain.TaskFailure;
import io.opencode.loopper.domain.TaskPackageRunState;
import io.opencode.loopper.domain.TaskState;
import io.opencode.loopper.persistence.AttemptRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.TaskPackageRunRow;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.persistence.TaskWorkspaceCheckpointRow;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

/** Keeps rolling-package boundary logic out of the legacy execution orchestrator. */
@Service
final class RollingPackageTaskHooks {
    private final LoopperMapper mapper;
    private final TaskExecutionCycleService cycles;
    private final TaskStateStore states;
    private final TaskEvidenceService evidence;
    private final RollingPackageService rolling;

    RollingPackageTaskHooks(LoopperMapper mapper, TaskExecutionCycleService cycles, TaskStateStore states,
                            TaskEvidenceService evidence, RollingPackageService rolling) {
        this.mapper = mapper;
        this.cycles = cycles;
        this.states = states;
        this.evidence = evidence;
        this.rolling = rolling;
    }

    boolean applies(String taskId) { return rolling.rolling(taskId); }
    RollingPackageService.ExecutionRequest executionRequest(String taskId, String packageRunId,
                                                             long taskVersion, long packageVersion) {
        return rolling.executionRequest(taskId, packageRunId, taskVersion, packageVersion);
    }
    void requestExecutionInTransaction(RollingPackageService.ExecutionRequest request) {
        rolling.requestExecutionInTransaction(request);
    }

    TaskPackageRunRow prepareRetry(String taskId, String packageRunId, long taskVersion, long packageVersion) {
        TaskPackageRunRow run = rolling.prepareFailedCandidateRetry(taskId, packageRunId, taskVersion, packageVersion);
        mapper.listStages(taskId).stream()
                .filter(stage -> run.id().equals(stage.packageRunId()) && StageState.FAILED.name().equals(stage.state()))
                .map(stage -> states.stageState(stage, StageState.PENDING))
                .forEach(stage -> states.updateStage(stage, LifecycleEvent.RECOVER));
        return run;
    }

    void ensureExecutionCycle(TaskRow task, String budgetJson) {
        if (!applies(task.id())) {
            cycles.ensureInitial(task, budgetJson);
            return;
        }
        if (cycles.active(task.id()) != null) return;
        TaskPackageRunRow run = mapper.currentTaskPackageRun(task.id()).orElseThrow(() ->
                new TaskFailure("PACKAGE_RUN_MISSING", "滚动任务没有当前工作包"));
        cycles.create(task, ExecutionCycleKind.INITIAL, null, null, budgetJson,
                run.id(), "PACKAGE_EXECUTION");
    }

    void executionStarted(String taskId) { rolling.executionStarted(taskId); }
    void verificationStarted(String taskId) { rolling.verificationStarted(taskId); }

    CheckpointOutcome checkpoint(TaskRow task, String packageRunId, String attemptId) {
        AttemptRow attempt = mapper.findAttempt(attemptId).orElseThrow(() ->
                new TaskFailure("ATTEMPT_MISSING", "工作包验收 Attempt 已不存在"));
        TaskPackageRunRow run = mapper.findTaskPackageRun(packageRunId).orElseThrow(() ->
                new TaskFailure("PACKAGE_RUN_MISSING", "工作包执行记录已不存在"));
        var factEvidence = evidence.capturePackageFactEvidence(task, run, attempt);
        return new CheckpointOutcome(attempt,
                rolling.checkpointSuccessfulPackage(task, run.id(), attempt.id(), factEvidence));
    }

    List<CheckpointOutcome> recoverIncomplete() {
        List<CheckpointOutcome> recovered = new ArrayList<>();
        for (TaskRow task : mapper.listRecoverableTasks()) {
            if (!applies(task.id())) continue;
            TaskPackageRunRow run = mapper.currentTaskPackageRun(task.id()).orElse(null);
            if (run != null && TaskPackageRunState.DESIGNING.name().equals(run.state())) {
                rolling.recoverDesignDispatch(task.id(), run.id());
                continue;
            }
            if (run == null || !List.of(TaskPackageRunState.VERIFYING.name(),
                    TaskPackageRunState.CHECKPOINTING.name()).contains(run.state())) {
                run = mapper.listTaskPackageRuns(task.id()).stream()
                        .filter(item -> TaskPackageRunState.FACT_FROZEN.name().equals(item.state()))
                        .reduce((left, right) -> right).orElse(null);
            }
            if (run == null || !List.of(TaskPackageRunState.VERIFYING.name(),
                    TaskPackageRunState.CHECKPOINTING.name(), TaskPackageRunState.FACT_FROZEN.name())
                    .contains(run.state())) continue;
            TaskPackageRunRow recoveredRun = run;
            var fact = mapper.findPackageFactSnapshot(recoveredRun.id()).orElse(null);
            AttemptRow attempt = fact == null ? mapper.listStages(task.id()).stream()
                    .filter(stage -> recoveredRun.id().equals(stage.packageRunId()))
                    .max(Comparator.comparingInt(stage -> stage.ordinal()))
                    .flatMap(stage -> mapper.latestAttempt(stage.id())).orElse(null)
                    : mapper.findAttempt(fact.successfulAttemptId()).orElse(null);
            boolean stagesSucceeded = mapper.listStages(task.id()).stream()
                    .filter(stage -> recoveredRun.id().equals(stage.packageRunId()))
                    .allMatch(stage -> StageState.SUCCEEDED.name().equals(stage.state()));
            if (attempt == null || !AttemptState.SUCCEEDED.name().equals(attempt.state()) || !stagesSucceeded) continue;
            RollingPackageService.FactEvidence captured = fact == null
                    ? evidence.capturePackageFactEvidence(task, recoveredRun, attempt) : null;
            recovered.add(new CheckpointOutcome(attempt,
                    rolling.checkpointSuccessfulPackage(task, recoveredRun.id(), attempt.id(), captured)));
        }
        return List.copyOf(recovered);
    }

    void fail(TaskRow task, String code, String message, boolean writersStopped) {
        rolling.packageFailed(task, code, message, writersStopped);
    }

    void cancelRunsInTransaction(String taskId) {
        if (applies(taskId)) rolling.cancelRuns(taskId);
    }

    void supersedeRunsInTransaction(String taskId) {
        if (applies(taskId)) rolling.runs(taskId).forEach(rolling::supersedeRun);
    }

    void requireTerminalRuns(String taskId) {
        if (applies(taskId) && rolling.runs(taskId).stream()
                .anyMatch(run -> !TaskPackageRunState.valueOf(run.state()).terminal())) {
            throw new ConflictException("TASK_PACKAGE_RUN_ACTIVE", "工作包尚未收束，不能完成父任务");
        }
    }

    void deleteEvidenceBeforeAttempts(String taskId) {
        mapper.deletePackageFactSnapshotsForTask(taskId);
        mapper.deleteTaskSpecRevisionsForTask(taskId);
        mapper.detachTaskPackageRunReferences(taskId);
        mapper.detachTaskPackagePlanRevisionReferences(taskId);
    }

    void deletePlanAfterStages(String taskId) {
        mapper.deleteTaskExecutionCyclesForTask(taskId);
        mapper.deleteTaskPackageRunsForTask(taskId);
        mapper.deleteTaskPackagePlanRevisionsForTask(taskId);
    }

    void afterLeaseReconciliation(String taskId) { rolling.afterLeaseReconciliation(taskId); }

    TaskWorkspaceCheckpointRow resumeCheckpoint(TaskRow task) {
        if (!applies(task.id())) return null;
        return mapper.currentTaskPackageRun(task.id()).map(TaskPackageRunRow::resumeCheckpointId)
                .flatMap(id -> id == null ? java.util.Optional.empty() : mapper.findTaskWorkspaceCheckpoint(id))
                .orElse(null);
    }

    LoopSpec latestSpec(TaskRow task) { return rolling.latestSpec(task); }
    record CheckpointOutcome(AttemptRow attempt, boolean finalReview) { }
}
