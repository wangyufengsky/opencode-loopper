package io.opencode.loopper.runtime;

import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.domain.TaskFailure;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.ProjectRow;
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
    void siblingModulesAndRepositoryRootShareOneFifoLease() throws Exception {
        Path repo = Files.createDirectory(temporaryDirectory.resolve("repo"));
        Files.createDirectory(repo.resolve(".git"));
        Path firstModule = Files.createDirectory(repo.resolve("first"));
        Path secondModule = Files.createDirectory(repo.resolve("second"));
        task("module-first", firstModule); task("module-second", secondModule); task("repo-task", repo);
        assertThat(leases.acquireOrEnqueue(firstModule, "module-first", "MANUAL", null).state()).isEqualTo("ADMITTED");
        assertThat(leases.acquireOrEnqueue(secondModule, "module-second", "MANUAL", null).state()).isEqualTo("QUEUED");
        assertThat(leases.acquireOrEnqueue(repo, "repo-task", "MANUAL", null).state()).isEqualTo("QUEUED");
        assertThat(mapper.findTaskQueue("module-second").orElseThrow().canonicalRoot()).isEqualTo(repo.toRealPath().toString());
        var release = leases.releaseAfterWriterStopped(firstModule, "module-first", "stopped");
        assertThat(release.admittedNext().taskId()).isEqualTo("module-second");
        assertThat(leases.requireWritableLease(secondModule, "module-second").holderTaskId()).isEqualTo("module-second");
    }

    @Test
    void legacyModuleLeaseKeepsItsKeyAndBlocksRepositoryAdmissionUntilStopped() throws Exception {
        Path repo = Files.createDirectory(temporaryDirectory.resolve("legacy-repo"));
        Files.createDirectory(repo.resolve(".git"));
        Path module = Files.createDirectory(repo.resolve("module"));
        task("legacy", module); task("new-task", repo);
        leases.acquireOrEnqueue(DirectWorkspaceLeaseCoordinator.identifyDirectory(module), "legacy", "MANUAL", null);
        assertThat(leases.requireWritableLease(module, "legacy").holderTaskId()).isEqualTo("legacy");
        assertThatThrownBy(() -> leases.acquireOrEnqueue(repo, "new-task", "MANUAL", null))
                .isInstanceOfSatisfying(TaskFailure.class, failure -> assertThat(failure.code()).isEqualTo("WORKSPACE_OVERLAPPING_LEASE"));
        leases.releaseAfterWriterStopped(module, "legacy", "stopped");
        assertThat(leases.acquireOrEnqueue(repo, "new-task", "MANUAL", null).state()).isEqualTo("ADMITTED");
        assertThat(mapper.findTaskQueue("legacy").orElseThrow().canonicalRoot()).isEqualTo(module.toRealPath().toString());
    }

    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void privateReportDirectoryInsideCheckoutDoesNotBlockSourceWriter(boolean reportFirst) throws Exception {
        Path repo = Files.createDirectory(temporaryDirectory.resolve("source"));
        Files.createDirectory(repo.resolve(".git"));
        Path report = Files.createDirectories(repo.resolve("data/template-tasks/report"));
        task("source-writer", repo); task("report", report, "TEMPLATE_REPORT");
        var repoIdentity = DirectWorkspaceLeaseCoordinator.identify(repo);
        var reportIdentity = DirectWorkspaceLeaseCoordinator.identifyDirectory(report);
        assertThat(leases.acquireOrEnqueue(reportFirst ? reportIdentity : repoIdentity,
                reportFirst ? "report" : "source-writer", "MANUAL", null).state()).isEqualTo("ADMITTED");
        assertThat(leases.acquireOrEnqueue(reportFirst ? repoIdentity : reportIdentity,
                reportFirst ? "source-writer" : "report", "MANUAL", null).state()).isEqualTo("ADMITTED");
        assertThat(leases.requireWritableLease(report, "report").holderTaskId()).isEqualTo("report");
        assertThat(leases.requireWritableLease(repo, "source-writer").holderTaskId()).isEqualTo("source-writer");
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
        task(id, root, "LEGACY_AGGREGATE");
    }

    private void task(String id, Path root, String mode) throws Exception {
        String now = Instant.now().toString();
        String canonicalRoot = root.toRealPath().toString();
        String projectId = mapper.findProjectByRoot(canonicalRoot).map(ProjectRow::id).orElseGet(() -> {
            String created = "project-" + id;
            mapper.insertProject(new ProjectRow(created, created, canonicalRoot, "", now, now, 1, 0));
            return created;
        });
        mapper.insertTask(new TaskRow(id, projectId, null, id, "READY", canonicalRoot, "DIRECT", null,
                "direct:test", now, now, 0, null, null, null, mode, null));
    }
}
