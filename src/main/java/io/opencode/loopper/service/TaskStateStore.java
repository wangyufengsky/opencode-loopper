package io.opencode.loopper.service;

import io.opencode.loopper.domain.AttemptState;
import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.domain.LifecycleMachineType;
import io.opencode.loopper.domain.LifecycleScopeType;
import io.opencode.loopper.domain.SessionState;
import io.opencode.loopper.domain.StageState;
import io.opencode.loopper.domain.TaskState;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.AttemptRow;
import io.opencode.loopper.persistence.ExecutionSessionRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.StageRow;
import io.opencode.loopper.persistence.TaskRetryScheduleRow;
import io.opencode.loopper.persistence.TaskRow;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Owns optimistic lifecycle persistence and immutable row state projections for Task execution. */
@Component
final class TaskStateStore {
    private final LoopperMapper mapper;
    private final LifecycleTransitionService lifecycle;

    TaskStateStore(LoopperMapper mapper, LifecycleTransitionService lifecycle) {
        this.mapper = mapper;
        this.lifecycle = lifecycle;
    }

    TaskRow taskState(TaskRow row, TaskState state) {
        return new TaskRow(row.id(), row.projectId(), row.loopDraftId(), row.title(), state.name(),
                row.worktreePath(), row.branchName(), row.sourceBranch(), row.baselineCommit(), row.createdAt(),
                now(), row.version(), row.taskProfileId(), row.rolePackId(), row.rolePackVersion(),
                row.executionMode(), row.workspacePolicy());
    }

    StageRow stageState(StageRow row, StageState state) {
        return new StageRow(row.id(), row.taskId(), row.ordinal(), row.objective(), row.allowedPathsJson(),
                row.forbiddenPathsJson(), row.deliverablesJson(), row.verifiersJson(), state.name(), row.createdAt(),
                now(), row.version(), row.workPackageId(), row.stageKind(), row.executionStrategy(),
                row.artifactPlanId(), row.rolePackId(), row.rolePackVersion(), row.testPolicy(),
                row.technologiesJson(), row.projectStackProfileId(), row.componentKeysJson(), row.stackFingerprint(),
                row.packageRunId());
    }

    AttemptRow finishAttempt(AttemptRow row, AttemptState state, String failureKind, String summary) {
        return new AttemptRow(row.id(), row.taskId(), row.stageId(), row.executionCycleId(), row.ordinal(),
                state.name(), failureKind, safe(summary), row.createdAt(), now(), row.version());
    }

    ExecutionSessionRow sessionState(ExecutionSessionRow row, SessionState state) {
        return new ExecutionSessionRow(row.id(), row.taskId(), row.stageId(), row.attemptId(),
                row.externalSessionId(), state.name(), row.createdAt(), now(), row.version(), row.todoCapability());
    }

    TaskRetryScheduleRow retryState(TaskRetryScheduleRow row, String state, String dueAt,
                                    Integer remainingSeconds) {
        return new TaskRetryScheduleRow(row.id(), row.taskId(), row.stageId(), row.cause(), row.ordinal(),
                row.delaySeconds(), dueAt, remainingSeconds, row.prompt(), state, row.createdAt(), now(),
                row.version());
    }

    void updateRetrySchedule(TaskRetryScheduleRow row) {
        if (mapper.updateTaskRetrySchedule(row) != 1) {
            throw new ConflictException("RETRY_SCHEDULE_CONFLICT", "Retry schedule was updated concurrently");
        }
    }

    void cancelRetrySchedule(String taskId, String cancelledState) {
        mapper.findActiveTaskRetrySchedule(taskId).ifPresent(row ->
                updateRetrySchedule(retryState(row, cancelledState, row.dueAt(), null)));
    }

