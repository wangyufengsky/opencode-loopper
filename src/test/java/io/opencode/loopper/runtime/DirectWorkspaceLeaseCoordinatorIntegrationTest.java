package io.opencode.loopper.runtime;

import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.domain.TaskFailure;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.persistence.TaskQueueRow;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.persistence.WorkspaceLeaseRow;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = LoopperApplication.class, properties = {
        "loopper.opencode.mode=fake", "loopper.monitor-delay=1h"
})
class DirectWorkspaceLeaseCoordinatorIntegrationTest {
    @Autowired private Flyway flyway;
    @Autowired private LoopperMapper mapper;
    @Autowired private DirectWorkspaceLeaseCoordinator leases;
    @TempDir Path temporaryDirectory;

    @BeforeEach
    void resetDatabase() {
        flyway.clean();
        flyway.migrate();
    }

    @Test
    void canonicalAliasesShareOnePersistentFifoLeaseAndAdmitOnlyAfterRelease() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("direct-root"));
        Path alias = temporaryDirectory.resolve("direct-root-alias");
        Files.createSymbolicLink(alias, root);
        task("task-one", root);
        task("task-two", root);

        DirectWorkspaceLeaseCoordinator.Admission first = leases.acquireOrEnqueue(root, "task-one", "MANUAL", null);
        DirectWorkspaceLeaseCoordinator.Admission second = leases.acquireOrEnqueue(alias, "task-two", "RECOVERY", null);

        assertThat(first.state()).isEqualTo("ADMITTED");
        assertThat(second.state()).isEqualTo("QUEUED");
        assertThat(second.queuePosition()).isGreaterThan(first.queuePosition());
        assertThat(first.canonicalRoot()).isEqualTo(root.toRealPath().toString());
        assertThat(first.rootFingerprint()).isEqualTo(second.rootFingerprint());

        DirectWorkspaceLeaseCoordinator.Release released = leases.releaseAfterWriterStopped(root, "task-one", "remote terminal confirmed");

        assertThat(mapper.findTaskQueue("task-one").orElseThrow().state()).isEqualTo("FINISHED");
        assertThat(released.admittedNext()).satisfies(next -> {
            assertThat(next.taskId()).isEqualTo("task-two");
            assertThat(next.state()).isEqualTo("ADMITTED");
        });
        assertThat(mapper.findWorkspaceLease(root.toRealPath().toString()).orElseThrow())
                .extracting(WorkspaceLeaseRow::holderTaskId, WorkspaceLeaseRow::state)
                .containsExactly("task-two", "HELD");
    }

    @Test
    void unconfirmedWriterRemainsBlockingUntilItsCallerConfirmsTerminality() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("pending-root"));
        task("holder", root);
        task("waiting", root);
        leases.acquireOrEnqueue(root, "holder", "MANUAL", null);
        leases.acquireOrEnqueue(root, "waiting", "AUTOMATION", null);

        DirectWorkspaceLeaseCoordinator.LeaseSnapshot pending = leases.markWriterUnconfirmed(root, "holder", null, "abort status unavailable");

        assertThat(pending.state()).isEqualTo("RELEASE_PENDING");
        assertThat(leases.blockingLeases()).anySatisfy(lease -> {
            assertThat(lease.holderTaskId()).isEqualTo("holder");
            assertThat(lease.state()).isEqualTo("RELEASE_PENDING");
            assertThat(lease.rootAvailable()).isTrue();
            assertThat(lease.fingerprintMatches()).isTrue();
        });
        assertThat(mapper.findTaskQueue("waiting").orElseThrow().state()).isEqualTo("QUEUED");

        leases.releaseAfterWriterStopped(root, "holder", "later status read observed ABORTED");

        assertThat(mapper.findTaskQueue("waiting").orElseThrow().state()).isEqualTo("ADMITTED");
    }

    @Test
    void queuedCancellationNeverReleasesTheCurrentWriter() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("cancel-root"));
        task("holder", root);
        task("cancelled", root);
        leases.acquireOrEnqueue(root, "holder", "MANUAL", null);
        leases.acquireOrEnqueue(root, "cancelled", "MANUAL", null);

        DirectWorkspaceLeaseCoordinator.QueueSnapshot cancelled = leases.cancelQueued("cancelled");

        assertThat(cancelled.state()).isEqualTo("CANCELLED");
        assertThat(mapper.findWorkspaceLease(root.toRealPath().toString()).orElseThrow().holderTaskId()).isEqualTo("holder");
        assertThatThrownBy(() -> leases.cancelQueued("holder"))
                .isInstanceOf(TaskFailure.class)
                .hasMessageContaining("must confirm writer termination");
    }

    @Test
    void aPersistedFingerprintMismatchFailsClosedBeforeAnotherTaskCanQueue() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("replaced-root"));
        task("holder", root);
        task("contender", root);
        DirectWorkspaceLeaseCoordinator.WorkspaceIdentity identity = DirectWorkspaceLeaseCoordinator.identify(root);
        String now = Instant.now().toString();
        mapper.insertWorkspaceLease(new WorkspaceLeaseRow(identity.canonicalRoot(), "not-the-real-fingerprint", "DIRECT",
                "holder", null, "HELD", now, now, null, null, 0));

        assertThatThrownBy(() -> leases.acquireOrEnqueue(root, "contender", "MANUAL", null))
                .isInstanceOf(TaskFailure.class)
                .hasMessageContaining("identity changed");
        assertThat(mapper.findTaskQueue("contender")).isEmpty();
    }

    @Test
    void deletingAndRecreatingTheSameCanonicalPathChangesItsStableFileIdentityFingerprint() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("same-path-new-directory"));
        DirectWorkspaceLeaseCoordinator.WorkspaceIdentity before = DirectWorkspaceLeaseCoordinator.identify(root);
        Files.delete(root);
        Files.createDirectory(root);

        DirectWorkspaceLeaseCoordinator.WorkspaceIdentity after = DirectWorkspaceLeaseCoordinator.identify(root);
        if (after.rootFingerprint().equals(before.rootFingerprint())) {
            // NTFS can tunnel a recently deleted directory's creation time and immediately
            // reuse the file key for the same name. Force distinct replacement metadata so
            // this test verifies Loopper's documented metadata fingerprint, not NTFS timing.
            Files.getFileAttributeView(root, java.nio.file.attribute.BasicFileAttributeView.class)
                    .setTimes(null, null, java.nio.file.attribute.FileTime.from(Instant.now().plusSeconds(1)));
            after = DirectWorkspaceLeaseCoordinator.identify(root);
        }

        assertThat(after.canonicalRoot()).isEqualTo(before.canonicalRoot());
        assertThat(after.rootFingerprint()).isNotEqualTo(before.rootFingerprint());
    }

    @Test
    void aReleasedLeaseRefreshesItsFingerprintBeforeAReplacementRootIsAdmitted() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("released-replacement"));
        task("finished", root);
        DirectWorkspaceLeaseCoordinator.Admission first = leases.acquireOrEnqueue(root, "finished", "MANUAL", null);
        leases.releaseAfterWriterStopped(root, "finished", "writer stopped");

        Files.delete(root);
        Files.createDirectory(root);
        task("replacement", root);

        DirectWorkspaceLeaseCoordinator.Admission replacement = leases.acquireOrEnqueue(root, "replacement", "MANUAL", null);

        assertThat(replacement.state()).isEqualTo("ADMITTED");
        assertThat(replacement.rootFingerprint()).isNotEqualTo(first.rootFingerprint());
        assertThat(mapper.findWorkspaceLease(root.toRealPath().toString())).get()
                .extracting(WorkspaceLeaseRow::rootFingerprint)
                .isEqualTo(replacement.rootFingerprint());
    }

    private void task(String id, Path root) throws Exception {
        String now = Instant.now().toString();
        String canonicalRoot = root.toRealPath().toString();
        String projectId = mapper.findProjectByRoot(canonicalRoot).map(ProjectRow::id).orElseGet(() -> {
            String created = "project-" + id;
            mapper.insertProject(new ProjectRow(created, created, canonicalRoot, "", now, now, 1, 0));
            return created;
        });
        mapper.insertTask(new TaskRow(id, projectId, null, id, "READY", canonicalRoot, "DIRECT", "direct:test", now, now, 0));
    }
}
