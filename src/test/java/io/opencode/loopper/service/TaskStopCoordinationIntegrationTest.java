package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.domain.SessionFailure;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest(classes = LoopperApplication.class, properties = {
        "loopper.opencode.mode=fake", "loopper.monitor-delay=1h", "loopper.designer-monitor-delay=1h"
})
class TaskStopCoordinationIntegrationTest {
    private static final Path DATA = temporaryData();
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("loopper.data-dir", () -> DATA.toString());
        properties.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATA.resolve("loopper.db")
                + "?foreign_keys=on&busy_timeout=5000&journal_mode=WAL&transaction_mode=IMMEDIATE");
    }
    private static Path temporaryData() {
        try { return Files.createTempDirectory("loopper-stop-race-"); }
        catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }
    @Autowired ProjectService projects;
    @Autowired LoopDraftService drafts;
    @Autowired TaskService tasks;
    @MockitoSpyBean OpenCodeClient openCode;
    @TempDir Path root;

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void userAndMonitorShareAnInFlightTaskCancellation(boolean monitor) throws Exception {
        TaskRow task = tasks.start(pendingTask().id());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var aborts = new AtomicInteger();
        doAnswer(invocation -> {
            aborts.incrementAndGet(); entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return OpenCodeClient.AbortConfirmation.ACKNOWLEDGED;
        }).when(openCode).abortWithConfirmation(any());
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = workers.submit(() -> tasks.cancel(task.id()));
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            var secondEntered = new CountDownLatch(1);
            var second = workers.submit(() -> {
                secondEntered.countDown();
                return monitor ? tasks.continueCancellation(task.id()) : tasks.cancel(task.id());
            });
            try {
                assertThat(secondEntered.await(3, TimeUnit.SECONDS)).isTrue();
                Thread.sleep(150);
                assertThat(aborts).as("one stop proof, not two stale Session updates").hasValue(1);
            } finally { release.countDown(); }
            assertThat(first.get(5, TimeUnit.SECONDS).state()).isEqualTo("CANCELLED");
            assertThat(second.get(5, TimeUnit.SECONDS).state()).isEqualTo("CANCELLED");
            assertThat(tasks.get(task.id()).state()).isEqualTo("CANCELLED");
        } finally { release.countDown(); }
    }

    @Test void aPromptInvalidatedByPauseDoesNotConsumeBusinessFailureRecovery() throws Exception {
        TaskRow task = pendingTask();
        var waiting = new CountDownLatch(1);
        var stopped = new CountDownLatch(1);
        var starting = new AtomicReference<Future<TaskRow>>();
        var aborts = new AtomicInteger();
        doAnswer(invocation -> {
            waiting.countDown();
            assertThat(stopped.await(5, TimeUnit.SECONDS)).isTrue();
            throw new SessionFailure("OPENCODE_PROMPT_CANCELLED", "old prompt invalidated by stop");
        }).when(openCode).promptAsync(any(OpenCodeClient.OpenCodeSession.class), any(OpenCodeClient.PromptRequest.class));
        doAnswer(invocation -> {
            if (aborts.incrementAndGet() == 1) {
                stopped.countDown();
                // Force the invalidated prompt to return while pause still awaits its stop receipt.
                starting.get().get(3, TimeUnit.SECONDS);
            }
            return OpenCodeClient.AbortConfirmation.ACKNOWLEDGED;
        }).when(openCode).abortWithConfirmation(any());
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            starting.set(workers.submit(() -> tasks.start(task.id())));
            assertThat(waiting.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(tasks.pause(task.id()).state()).isEqualTo("PAUSED");
            assertThat(tasks.errors(task.id())).noneMatch(error -> "OPENCODE_PROMPT_CANCELLED".equals(error.code()));
            assertThat(tasks.attempts(task.id())).singleElement().satisfies(attempt ->
                    assertThat(attempt.failureKind()).isEqualTo("PAUSED"));
            assertThat(aborts).hasValue(1);
        } finally { stopped.countDown(); }
    }

    private TaskRow pendingTask() throws Exception {
        Files.writeString(root.resolve("README.md"), "fixture\n");
        String projectId = projects.create("stop race", root.toString()).id();
        var spec = new LoopSpec("v1", projectId, "Change README", null,
                List.of(new LoopSpec.StageSpec("Change README", null, null, null,
                        List.of(new LoopSpec.VerifierSpec("FILE_EXISTS", null, "README.md", null, null, null, null)))),
                null, null, null, null);
        return drafts.confirm(drafts.create(spec).id(), "stop race");
    }
}
