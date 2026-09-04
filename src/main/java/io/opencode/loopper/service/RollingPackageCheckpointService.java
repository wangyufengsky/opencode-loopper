package io.opencode.loopper.service;

import io.opencode.loopper.domain.ExecutionCycleKind;
import io.opencode.loopper.domain.ExecutionCycleState;
import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.domain.LifecycleMachineType;
import io.opencode.loopper.domain.LifecycleScopeType;
import io.opencode.loopper.domain.TaskPackageRunState;
import io.opencode.loopper.domain.TaskState;
import io.opencode.loopper.domain.TaskWorkspacePolicy;
import io.opencode.loopper.domain.WorkspaceCheckpointState;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.DesignWorkPackageRow;
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.PackageFactSnapshotRow;
import io.opencode.loopper.persistence.TaskExecutionCycleRow;
import io.opencode.loopper.persistence.TaskPackageRunRow;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.persistence.TaskSpecRevisionRow;
import io.opencode.loopper.persistence.TaskWorkspaceCheckpointRow;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Restart-safe Checkpoint -> fact -> next-package saga. */
@Service
final class RollingPackageCheckpointService {
    private final LoopperMapper mapper;
    private final LifecycleTransitionService lifecycle;
    private final RollingPackageCodec codec;
    private final TaskExecutionCycleService cycles;
    private final TaskWorkspaceCheckpointService checkpoints;
    private final WorkspaceLeaseReconciliationService leases;
    private final TaskEventService events;
    private final RollingPackageDesignContinuationService designContinuation;
    private final ObjectProvider<TaskService> taskRunners;

    RollingPackageCheckpointService(LoopperMapper mapper, LifecycleTransitionService lifecycle, ObjectMapper json,
                                    TaskExecutionCycleService cycles, TaskWorkspaceCheckpointService checkpoints,
                                    WorkspaceLeaseReconciliationService leases, TaskEventService events,
                                    RollingPackageDesignContinuationService designContinuation,
                                    ObjectProvider<TaskService> taskRunners) {
        this.mapper = mapper;
        this.lifecycle = lifecycle;
        this.codec = new RollingPackageCodec(json);
        this.cycles = cycles;
        this.checkpoints = checkpoints;
        this.leases = leases;
        this.events = events;
        this.designContinuation = designContinuation;
        this.taskRunners = taskRunners;
    }

    boolean complete(TaskRow task, String packageRunId, String attemptId,
                     RollingPackageService.FactEvidence evidence) {
        TaskPackageRunRow run = mapper.findTaskPackageRun(packageRunId).orElseThrow(() ->
                new ConflictException("PACKAGE_RUN_MISSING", "工作包执行记录已不存在"));
        PackageFactSnapshotRow fact = mapper.findPackageFactSnapshot(run.id()).orElse(null);
        TaskWorkspaceCheckpointRow checkpoint;
        if (fact == null) {
            if (TaskPackageRunState.VERIFYING.name().equals(run.state())) {
                updateRun(run, TaskPackageRunState.CHECKPOINTING, LifecycleEvent.BEGIN_PACKAGE_CHECKPOINT,
                        run.waitingReasonCode());
                run = mapper.findTaskPackageRun(run.id()).orElseThrow();
            }
            if (!TaskPackageRunState.CHECKPOINTING.name().equals(run.state())) {
                throw new ConflictException("PACKAGE_CHECKPOINT_STATE_INVALID", "工作包不在可恢复的事实冻结状态");
            }
            TaskExecutionCycleRow cycle = completedPackageCycle(task, run);
            checkpoint = checkpoints.freeze(mapper.findTask(task.id()).orElse(task), cycle);
            if (!WorkspaceCheckpointState.READY.name().equals(checkpoint.state())) {
                blockCheckpoint(task, run);
                throw new ConflictException("PACKAGE_CHECKPOINT_BLOCKED", "工作包事实冻结失败，需要人工处理");
            }
            if (evidence == null) {
                throw new ConflictException("PACKAGE_FACT_EVIDENCE_MISSING", "工作包恢复缺少已持久化的机器验收证据");
            }
            fact = insertFact(task, run, checkpoint, attemptId, evidence);
        } else {
            checkpoint = mapper.findTaskWorkspaceCheckpoint(fact.checkpointId()).orElseThrow(() ->
                    new ConflictException("PACKAGE_CHECKPOINT_MISSING", "工作包事实引用的 Checkpoint 不存在"));
        }
        TaskPackageRunRow current = mapper.findTaskPackageRun(run.id()).orElse(run);
        if (TaskPackageRunState.CHECKPOINTING.name().equals(current.state())) {
            updateRun(current, TaskPackageRunState.FACT_FROZEN, LifecycleEvent.FREEZE_PACKAGE_FACT, null);
        }
        return advance(mapper.findTask(task.id()).orElse(task),
                mapper.findTaskPackageRun(run.id()).orElse(current), fact, checkpoint);
    }

