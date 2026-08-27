package io.opencode.loopper.service;

import io.opencode.loopper.domain.AttemptState;
import io.opencode.loopper.domain.ExecutionCycleState;
import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.domain.StageState;
import io.opencode.loopper.domain.TaskQueueState;
import io.opencode.loopper.domain.TaskState;
import io.opencode.loopper.domain.VerificationState;
import io.opencode.loopper.persistence.AttemptRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.StageRow;
import io.opencode.loopper.persistence.TaskQueueRow;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.runtime.DirectWorkspaceLeaseCoordinator;
import io.opencode.loopper.verification.VerifierOutcome;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Owns the durable Task cancellation protocol after TaskService accepts the command. */
final class TaskCancellationCoordinator {
    private static final String RETRY_CANCELLED = "CANCELLED";
    private final LoopperMapper mapper;
    private final TaskStateStore states;
    private final ManagedVerificationRuntimeService verifierRuntimes;
    private final DirectWorkspaceLeaseCoordinator leases;
    private final TaskEventService events;
    private final TaskExecutionCycleService executionCycles;
    private final TaskWriterTerminationService writers;

    TaskCancellationCoordinator(LoopperMapper mapper, TaskStateStore states,
                                ManagedVerificationRuntimeService verifierRuntimes,
                                DirectWorkspaceLeaseCoordinator leases, TaskEventService events,
                                TaskExecutionCycleService executionCycles,
                                TaskWriterTerminationService writers) {
        this.mapper = mapper;
        this.states = states;
        this.verifierRuntimes = verifierRuntimes;
        this.leases = leases;
        this.events = events;
        this.executionCycles = executionCycles;
        this.writers = writers;
    }

    TaskRow cancel(String taskId) {
        TaskRow current = task(taskId);
        if (TaskState.valueOf(current.state()).terminal()) return current;
        if (TaskState.AWAITING_DECISION.name().equals(current.state())) {
            throw new ConflictException("TASK_DECISION_REQUIRED",
                    "Task execution has ended; use the explicit result decision instead of runtime cancellation");
        }
        if (TaskState.STOPPING.name().equals(current.state())) {
            writers.retryDisconnectedSessions(current);
            return continueCancellation(taskId);
        }
        return requestCancellation(current);
    }

    TaskRow cancelDecision(String taskId) {
        TaskRow current = task(taskId);
        if (TaskState.CANCELLED.name().equals(current.state())) return current;
        if (!TaskState.AWAITING_DECISION.name().equals(current.state())) {
            throw new ConflictException("TASK_NOT_AWAITING_DECISION",
                    "Task does not have an execution result awaiting disposition");
        }
        return requestCancellation(current);
    }

    private TaskRow requestCancellation(TaskRow current) {
        states.cancelRetrySchedule(current.id(), RETRY_CANCELLED);
        TaskQueueRow queue = mapper.findTaskQueue(current.id()).orElse(null);
        if (queue != null && TaskQueueState.QUEUED.name().equals(queue.state())) leases.cancelQueued(current.id());
        states.updateTask(states.taskState(current, TaskState.STOPPING), LifecycleEvent.CANCEL,
                Map.of("remoteTerminationRequired", hasExternalWriter(current)));
        events.emit(current.id(), "task.cancellation_requested", Map.of("state", TaskState.STOPPING.name()));
        return continueCancellation(current.id());
    }

    TaskRow continueCancellation(String taskId) {
        TaskRow current = task(taskId);
        if (!TaskState.STOPPING.name().equals(current.state())) return current;
        VerifierOutcome runtimeStop = verifierRuntimes.stopTask(taskId, "task-cancelled");
        boolean verifierStopped = verifierRuntimes.confirmTaskStopped(taskId);
        boolean sessionsStopped = writers.stopSessions(current);
        boolean judgesStopped = writers.stopJudges(current);
        if (!verifierStopped || !sessionsStopped || !judgesStopped || writers.hasUnconfirmedWriter(taskId)) {
            Map<String, Object> pending = new LinkedHashMap<>();
            pending.put("state", TaskState.STOPPING.name());
            pending.put("sessionTerminationConfirmed", sessionsStopped && !writers.hasUnconfirmedWriter(taskId));
            pending.put("judgeTerminationConfirmed", judgesStopped);
            pending.put("verifierTerminationConfirmed", verifierStopped);
            if (runtimeStop != null && runtimeStop.state() == VerificationState.ERROR) {
                pending.put("verifierDetail", safeMessage(runtimeStop.summary()));
            }
            events.emit(taskId, "task.cancellation_waiting", pending);
            return task(taskId);
        }
        return finalizeCancellation(taskId);
    }

    private TaskRow finalizeCancellation(String taskId) {
        TaskRow current = task(taskId);
        if (!TaskState.STOPPING.name().equals(current.state())) return current;
        for (AttemptRow attempt : mapper.listAttempts(taskId)) {
            if (AttemptState.RUNNING.name().equals(attempt.state())) {
                states.updateAttempt(states.finishAttempt(attempt, AttemptState.CANCELLED, "CANCELLED", "Task cancelled"));
            }
        }
        for (StageRow stage : mapper.listStages(taskId)) {
            if (List.of(StageState.PENDING.name(), StageState.RUNNING.name(), StageState.PAUSED.name()).contains(stage.state())) {
                states.updateStage(states.stageState(stage, StageState.CANCELLED), LifecycleEvent.CANCEL);
            }
        }
        if (executionCycles.active(taskId) != null) {
            executionCycles.finish(taskId, ExecutionCycleState.INTERRUPTED, "TASK_CANCELLED", "Task was cancelled by the user");
        }
        states.updateTask(states.taskState(task(taskId), TaskState.CANCELLED), LifecycleEvent.COMPLETE,
                Map.of("remoteTerminationConfirmed", true));
        events.emit(taskId, "task.cancelled", Map.of("state", TaskState.CANCELLED.name()));
        return task(taskId);
    }

    private boolean hasExternalWriter(TaskRow current) {
        return !mapper.activeSessions(current.id()).isEmpty() || !mapper.activeJudgeRuns(current.id()).isEmpty()
                || mapper.listVerifierRuntimes(current.id()).stream()
                .anyMatch(runtime -> List.of("STARTING", "RUNNING", "STOPPING", "DISCONNECTED").contains(runtime.state()));
    }

    private TaskRow task(String taskId) {
        return mapper.findTask(taskId).orElseThrow(() -> new NotFoundException("Task not found: " + taskId));
    }

    private static String safeMessage(String message) {
        if (message == null || message.isBlank()) return "Unknown cancellation failure";
        String normalized = message.replaceAll("[\\r\\n]+", " ").trim();
        return normalized.substring(0, Math.min(normalized.length(), 500));
    }
}