    Map<String, Object> retryAudit(TaskRetryScheduleRow row) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("retryCause", row.cause());
        details.put("retryOrdinal", row.ordinal());
        details.put("retryDelaySeconds", row.delaySeconds());
        details.put("retryDueAt", row.dueAt());
        if (row.remainingSeconds() != null) details.put("retryRemainingSeconds", row.remainingSeconds());
        return details;
    }

    void updateTask(TaskRow row) {
        updateTask(row, null, Map.of());
    }

    void updateTask(TaskRow row, LifecycleEvent event) {
        updateTask(row, event, Map.of());
    }

    void updateTask(TaskRow row, LifecycleEvent event, Map<String, ?> metadata) {
        updateTask(row, event, null, metadata);
    }

    void updateTask(TaskRow row, LifecycleEvent event, String reasonCode, Map<String, ?> metadata) {
        if (TaskState.WAITING_INPUT.name().equals(row.state())
                && (reasonCode == null || reasonCode.isBlank())) {
            throw new IllegalArgumentException("WAITING_INPUT transitions require a reason code");
        }
        TaskRow current = mapper.findTask(row.id())
                .orElseThrow(() -> new NotFoundException("Task not found: " + row.id()));
        lifecycle.transition(subject(LifecycleMachineType.TASK, row.id(), row.id()), current.state(), row.state(),
                event, reasonCode, metadata, () -> mapper.updateTaskState(row),
                () -> new ConflictException("TASK_VERSION_CONFLICT", "Task was updated concurrently"));
    }

    void updateStage(StageRow row) {
        updateStage(row, null);
    }

    void updateStage(StageRow row, LifecycleEvent event) {
        StageRow current = mapper.findStage(row.id())
                .orElseThrow(() -> new NotFoundException("Stage not found: " + row.id()));
        lifecycle.transition(subject(LifecycleMachineType.STAGE, row.id(), row.taskId()), current.state(),
                row.state(), event, null, Map.of(), () -> mapper.updateStageState(row),
                () -> new ConflictException("STAGE_VERSION_CONFLICT", "Stage was updated concurrently"));
    }

    void updateAttempt(AttemptRow row) {
        AttemptRow current = mapper.findAttempt(row.id())
                .orElseThrow(() -> new NotFoundException("Attempt not found: " + row.id()));
        lifecycle.transition(subject(LifecycleMachineType.ATTEMPT, row.id(), row.taskId()), current.state(),
                row.state(), row.failureKind(), Map.of(), () -> mapper.finishAttempt(row),
                () -> new ConflictException("ATTEMPT_VERSION_CONFLICT", "Attempt was updated concurrently"));
    }

    void updateSession(ExecutionSessionRow row) {
        ExecutionSessionRow current = mapper.findSession(row.id())
                .orElseThrow(() -> new NotFoundException("Session not found: " + row.id()));
        lifecycle.transition(subject(LifecycleMachineType.EXECUTION_SESSION, row.id(), row.taskId()),
                current.state(), row.state(), null, Map.of(), () -> mapper.updateSessionState(row),
                () -> new ConflictException("SESSION_VERSION_CONFLICT", "Session was updated concurrently"));
    }

    void createAttempt(AttemptRow row) {
        lifecycle.create(subject(LifecycleMachineType.ATTEMPT, row.id(), row.taskId()), row.state(), Map.of(),
                () -> mapper.insertAttempt(row),
                () -> new ConflictException("ATTEMPT_CREATE_CONFLICT", "Attempt could not be created"));
    }

    void createSession(ExecutionSessionRow row) {
        lifecycle.create(subject(LifecycleMachineType.EXECUTION_SESSION, row.id(), row.taskId()), row.state(), Map.of(),
                () -> mapper.insertSession(row),
                () -> new ConflictException("SESSION_CREATE_CONFLICT", "Session could not be created"));
    }

    LifecycleTransitionService.Subject subject(LifecycleMachineType machine, String entityId, String taskId) {
        return new LifecycleTransitionService.Subject(machine, entityId, LifecycleScopeType.TASK, taskId);
    }

    private String now() {
        return Instant.now().toString();
    }

    private String safe(String value) {
        return value == null ? "Unknown error" : value.substring(0, Math.min(value.length(), 4_000));
    }
}