    private TaskExecutionCycleRow completedPackageCycle(TaskRow task, TaskPackageRunRow run) {
        TaskExecutionCycleRow active = cycles.active(task.id());
        if (active != null) {
            if (!run.id().equals(active.packageRunId())) {
                throw new ConflictException("PACKAGE_EXECUTION_CYCLE_MISMATCH", "活动执行轮次不属于当前工作包");
            }
            return cycles.finish(task.id(), ExecutionCycleState.SUCCEEDED, null, null);
        }
        TaskExecutionCycleRow latest = cycles.latest(task.id());
        if (latest == null || !run.id().equals(latest.packageRunId())
                || !ExecutionCycleState.SUCCEEDED.name().equals(latest.state())) {
            throw new ConflictException("PACKAGE_EXECUTION_CYCLE_MISSING", "工作包成功执行轮次不存在");
        }
        return latest;
    }

    private PackageFactSnapshotRow insertFact(TaskRow task, TaskPackageRunRow run,
                                              TaskWorkspaceCheckpointRow checkpoint, String attemptId,
                                              RollingPackageService.FactEvidence evidence) {
        TaskSpecRevisionRow spec = mapper.latestTaskSpecRevision(task.id()).orElseThrow();
        Map<String, Object> proven = new LinkedHashMap<>();
        proven.put("attemptId", attemptId);
        proven.put("checkpointId", checkpoint.id());
        proven.put("inputTree", evidence.inputTree());
        proven.put("outputTree", checkpoint.checkpointTree());
        proven.put("manifestSha256", checkpoint.manifestSha256());
        proven.put("diffSha256", evidence.diffSha256());
        proven.put("evidenceSha256", evidence.evidenceSha256());
        proven.put("diffArtifactId", evidence.diffArtifactId());
        proven.put("evidenceArtifactId", evidence.evidenceArtifactId());
        Map<String, Object> accepted = new LinkedHashMap<>();
        accepted.put("packageKey", run.packageKey());
        accepted.put("designRevision", run.acceptedDesignRevision());
        accepted.put("taskSpecRevision", spec.revision());
        accepted.put("taskSpecSha256", spec.specSha256());
        DesignWorkPackageRow workPackage = mapper.findDesignWorkPackage(run.designWorkPackageId()).orElseThrow();
        accepted.put("designWorkPackageId", workPackage.id());
        accepted.put("dependencies", codec.jsonValue(workPackage.dependenciesJson()));
        accepted.put("deliverables", codec.jsonValue(workPackage.deliverablesJson()));
        accepted.put("acceptanceConditions", codec.jsonValue(workPackage.acceptanceIntentJson()));
        accepted.put("stages", mapper.listStages(task.id()).stream()
                .filter(stage -> evidence.acceptedStageIds().contains(stage.id()))
                .map(stage -> Map.of("id", stage.id(), "ordinal", stage.ordinal(), "objective", stage.objective(),
                        "deliverables", codec.jsonValue(stage.deliverablesJson()),
                        "verifiers", codec.jsonValue(stage.verifiersJson()))).toList());
        PackageFactSnapshotRow row = new PackageFactSnapshotRow(UUID.randomUUID().toString(), task.id(), run.id(),
                checkpoint.id(), attemptId, evidence.inputTree(), checkpoint.checkpointTree(),
                checkpoint.manifestSha256(), evidence.diffSha256(), evidence.evidenceSha256(), codec.write(proven),
                codec.write(accepted), codec.boundedSummary(evidence.navigationSummary()), spec.specSha256(), now());
        mapper.insertPackageFactSnapshot(row);
        return mapper.findPackageFactSnapshot(run.id()).orElse(row);
    }

