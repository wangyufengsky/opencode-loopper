package io.opencode.loopper.service;

import io.opencode.loopper.domain.ExecutionCycleKind;
import io.opencode.loopper.domain.ExecutionCycleState;
import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.domain.LifecycleMachineType;
import io.opencode.loopper.domain.LifecycleScopeType;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.StageRow;
import io.opencode.loopper.persistence.TaskExecutionCycleRow;
import io.opencode.loopper.persistence.TaskRow;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Persists execution outcomes separately from the user's final disposition of a Task. */
@Service
public class TaskExecutionCycleService {
    private final LoopperMapper mapper;
    private final LifecycleTransitionService lifecycle;

    public TaskExecutionCycleService(LoopperMapper mapper, LifecycleTransitionService lifecycle) {
        this.mapper = mapper;
        this.lifecycle = lifecycle;
    }

    public TaskExecutionCycleRow ensureInitial(TaskRow task, String budgetJson) {
        return mapper.activeTaskExecutionCycle(task.id()).orElseGet(() -> mapper.latestTaskExecutionCycle(task.id())
                .filter(row -> !ExecutionCycleState.valueOf(row.state()).terminal())
                .orElseGet(() -> create(task, ExecutionCycleKind.INITIAL, null, null, budgetJson)));
    }

    public TaskExecutionCycleRow create(TaskRow task, ExecutionCycleKind kind, StageRow startStage,
                                        String supplementalPrompt, String budgetJson) {
        if (mapper.activeTaskExecutionCycle(task.id()).isPresent()) {
            throw new ConflictException("TASK_EXECUTION_CYCLE_ACTIVE", "Task already has an active execution cycle");
        }
        String now = Instant.now().toString();
        TaskExecutionCycleRow row = new TaskExecutionCycleRow(UUID.randomUUID().toString(), task.id(),
                mapper.maxTaskExecutionCycleOrdinal(task.id()) + 1, kind.name(), ExecutionCycleState.RUNNING.name(),
                startStage == null ? null : startStage.id(), startStage == null ? null : startStage.ordinal(),
                bounded(supplementalPrompt, 12_000), budgetJson == null ? "{}" : budgetJson,
                null, null, now, now, null, 0);
        lifecycle.create(subject(row), row.state(), Map.of("kind", kind.name(), "ordinal", row.ordinal()),
                () -> mapper.insertTaskExecutionCycle(row),
                () -> new ConflictException("TASK_EXECUTION_CYCLE_CREATE_CONFLICT", "Execution cycle was created concurrently"));
        return row;
    }

    public TaskExecutionCycleRow finish(String taskId, ExecutionCycleState result, String code, String message) {
        if (result == ExecutionCycleState.RUNNING) throw new IllegalArgumentException("Cycle result must be terminal");
        TaskExecutionCycleRow current = mapper.activeTaskExecutionCycle(taskId)
                .orElseThrow(() -> new ConflictException("TASK_EXECUTION_CYCLE_MISSING", "Task has no active execution cycle"));
        TaskExecutionCycleRow ended = new TaskExecutionCycleRow(current.id(), current.taskId(), current.ordinal(),
                current.kind(), result.name(), current.startStageId(), current.startStageOrdinal(),
                current.supplementalPrompt(), current.budgetJson(), bounded(code, 200), bounded(message, 2_000),
                current.authorizedAt(), current.startedAt(), Instant.now().toString(), current.version());
        LifecycleEvent event = switch (result) {
            case SUCCEEDED -> LifecycleEvent.CYCLE_SUCCEED;
            case FAILED -> LifecycleEvent.CYCLE_FAIL;
            case INTERRUPTED -> LifecycleEvent.CYCLE_INTERRUPT;
            case AUDIT_COMPLETED -> LifecycleEvent.CYCLE_AUDIT_COMPLETE;
            case RUNNING -> throw new IllegalArgumentException("Cycle result must be terminal");
        };
        lifecycle.transition(subject(current), current.state(), ended.state(), event,
                null, Map.of("result", result.name(), "failureCode", code == null ? "" : code),
                () -> mapper.updateTaskExecutionCycle(ended),
                () -> new ConflictException("TASK_EXECUTION_CYCLE_VERSION_CONFLICT", "Execution cycle changed concurrently"));
        return mapper.findTaskExecutionCycle(current.id()).orElse(ended);
    }

    public TaskExecutionCycleRow active(String taskId) { return mapper.activeTaskExecutionCycle(taskId).orElse(null); }
    public TaskExecutionCycleRow latest(String taskId) { return mapper.latestTaskExecutionCycle(taskId).orElse(null); }
    public List<TaskExecutionCycleRow> list(String taskId) { return mapper.listTaskExecutionCycles(taskId); }

    private LifecycleTransitionService.Subject subject(TaskExecutionCycleRow row) {
        return new LifecycleTransitionService.Subject(LifecycleMachineType.TASK_EXECUTION_CYCLE, row.id(),
                LifecycleScopeType.TASK, row.taskId());
    }

    private static String bounded(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip();
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }
}
