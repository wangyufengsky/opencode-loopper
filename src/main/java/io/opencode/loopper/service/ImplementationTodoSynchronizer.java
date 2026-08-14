package io.opencode.loopper.service;

import io.opencode.loopper.domain.TodoCapability;
import io.opencode.loopper.persistence.ExecutionSessionRow;
import io.opencode.loopper.persistence.SessionTodoRow;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Service;

/** Serializes manual and scheduled Todo reads while keeping provider I/O outside SQLite transactions. */
@Service
public class ImplementationTodoSynchronizer {
    private static final int LOCK_STRIPES = 64;
    private final ReentrantLock[] locks = new ReentrantLock[LOCK_STRIPES];
    private final OpenCodeClient openCode;
    private final SessionLifecyclePersistence persistence;

    public ImplementationTodoSynchronizer(OpenCodeClient openCode, SessionLifecyclePersistence persistence) {
        this.openCode = openCode;
        this.persistence = persistence;
        for (int index = 0; index < locks.length; index++) locks[index] = new ReentrantLock();
    }

    public SyncResult synchronize(TaskRow task, ExecutionSessionRow session) {
        return synchronize(task, session, true);
    }

    public SyncResult synchronizeIfAvailable(TaskRow task, ExecutionSessionRow session) {
        return synchronize(task, session, false);
    }

    private SyncResult synchronize(TaskRow task, ExecutionSessionRow session, boolean explicit) {
        if (!explicit && !TodoCapability.AVAILABLE.name().equals(session.todoCapability())) {
            return new SyncResult(persistence.todos(session.id()), false, null, false);
        }
        if (session.externalSessionId() == null || session.externalSessionId().isBlank()
                || task.worktreePath() == null || task.worktreePath().isBlank()) {
            return new SyncResult(persistence.todos(session.id()), false, "OpenCode Todo remote is unavailable", false);
        }
        ReentrantLock lock = locks[Math.floorMod(session.id().hashCode(), locks.length)];
        lock.lock();
        try {
            OpenCodeClient.SessionTodoSnapshot observed = openCode.sessionTodoSnapshot(
                    new OpenCodeClient.OpenCodeSession(session.externalSessionId(), Path.of(task.worktreePath())));
            SessionLifecyclePersistence.TodoPersistenceResult result = persistence.replaceTodos(task.id(), session.id(),
                    observed.todos(), Instant.now().toString());
            return new SyncResult(result.todos(), observed.truncated(), observed.detail(), result.changed());
        } finally {
            lock.unlock();
        }
    }

    public record SyncResult(List<SessionTodoRow> todos, boolean truncated, String detail, boolean changed) {
        public SyncResult { todos = todos == null ? List.of() : List.copyOf(todos); }
    }
}