    private boolean advance(TaskRow task, TaskPackageRunRow frozen, PackageFactSnapshotRow fact,
                            TaskWorkspaceCheckpointRow checkpoint) {
        String activePlan = mapper.activeTaskPackagePlanRevision(task.id()).map(row -> row.id()).orElse(null);
        TaskPackageRunRow next = mapper.listTaskPackageRuns(task.id()).stream()
                .filter(run -> activePlan != null && activePlan.equals(run.planRevisionId()))
                .filter(run -> TaskPackageRunState.PLANNED.name().equals(run.state()))
                .findFirst().orElse(null);
        if (next == null) return startFinalReview(task);
        if (TaskState.VERIFYING.name().equals(task.state())) {
            updateTask(task, TaskState.PACKAGE_DESIGNING, LifecycleEvent.BEGIN_PACKAGE_CHECKPOINT,
                    Map.of("frozenPackage", frozen.packageKey(), "nextPackage", next.packageKey()));
            task = mapper.findTask(task.id()).orElse(task);
        }
        if (TaskWorkspacePolicy.RELEASE_BETWEEN_PACKAGES.name().equals(task.workspacePolicy())) {
            next = setResumeCheckpoint(next, checkpoint.id());
            WorkspaceLeaseReconciliationService.Result result = leases.reconcileHolder(task.id(),
                    WorkspaceLeaseReconciliationService.TRIGGER_AUTO, "ROLLING_PACKAGE_FACT_FROZEN");
            if (result.blocked()) {
                blockLeaseRelease(task, next, result);
                return false;
            }
            taskRunners.getObject().continueAfterLeaseReconciliation(result);
            return false;
        }
        next = setResumeCheckpoint(next, checkpoint.id());
        startNextDesign(task, next, fact.id());
        return false;
    }

    void afterLeaseReconciliation(String taskId) {
        TaskRow task = mapper.findTask(taskId).orElse(null);
        if (task == null || !TaskWorkspacePolicy.RELEASE_BETWEEN_PACKAGES.name().equals(task.workspacePolicy())) return;
        TaskPackageRunRow next = mapper.currentTaskPackageRun(taskId).orElse(null);
        if (next == null || next.resumeCheckpointId() == null
                || !(TaskPackageRunState.PLANNED.name().equals(next.state())
                || TaskPackageRunState.WAITING_INPUT.name().equals(next.state())
                && "PACKAGE_CHECKPOINT_BLOCKED".equals(next.waitingReasonCode()))) return;
        PackageFactSnapshotRow fact = mapper.listPackageFactSnapshots(taskId).stream()
                .filter(item -> next.resumeCheckpointId().equals(item.checkpointId()))
                .reduce((left, right) -> right).orElse(null);
        if (fact == null) throw new ConflictException("PACKAGE_FACT_REQUIRED", "下一包恢复点不属于成功事实快照");
        startNextDesign(task, next, fact.id());
    }

    void retryLeaseRelease(TaskRow task, TaskPackageRunRow run) {
        if (!TaskPackageRunState.WAITING_INPUT.name().equals(run.state())
                || !"PACKAGE_CHECKPOINT_BLOCKED".equals(run.waitingReasonCode())) {
            throw new ConflictException("PACKAGE_CHECKPOINT_RETRY_UNAVAILABLE", "当前工作包不在 Checkpoint 释放阻塞状态");
        }
        WorkspaceLeaseReconciliationService.Result result = leases.reconcileHolder(task.id(),
                WorkspaceLeaseReconciliationService.TRIGGER_MANUAL, "ROLLING_PACKAGE_CHECKPOINT_RETRY");
        taskRunners.getObject().continueAfterLeaseReconciliation(result);
        if (result.blocked()) throw new ConflictException(result.blockerCode(), result.blockerMessage());
    }

    void recoverDesignDispatch(String taskId, String packageRunId) {
        TaskPackageRunRow run = mapper.findTaskPackageRun(packageRunId).orElse(null);
        if (run == null || !taskId.equals(run.taskId())
                || !TaskPackageRunState.DESIGNING.name().equals(run.state())) return;
        DesignWorkPackageRow workPackage = mapper.findDesignWorkPackage(run.designWorkPackageId()).orElseThrow();
        if (!resumableDesign(workPackage)) return;
        try { resumeDesign(taskId, run); }
        catch (RuntimeException failure) {
            blockDesignSnapshot(mapper.findTask(taskId).orElseThrow(), run, failure);
        }
    }

