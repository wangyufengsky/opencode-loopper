package io.opencode.loopper.service;

import io.opencode.loopper.domain.AttemptState;
import io.opencode.loopper.domain.SessionState;
import io.opencode.loopper.domain.TaskState;
import io.opencode.loopper.domain.LifecycleMachineType;
import io.opencode.loopper.domain.LifecycleScopeType;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.AttemptRow;
import io.opencode.loopper.persistence.ExecutionSessionRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.SessionCheckpointRow;
import io.opencode.loopper.persistence.SessionTodoRow;
import io.opencode.loopper.persistence.StageRow;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Keeps provider I/O outside SQLite transactions. WAL readers cannot safely be
 * promoted to writers after another connection commits, even with busy_timeout;
 * each observed provider snapshot is therefore applied in one short transaction.
 */
@Service
public class SessionLifecyclePersistence {
    private final LoopperMapper mapper;
    private final LifecycleTransitionService lifecycle;
    private final TaskEventService events;
    private final ObjectMapper json;

    public SessionLifecyclePersistence(LoopperMapper mapper, LifecycleTransitionService lifecycle,
                                       TaskEventService events, ObjectMapper json) {
        this.mapper = mapper;
        this.lifecycle = lifecycle;
        this.events = events;
        this.json = json;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<SessionTodoRow> replaceTodos(String taskId, String sessionId,
                                             List<OpenCodeClient.SessionTodo> observed,
                                             String observedAt) {
        Map<String, SessionTodoRow> persisted = new LinkedHashMap<>();
        for (SessionTodoRow row : mapper.listSessionTodos(sessionId)) persisted.put(row.externalTodoId(), row);
        List<String> activeTodoIds = new ArrayList<>();
        for (OpenCodeClient.SessionTodo todo : observed) {
            if (todo.id() == null || todo.id().isBlank()) continue;
            activeTodoIds.add(todo.id());
            SessionTodoRow old = persisted.get(todo.id());
            mapper.upsertSessionTodo(new SessionTodoRow(old == null ? UUID.randomUUID().toString() : old.id(), sessionId,
                    todo.id(), blank(todo.content()), blank(todo.status()), nullable(todo.priority()), todo.ordinal(),
                    write(todo.metadata()), observedAt, old == null ? 0 : old.version()));
        }
        mapper.deleteMissingSessionTodos(sessionId, write(activeTodoIds));
        List<SessionTodoRow> snapshot = mapper.listSessionTodos(sessionId);
        events.emit(taskId, "session.todos_refreshed", Map.of("sessionId", sessionId, "count", snapshot.size()));
        return snapshot;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SessionCheckpointRow insertCheckpoint(SessionCheckpointRow row) {
        mapper.insertSessionCheckpoint(row);
        events.emit(row.taskId(), "session.checkpoint_created", Map.of("sessionId", row.executionSessionId(),
                "checkpointId", row.id(), "contentSha256", row.contentSha256()));
        return row;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ForkSnapshot insertForkSnapshot(String taskId, String parentSessionId, String childExternalSessionId) {
        var task = mapper.findTask(taskId).orElseThrow(() -> new NotFoundException("Task not found: " + taskId));
        if (!TaskState.PAUSED.name().equals(task.state())) {
            throw new ConflictException("TASK_NOT_PAUSED", "Fork 持久化前 Task 已不再处于 PAUSED 状态");
        }
        for (ExecutionSessionRow row : mapper.listSessions(taskId)) {
            if (SessionState.CREATING.name().equals(row.state()) || SessionState.RUNNING.name().equals(row.state())
                    || SessionState.DISCONNECTED.name().equals(row.state())) {
                throw new ConflictException("SESSION_WRITER_UNCONFIRMED", "Fork 持久化前出现未确认终止的写入 Session");
            }
        }
        ExecutionSessionRow parent = mapper.findSession(parentSessionId)
                .orElseThrow(() -> new NotFoundException("Execution session not found: " + parentSessionId));
        StageRow stage = mapper.findStage(parent.stageId())
                .orElseThrow(() -> new ConflictException("SESSION_STAGE_MISSING", "Session stage no longer exists"));
        int ordinal = mapper.countAttemptsForStage(stage.id()) + 1;
        String now = Instant.now().toString();
        AttemptRow attempt = new AttemptRow(UUID.randomUUID().toString(), taskId, stage.id(), ordinal,
                AttemptState.SUCCEEDED.name(), "SESSION_FORK_SNAPSHOT", "OpenCode fork transcript snapshot", now, now, 0);
        lifecycle.create(subject(LifecycleMachineType.ATTEMPT, attempt.id(), taskId), attempt.state(), Map.of("source", "fork"),
                () -> mapper.insertAttempt(attempt),
                () -> new ConflictException("ATTEMPT_CREATE_CONFLICT", "Fork snapshot attempt could not be created"));
        ExecutionSessionRow snapshot = new ExecutionSessionRow(UUID.randomUUID().toString(), taskId, stage.id(), attempt.id(),
                childExternalSessionId, SessionState.COMPLETED.name(), now, now, 0);
        lifecycle.create(subject(LifecycleMachineType.EXECUTION_SESSION, snapshot.id(), taskId), snapshot.state(),
                Map.of("source", "fork"), () -> mapper.insertSession(snapshot),
                () -> new ConflictException("SESSION_CREATE_CONFLICT", "Fork snapshot session could not be created"));
        events.emit(taskId, "session.forked", Map.of("parentSessionId", parentSessionId, "sessionId", snapshot.id(),
                "attemptId", attempt.id(), "externalSessionId", childExternalSessionId));
        return new ForkSnapshot(snapshot, attempt);
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JacksonException failure) { throw new IllegalStateException("Unable to serialize session snapshot", failure); }
    }
    private static String blank(String value) { return value == null ? "" : value; }
    private static String nullable(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    public record ForkSnapshot(ExecutionSessionRow session, AttemptRow attempt) { }

    private LifecycleTransitionService.Subject subject(LifecycleMachineType machine, String entityId, String taskId) {
        return new LifecycleTransitionService.Subject(machine, entityId, LifecycleScopeType.TASK, taskId);
    }
}
