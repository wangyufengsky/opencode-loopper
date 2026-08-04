package io.opencode.loopper.service;

import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.ExecutionSessionRow;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.runtime.OpenCodeClient;
import io.opencode.loopper.domain.SessionFailure;
import java.nio.file.Path;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bridges OpenCode session state into the persisted task state machine. */
@Component
class TaskMonitor {
    private final LoopperMapper mapper;
    private final OpenCodeClient openCode;
    private final TaskService tasks;
    private final TaskVerificationDispatcher verification;
    TaskMonitor(LoopperMapper mapper, OpenCodeClient openCode, TaskService tasks, TaskVerificationDispatcher verification) {
        this.mapper = mapper; this.openCode = openCode; this.tasks = tasks; this.verification = verification;
    }
    @Scheduled(fixedDelayString = "${loopper.monitor-delay:2s}")
    void poll() {
        // Task terminality and remote writer terminality are separate facts. A
        // failed/cancelled Task never starts another Attempt, but an unconfirmed
        // abort keeps a persisted, bounded cleanup obligation across restarts.
        for (ExecutionSessionRow session : mapper.sessionsPendingAbortCleanup()) {
            try { tasks.retrySessionCleanup(session.id()); }
            catch (RuntimeException ignoredConcurrentCleanup) {
                // Another poll/operator transition may have resolved the same row.
            }
        }
        for (TaskRow task : mapper.listTasks()) {
            if ("JUDGING".equals(task.state())) {
                try { tasks.pollJudges(task.id()); }
                catch (RuntimeException ignoredConcurrentTransition) {
                    // A cancel or explicit operator transition can legitimately win the state race.
                }
                continue;
            }
            if (!"RUNNING".equals(task.state()) || task.worktreePath() == null) continue;
            tasks.enforceTimeouts(task.id());
            if (!"RUNNING".equals(tasks.get(task.id()).state())) continue;
            for (ExecutionSessionRow session : mapper.activeSessions(task.id())) {
                if (session.externalSessionId() == null) continue;
                try {
                    OpenCodeClient.SessionStatus status = openCode.sessionStatus(new OpenCodeClient.OpenCodeSession(session.externalSessionId(), Path.of(task.worktreePath())));
                    if (status.failed()) tasks.sessionFailed(task.id(), session.attemptId(), "OPENCODE_SESSION_" + status.state(),
                            status.detail() == null || status.detail().isBlank() ? "OpenCode session ended in " + status.state() : status.detail());
                    else if (status.completed()) verification.dispatch(task.id());
                } catch (SessionFailure transportFailure) {
                    tasks.sessionFailed(task.id(), session.attemptId(), "OPENCODE_STATUS_FAILED", transportFailure.getMessage());
                } catch (RuntimeException ignoredConcurrentTransition) {
                    // A user/API transition may have won the optimistic-lock race; do not reinterpret it as a session failure.
                }
            }
        }
    }
}