    void resumeDesign(String taskId, TaskPackageRunRow run) {
        if (!taskId.equals(run.taskId()) || !TaskPackageRunState.DESIGNING.name().equals(run.state())) {
            throw new ConflictException("PACKAGE_DESIGN_CONTINUATION_UNAVAILABLE",
                    "当前工作包不在可继续的设计状态");
        }
        DesignWorkPackageRow workPackage = mapper.findDesignWorkPackage(run.designWorkPackageId()).orElseThrow(() ->
                new ConflictException("DESIGN_WORK_PACKAGE_MISSING", "工作包设计来源不存在"));
        if (!resumableDesign(workPackage)) {
            throw new ConflictException("PACKAGE_DESIGN_CONTINUATION_UNAVAILABLE",
                    "当前工作包设计阶段已更新，请刷新后重试");
        }
        DesignerSessionRow session = mapper.findDesignerSessionByTask(taskId).orElseThrow(() ->
                new ConflictException("ROLLING_DESIGNER_MISSING", "滚动任务没有绑定设计会话"));
        designContinuation.resume(session.id(), workPackage.id(), rollingDesignPrompt(taskId));
    }

    private void startNextDesign(TaskRow task, TaskPackageRunRow next, String factId) {
        TaskRow currentTask = mapper.findTask(task.id()).orElse(task);
        if (TaskState.WAITING_INPUT.name().equals(currentTask.state())) {
            updateTask(currentTask, TaskState.PACKAGE_DESIGNING, LifecycleEvent.BEGIN_PACKAGE_DESIGN,
                    Map.of("packageKey", next.packageKey(), "source", "CHECKPOINT_RELEASE_RECOVERY"));
        }
        TaskPackageRunRow current = mapper.findTaskPackageRun(next.id()).orElse(next);
        updateRun(current, TaskPackageRunState.DESIGNING, LifecycleEvent.BEGIN_PACKAGE_DESIGN, null);
        current = mapper.findTaskPackageRun(current.id()).orElse(current);
        DesignWorkPackageRow workPackage = mapper.findDesignWorkPackage(current.designWorkPackageId()).orElseThrow();
        if ("PENDING".equals(workPackage.state())) {
            try { dispatchDesign(task.id(), workPackage); }
            catch (RuntimeException failure) { blockDesignSnapshot(mapper.findTask(task.id()).orElse(task), current, failure); return; }
        }
        events.emit(task.id(), "package.design_started", Map.of("packageKey", current.packageKey(),
                "factSnapshotId", factId));
    }

    private void blockDesignSnapshot(TaskRow task, TaskPackageRunRow run, RuntimeException failure) {
        TaskPackageRunRow current = mapper.findTaskPackageRun(run.id()).orElse(run);
        if (TaskPackageRunState.DESIGNING.name().equals(current.state())) {
            updateRun(current, TaskPackageRunState.WAITING_INPUT, LifecycleEvent.REQUIRE_INPUT,
                    "PACKAGE_CHECKPOINT_BLOCKED");
        }
        TaskRow latest = mapper.findTask(task.id()).orElse(task);
        if (!TaskState.WAITING_INPUT.name().equals(latest.state())) {
            updateTask(latest, TaskState.WAITING_INPUT, LifecycleEvent.REQUIRE_INPUT,
                    Map.of("reason", "PACKAGE_CHECKPOINT_BLOCKED", "detail", safe(failure.getMessage())));
        }
        events.emit(task.id(), "package.design_snapshot_blocked", Map.of("packageKey", run.packageKey(),
                "code", "PACKAGE_CHECKPOINT_BLOCKED", "message", safe(failure.getMessage())));
    }

