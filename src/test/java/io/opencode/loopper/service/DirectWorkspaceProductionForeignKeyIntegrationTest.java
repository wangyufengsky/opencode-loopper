package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.persistence.TaskRow;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Exercises the production foreign_keys=on URL, which the shared memory test DB cannot clean reliably. */
@SpringBootTest(classes = LoopperApplication.class, properties = {
        "loopper.opencode.mode=fake", "loopper.monitor-delay=1h"
})
class DirectWorkspaceProductionForeignKeyIntegrationTest {
    private static final Path DATA = temporaryDataDirectory();

    @DynamicPropertySource
    static void productionLikeSqlite(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATA.resolve("loopper.db")
                + "?foreign_keys=on&busy_timeout=5000&journal_mode=WAL");
        properties.add("loopper.data-dir", () -> DATA.toString());
    }

    @Autowired private ProjectService projects;
    @Autowired private LoopDraftService drafts;
    @Autowired private TaskService tasks;
    @Autowired private LoopperMapper mapper;
    @MockitoSpyBean private TaskWorkspaceCheckpointService checkpoints;

    @Test
    void directWriterLeaseReferencesTheDurableLocalExecutionSession() throws Exception {
        Path root = Files.createDirectory(DATA.resolve("direct-project"));
        Files.writeString(root.resolve("README.md"), "fixture");
        ProjectRow project = projects.create("foreign-key-direct", root.toString());
        LoopSpec spec = new LoopSpec("v1", project.id(), "Verify writer FK", "", List.of(
                new LoopSpec.StageSpec("run", List.of(), List.of(), List.of("evidence"), List.of(
                        new LoopSpec.VerifierSpec("FILE_EXISTS", null, "README.md", null, null, null, null)))),
                null, null, null, null);
        TaskRow task = drafts.confirm(drafts.create(spec).id(), "foreign key writer");

        tasks.start(task.id());

        var session = mapper.activeSessions(task.id()).getFirst();
        var queue = mapper.findTaskQueue(task.id()).orElseThrow();
        var lease = mapper.findWorkspaceLease(queue.canonicalRoot()).orElseThrow();
        assertThat(lease.writerSessionId()).isEqualTo(session.id());
        assertThat(lease.writerSessionId()).isNotEqualTo(session.externalSessionId());
    }

    @RepeatedTest(5)
    void concurrentManualAndAutomaticReconciliationTransferExactlyOneFifoWaiter() throws Exception {
        Path root = Files.createTempDirectory(DATA, "concurrent-fifo-");
        Files.writeString(root.resolve("README.md"), "fixture");
        run(root, "git", "init", "-b", "main");
        run(root, "git", "config", "user.name", "test");
        run(root, "git", "config", "user.email", "test@example.invalid");
        run(root, "git", "add", "README.md");
        run(root, "git", "commit", "-m", "initial");
        ProjectRow project = projects.create("concurrent-reconcile", root.toString());
        TaskRow holder = drafts.confirm(drafts.create(spec(project.id())).id(), "concurrent holder");
        TaskRow firstWaiter = drafts.confirm(drafts.create(spec(project.id())).id(), "first waiter");
        TaskRow secondWaiter = drafts.confirm(drafts.create(spec(project.id())).id(), "second waiter");
        holder = tasks.start(holder.id());
        tasks.start(firstWaiter.id());
        tasks.start(secondWaiter.id());
        Files.writeString(root.resolve("temporary.txt"), "block initial release\n");
        var blocked = org.mockito.Mockito.mock(io.opencode.loopper.persistence.TaskWorkspaceCheckpointRow.class);
        org.mockito.Mockito.when(blocked.state()).thenReturn("BLOCKED");
        org.mockito.Mockito.when(blocked.blockerCode()).thenReturn("CANCELLATION_CHECKPOINT_UNAVAILABLE");
        org.mockito.Mockito.when(blocked.blockerMessage()).thenReturn("Checkpoint temporarily unavailable");
        String holderId = holder.id();
        org.mockito.Mockito.doReturn(blocked).when(checkpoints).freeze(
                org.mockito.ArgumentMatchers.argThat(task -> task != null && holderId.equals(task.id())),
                org.mockito.ArgumentMatchers.any());
        tasks.cancel(holder.id());
        Files.delete(root.resolve("temporary.txt"));
        run(root, "git", "switch", holder.sourceBranch());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<?> manual = pool.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                tasks.reconcileQueue(firstWaiter.id());
                return null;
            });
            Future<?> automatic = pool.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                tasks.reconcileTerminalWorkspaceLeasesWithWaiters();
                return null;
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            manual.get(20, TimeUnit.SECONDS);
            automatic.get(20, TimeUnit.SECONDS);
        }

        assertThat(mapper.findTaskQueue(holder.id()).orElseThrow().state()).isEqualTo("FINISHED");
        assertThat(mapper.findTaskQueue(firstWaiter.id()).orElseThrow().state()).isEqualTo("ADMITTED");
        assertThat(mapper.findTaskQueue(secondWaiter.id()).orElseThrow().state()).isEqualTo("QUEUED");
        assertThat(tasks.get(firstWaiter.id()).state()).isEqualTo("RUNNING");
        assertThat(mapper.listAttempts(firstWaiter.id())).hasSize(1);
        assertThat(mapper.activeSessions(firstWaiter.id())).hasSize(1);
        assertThat(tasks.get(secondWaiter.id()).state()).isEqualTo("QUEUED");
        assertThat(mapper.eventsAfter(holder.id(), 0).stream()
                .filter(event -> "workspace.lease_released".equals(event.type()))).hasSize(1);
    }

    private LoopSpec spec(String projectId) {
        return new LoopSpec("v1", projectId, "Verify README", null,
                List.of(new LoopSpec.StageSpec("Check README", null, null, null,
                        List.of(new LoopSpec.VerifierSpec("FILE_EXISTS", null, "README.md", null, null, null, null)))),
                null, null, null, null);
    }

    private void run(Path root, String... command) throws Exception {
        var process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(output).isZero();
    }

    private static Path temporaryDataDirectory() {
        try { return Files.createTempDirectory("loopper-production-fk-"); }
        catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    }
}
