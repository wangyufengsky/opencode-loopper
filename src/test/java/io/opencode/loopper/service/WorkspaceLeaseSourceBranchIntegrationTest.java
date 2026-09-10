package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.domain.RecoveryMode;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.persistence.TaskWorkspaceCheckpointRow;
import io.opencode.loopper.runtime.SafeProcessRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
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
class WorkspaceLeaseSourceBranchIntegrationTest {
    private static final Path DATA = temporaryData();
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("loopper.data-dir", () -> DATA.toString());
        properties.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATA.resolve("loopper.db")
                + "?foreign_keys=on&busy_timeout=5000&journal_mode=WAL&transaction_mode=IMMEDIATE");
    }
    private static Path temporaryData() {
        try { return Files.createTempDirectory("loopper-source-release-"); }
        catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }
    @Autowired ProjectService projects;
    @Autowired LoopDraftService drafts;
    @Autowired TaskService tasks;
    @Autowired RecoveryService recoveries;
    @Autowired LoopperMapper mapper;
    @Autowired WorkspaceLeaseReconciliationService reconciliation;
    @MockitoSpyBean TaskWorkspaceCheckpointService checkpoints;
    @TempDir Path root;

    @ParameterizedTest @ValueSource(strings = {"MANUAL", "AUTO", "RESTART"})
    void cancelledHolderAlreadyOnItsRecordedSourceCanRelease(String trigger) throws Exception {
        Fixture fixture = blockedCancellation();
        git("switch", "develop");
        if (trigger.equals("MANUAL")) {
            assertThat(tasks.reconcileQueue(fixture.waiter().id()).state()).isEqualTo("ADMITTED");
            assertThat(tasks.get(fixture.waiter().id()).state()).isEqualTo("RUNNING");
            assertThat(tasks.get(fixture.waiter().id()).sourceBranch()).isEqualTo("main");
        } else if (trigger.equals("AUTO")) {
            tasks.reconcileTerminalWorkspaceLeasesWithWaiters();
            assertThat(tasks.get(fixture.waiter().id()).state()).isEqualTo("RUNNING");
        } else {
            var result = reconciliation.reconcileHolder(fixture.holder().id(), trigger, "test-restart");
            assertThat(result.released()).as(result.blockerMessage()).isTrue();
            assertThat(result.admittedNext().taskId()).isEqualTo(fixture.waiter().id());
            assertThat(git("branch", "--show-current")).isEqualTo("main");
        }
        assertThat(mapper.findTaskQueue(fixture.holder().id()).orElseThrow().state()).isEqualTo("FINISHED");
        assertThat(mapper.findWorkspaceLease(root.toRealPath().toString()).orElseThrow().holderTaskId())
                .isEqualTo(fixture.waiter().id());
        assertThat(reconciliation.reconcileHolder(fixture.holder().id(), trigger, "repeat").alreadySettled()).isTrue();
    }

    @Test void unrelatedBranchRemainsBlockedWithoutMovingIt() throws Exception {
        Fixture fixture = blockedCancellation();
        git("switch", "-c", "another-user-branch");
        assertThatThrownBy(() -> tasks.reconcileQueue(fixture.waiter().id()))
                .isInstanceOfSatisfying(ConflictException.class, failure ->
                        assertThat(failure.code()).isEqualTo("TASK_SOURCE_BRANCH_RESTORE_MISMATCH"));
        assertThat(git("branch", "--show-current")).isEqualTo("another-user-branch");
        assertThat(tasks.get(fixture.waiter().id()).state()).isEqualTo("QUEUED");
    }

    @Test void cancelledReworkOnItsRecordedSourceCanRelease() throws Exception {
        Fixture fixture = blockedCancellation(true);
        git("switch", "develop");
        assertThat(tasks.reconcileQueue(fixture.waiter().id()).state()).isEqualTo("ADMITTED");
        assertThat(tasks.get(fixture.waiter().id()).state()).isEqualTo("RUNNING");
        assertThat(mapper.findTaskQueue(fixture.holder().id()).orElseThrow().state()).isEqualTo("FINISHED");
    }

    @Test void dirtyRecordedSourceRemainsBlockedAndKeepsUserFiles() throws Exception {
        Fixture fixture = blockedCancellation();
        git("switch", "develop");
        Files.writeString(root.resolve("user-work.txt"), "keep this work\n");
        assertThatThrownBy(() -> tasks.reconcileQueue(fixture.waiter().id())).isInstanceOf(ConflictException.class);
        assertThat(git("branch", "--show-current")).isEqualTo("develop");
        assertThat(Files.readString(root.resolve("user-work.txt"))).isEqualTo("keep this work\n");
        assertThat(tasks.get(fixture.waiter().id()).state()).isEqualTo("QUEUED");
    }

    private Fixture blockedCancellation() throws Exception {
        return blockedCancellation(false);
    }

    private Fixture blockedCancellation(boolean rework) throws Exception {
        Files.writeString(root.resolve("README.md"), "fixture\n");
        git("init", "--initial-branch=main"); git("add", ".");
        git("-c", "user.name=Loopper Test", "-c", "user.email=test@example.invalid", "commit", "-m", "fixture");
        git("switch", "-c", "develop");
        String projectId = projects.create("develop release", root.toString()).id();
        TaskRow original = tasks.start(pendingTask(projectId, "old holder").id());
        if (rework) {
            tasks.cancel(original.id());
            git("switch", "develop");
        }
        TaskRow holder = rework
                ? tasks.start(recoveries.create(original.id(), RecoveryMode.REWORK_ALL_STAGES).taskId()) : original;
        assertThat(holder.sourceBranch()).isEqualTo("develop");
        TaskRow waiter = tasks.start(pendingTask(projectId, "waiting task").id());
        assertThat(waiter.state()).isEqualTo("QUEUED");
        Files.writeString(root.resolve("block.txt"), "fixture holds first release\n");
        var blocked = mock(TaskWorkspaceCheckpointRow.class);
        when(blocked.state()).thenReturn("BLOCKED");
        when(blocked.blockerCode()).thenReturn("CANCELLATION_CHECKPOINT_UNAVAILABLE");
        when(blocked.blockerMessage()).thenReturn("fixture preservation unavailable");
        doReturn(blocked).when(checkpoints).freeze(argThat(task -> task.id().equals(holder.id())), any());
        assertThat(tasks.cancel(holder.id()).state()).isEqualTo("CANCELLED");
        Files.delete(root.resolve("block.txt"));
        return new Fixture(holder, waiter);
    }

    private TaskRow pendingTask(String projectId, String title) {
        var spec = new LoopSpec("v1", projectId, "Verify README", null,
                List.of(new LoopSpec.StageSpec("Verify README", null, null, null,
                        List.of(new LoopSpec.VerifierSpec("FILE_EXISTS", null, "README.md", null, null, null, null)))),
                null, null, null, null);
        return drafts.confirm(drafts.create(spec).id(), title);
    }
    private String git(String... arguments) {
        var command = new java.util.ArrayList<String>(); command.add("git"); command.addAll(List.of(arguments));
        var result = new SafeProcessRunner().run(root, command, Duration.ofSeconds(5));
        assertThat(result.exitCode()).as(result.output()).isZero();
        return result.output().strip();
    }
    private record Fixture(TaskRow holder, TaskRow waiter) { }
}