    private void blockLeaseRelease(TaskRow task, TaskPackageRunRow next,
                                   WorkspaceLeaseReconciliationService.Result result) {
        updateRun(mapper.findTaskPackageRun(next.id()).orElse(next), TaskPackageRunState.WAITING_INPUT,
                LifecycleEvent.REQUIRE_INPUT, "PACKAGE_CHECKPOINT_BLOCKED");
        TaskRow current = mapper.findTask(task.id()).orElse(task);
        if (!TaskState.WAITING_INPUT.name().equals(current.state())) {
            updateTask(current, TaskState.WAITING_INPUT, LifecycleEvent.REQUIRE_INPUT,
                    Map.of("reason", "PACKAGE_CHECKPOINT_BLOCKED", "blockerCode", result.blockerCode()));
        }
        events.emit(task.id(), "package.checkpoint_release_blocked", Map.of("packageKey", next.packageKey(),
                "code", result.blockerCode(), "message", result.blockerMessage()));
    }

    private boolean startFinalReview(TaskRow task) {
        if (!TaskState.JUDGING.name().equals(task.state())) {
            updateTask(task, TaskState.JUDGING, LifecycleEvent.BEGIN_FINAL_REVIEW,
                    Map.of("frozenPackages", mapper.listPackageFactSnapshots(task.id()).size()));
            task = mapper.findTask(task.id()).orElse(task);
        }
        TaskExecutionCycleRow active = cycles.active(task.id());
        if (active == null) {
            cycles.create(task, ExecutionCycleKind.INITIAL, null, null, "{}", null, "FINAL_REVIEW");
        } else if (!"FINAL_REVIEW".equals(active.cycleType())) {
            throw new ConflictException("FINAL_REVIEW_CYCLE_CONFLICT", "最终评审与活动执行轮次冲突");
        }
        events.emit(task.id(), "package.all_facts_frozen", Map.of("state", TaskState.JUDGING.name()));
        return true;
    }

    void fail(TaskRow task, String code, String message, boolean writersStopped) {
        TaskPackageRunRow run = mapper.currentTaskPackageRun(task.id()).orElseThrow(() ->
                new ConflictException("PACKAGE_RUN_MISSING", "滚动任务没有当前工作包"));
        if (!TaskPackageRunState.WAITING_INPUT.name().equals(run.state())) {
            updateRun(run, TaskPackageRunState.WAITING_INPUT, LifecycleEvent.REQUIRE_INPUT,
                    "PACKAGE_EXECUTION_FAILED");
        }
        TaskExecutionCycleRow active = cycles.active(task.id());
        TaskWorkspaceCheckpointRow candidate = null;
        if (active != null) {
            TaskExecutionCycleRow ended = cycles.finish(task.id(), ExecutionCycleState.FAILED, code, message);
            if (writersStopped) candidate = checkpoints.freeze(mapper.findTask(task.id()).orElse(task), ended);
        }
        if (candidate != null && WorkspaceCheckpointState.READY.name().equals(candidate.state())) {
            setResumeCheckpoint(mapper.findTaskPackageRun(run.id()).orElse(run), candidate.id());
        }
        TaskRow current = mapper.findTask(task.id()).orElse(task);
        if (!TaskState.WAITING_INPUT.name().equals(current.state())) {
            updateTask(current, TaskState.WAITING_INPUT, LifecycleEvent.REQUIRE_INPUT,
                    Map.of("reason", "PACKAGE_EXECUTION_FAILED", "packageKey", run.packageKey()));
        }
        events.emit(task.id(), "package.execution_failed", Map.of("packageKey", run.packageKey(), "code", code,
                "candidateCheckpointState", candidate == null ? "NOT_CAPTURED" : candidate.state()));
        if (candidate != null && WorkspaceCheckpointState.READY.name().equals(candidate.state())
                && TaskWorkspacePolicy.RELEASE_BETWEEN_PACKAGES.name().equals(task.workspacePolicy())) {
            taskRunners.getObject().continueAfterLeaseReconciliation(leases.reconcileHolder(task.id(),
                    WorkspaceLeaseReconciliationService.TRIGGER_AUTO, "ROLLING_PACKAGE_FAILURE_CHECKPOINTED"));
        }
    }

    private void blockCheckpoint(TaskRow task, TaskPackageRunRow run) {
        updateRun(mapper.findTaskPackageRun(run.id()).orElse(run), TaskPackageRunState.WAITING_INPUT,
                LifecycleEvent.REQUIRE_INPUT, "PACKAGE_CHECKPOINT_BLOCKED");
        updateTask(mapper.findTask(task.id()).orElse(task), TaskState.WAITING_INPUT, LifecycleEvent.REQUIRE_INPUT,
                Map.of("reason", "PACKAGE_CHECKPOINT_BLOCKED"));
    }

