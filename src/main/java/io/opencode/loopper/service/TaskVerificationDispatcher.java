package io.opencode.loopper.service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Deduplicates verification work per Task and runs it on a bounded worker pool.
 * A full queue is fail-safe: the Task remains eligible for the next monitor poll.
 */
@Component
class TaskVerificationDispatcher {
    private static final Logger log = LoggerFactory.getLogger(TaskVerificationDispatcher.class);
    private final Executor executor;
    private final TaskService tasks;
    private final Set<String> scheduled = ConcurrentHashMap.newKeySet();

    TaskVerificationDispatcher(@Qualifier("taskVerificationExecutor") Executor executor, TaskService tasks) {
        this.executor = executor;
        this.tasks = tasks;
    }

    void dispatch(String taskId) {
        if (!scheduled.add(taskId)) return;
        try {
            executor.execute(() -> {
                try {
                    tasks.verify(taskId);
                } catch (RuntimeException transitionOrInfrastructureFailure) {
                    log.warn("Verification worker for task {} did not complete", taskId, transitionOrInfrastructureFailure);
                } finally {
                    scheduled.remove(taskId);
                }
            });
        } catch (RejectedExecutionException queueFull) {
            scheduled.remove(taskId);
            log.warn("Verification queue is full; task {} will be retried by the monitor", taskId);
        }
    }
}
