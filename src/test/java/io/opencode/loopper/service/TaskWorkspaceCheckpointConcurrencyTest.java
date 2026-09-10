package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.runtime.GitWorktreeManager;
import io.opencode.loopper.runtime.SafeProcessRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest(classes = LoopperApplication.class, properties = {
        "loopper.opencode.mode=fake", "loopper.monitor-delay=1h", "loopper.designer-monitor-delay=1h"
})
class TaskWorkspaceCheckpointConcurrencyTest {
    private static final Path DATA = temporaryData();
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("loopper.data-dir", () -> DATA.toString());
        properties.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATA.resolve("loopper.db")
                + "?foreign_keys=on&busy_timeout=5000&journal_mode=WAL&transaction_mode=IMMEDIATE");
    }
    private static Path temporaryData() {
        try { return Files.createTempDirectory("loopper-checkpoint-race-"); }
        catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }
    @Autowired ProjectService projects;
    @Autowired LoopDraftService drafts;
    @Autowired TaskService tasks;
    @Autowired TaskExecutionCycleService cycles;
    @Autowired TaskWorkspaceCheckpointService checkpoints;
    @MockitoSpyBean GitWorktreeManager worktrees;
    @TempDir Path root;

    @Test void concurrentCancellationAndCompletionShareOneCheckpointCapture() throws Exception {
        Files.writeString(root.resolve("README.md"), "initial\n");
        git("init", "--initial-branch=main"); git("add", "README.md");
        git("-c", "user.name=Loopper Test", "-c", "user.email=test@example.invalid", "commit", "-m", "fixture");
        String projectId = projects.create("checkpoint race", root.toString()).id();
        var spec = new LoopSpec("v1", projectId, "Change README", null,
                List.of(new LoopSpec.StageSpec("Change README", null, null, null,
                        List.of(new LoopSpec.VerifierSpec("FILE_EXISTS", null, "README.md", null, null, null, null)))),
                null, null, null, null);
        var task = tasks.start(drafts.confirm(drafts.create(spec).id(), "checkpoint race").id());
        Files.writeString(root.resolve("README.md"), "changed\n");
        var cycle = cycles.active(task.id());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        doAnswer(invocation -> {
            calls.incrementAndGet(); entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(worktrees).freezeWorkspace(any(), any(), any(), any());
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = workers.submit(() -> checkpoints.freeze(task, cycle));
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            var secondEntered = new CountDownLatch(1);
            var second = workers.submit(() -> { secondEntered.countDown(); return checkpoints.freeze(task, cycle); });
            try {
                assertThat(secondEntered.await(3, TimeUnit.SECONDS)).isTrue();
                Thread.sleep(150);
                assertThat(calls).as("same cycle must not run two destructive Git checkpoint sagas").hasValue(1);
            } finally { release.countDown(); }
            var one = first.get(5, TimeUnit.SECONDS);
            var two = second.get(5, TimeUnit.SECONDS);
            assertThat(one.state()).isEqualTo("READY");
            assertThat(two).isEqualTo(one);
            assertThat(checkpoints.latest(task.id())).isEqualTo(one);
        } finally { release.countDown(); }
    }

    private void git(String... arguments) {
        var command = new java.util.ArrayList<String>(); command.add("git"); command.addAll(List.of(arguments));
        var result = new SafeProcessRunner().run(root, command, Duration.ofSeconds(5));
        assertThat(result.exitCode()).as(result.output()).isZero();
    }
}