    private void dispatchDesign(String taskId, DesignWorkPackageRow workPackage) {
        DesignerSessionRow session = mapper.findDesignerSessionByTask(taskId).orElseThrow(() ->
                new ConflictException("ROLLING_DESIGNER_MISSING", "滚动任务没有绑定设计会话"));
        designContinuation.resume(session.id(), workPackage.id(), rollingDesignPrompt(taskId));
    }

    private String rollingDesignPrompt(String taskId) {
        return "这是逐包闭环任务的下一工作包。只能把下列事实层和当前只读快照作为现状；"
                + "AI 导航摘要仅帮助定位，不属于机器证据。不得假设初始仓库仍是当前状态。"
                + codec.factContext(mapper.listPackageFactSnapshots(taskId));
    }

    private boolean resumableDesign(DesignWorkPackageRow workPackage) {
        return java.util.Set.of("PENDING", "QUESTIONING", "DESIGNING").contains(workPackage.state());
    }

    private void updateRun(TaskPackageRunRow from, TaskPackageRunState state, LifecycleEvent event, String reason) {
        TaskPackageRunRow to = new TaskPackageRunRow(from.id(), from.taskId(), from.planRevisionId(),
                from.designWorkPackageId(), from.packageKey(), from.ordinal(), from.title(), state.name(),
                from.correctionOfPackageRunId(), from.discussionRevision(), from.designRevision(),
                from.acceptedDesignRevision(), reason, from.createdAt(), now(), from.version(),
                from.resumeCheckpointId());
        lifecycle.transition(packageSubject(from), from.state(), to.state(), event, reason,
                Map.of("packageKey", from.packageKey()), () -> mapper.updateTaskPackageRun(to),
                () -> new ConflictException("PACKAGE_RUN_VERSION_CONFLICT", "工作包状态已被并发更新"));
    }

    private TaskPackageRunRow setResumeCheckpoint(TaskPackageRunRow run, String checkpointId) {
        if (checkpointId.equals(run.resumeCheckpointId())) return run;
        if (mapper.updateTaskPackageRunResumeCheckpoint(run.id(), checkpointId, now(), run.version()) != 1) {
            throw new ConflictException("PACKAGE_RUN_VERSION_CONFLICT", "工作包恢复事实点已更新");
        }
        return mapper.findTaskPackageRun(run.id()).orElseThrow();
    }

    private void updateTask(TaskRow from, TaskState state, LifecycleEvent event, Map<String, ?> metadata) {
        TaskRow to = new TaskRow(from.id(), from.projectId(), from.loopDraftId(), from.title(), state.name(),
                from.worktreePath(), from.branchName(), from.sourceBranch(), from.baselineCommit(), from.createdAt(),
                now(), from.version(), from.taskProfileId(), from.rolePackId(), from.rolePackVersion(),
                from.executionMode(), from.workspacePolicy());
        Object waitingReason = metadata.get("reason");
        String reason = state == TaskState.WAITING_INPUT && waitingReason instanceof String value && !value.isBlank()
                ? value : null;
        if (state == TaskState.WAITING_INPUT && reason == null) {
            throw new IllegalArgumentException("WAITING_INPUT transitions require a reason code");
        }
        lifecycle.transition(taskSubject(from), from.state(), to.state(), event, reason, metadata,
                () -> mapper.updateTaskState(to),
                () -> new ConflictException("TASK_VERSION_CONFLICT", "任务状态已被并发更新"));
    }

    private LifecycleTransitionService.Subject taskSubject(TaskRow row) {
        return new LifecycleTransitionService.Subject(LifecycleMachineType.TASK, row.id(),
                LifecycleScopeType.TASK, row.id());
    }
    private LifecycleTransitionService.Subject packageSubject(TaskPackageRunRow row) {
        return new LifecycleTransitionService.Subject(LifecycleMachineType.TASK_PACKAGE_RUN, row.id(),
                LifecycleScopeType.TASK, row.taskId());
    }
    private String now() { return Instant.now().toString(); }
    private String safe(String value) { return value == null || value.isBlank() ? "工作区事实快照无法验证" : value; }
}
