package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.opencode.loopper.domain.TaskFailure;
import io.opencode.loopper.runtime.GitEvidenceProcess;
import io.opencode.loopper.runtime.SafeProcessRunner;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class GitCommitSnapshotsTest {
    @TempDir Path directory;
    private final GitEvidenceProcess git = new GitEvidenceProcess(new SafeProcessRunner());
    private final GitCommitSnapshots snapshots = new GitCommitSnapshots(git);

    @Test void selectedCommitSurvivesBranchMovementAndDoesNotReadOrModifyDirtyIndex() throws Exception {
        Path source = project("source", "sha1");
        String original = read(source, "rev-parse", "HEAD").strip();
        read(source, "branch", "selected");
        Files.writeString(source.resolve("service.txt"), "later committed implementation\n");
        commit(source);
        var selected = snapshots.source(source, branch("selected", null));
        String frozen = snapshots.resolve(selected);
        read(source, "update-ref", "refs/heads/selected", "HEAD");
        Files.writeString(source.resolve("service.txt"), "staged content\n");
        read(source, "add", "service.txt");
        Files.writeString(source.resolve("service.txt"), "unstaged content\n");
        Files.writeString(source.resolve("untracked.txt"), "untracked\n");
        byte[] index = Files.readAllBytes(source.resolve(".git/index"));
        String before = read(source, "status", "--porcelain=v1", "-z");
        Path target = directory.resolve("snapshot.git");

        var result = snapshots.capture(selected, target, frozen);

        assertThat(frozen).isEqualTo(original).isNotEqualTo(snapshots.resolve(selected));
        assertThat(result.commitSha()).isEqualTo(original);
        assertThat(result.files()).extracting(GitSnapshotInventory.Entry::path).containsExactly("service.txt");
        assertThat(read(target, "cat-file", "blob", result.files().getFirst().blobSha())).isEqualTo("original\n");
        assertThat(read(target, "rev-list", "--count", frozen).strip()).isEqualTo("1");
        assertThat(read(source, "symbolic-ref", "HEAD").strip()).isEqualTo("refs/heads/main");
        assertThat(Files.readAllBytes(source.resolve(".git/index"))).containsExactly(index);
        assertThat(read(source, "status", "--porcelain=v1", "-z")).isEqualTo(before);
        assertThat(Files.readString(source.resolve("service.txt"))).isEqualTo("unstaged content\n");
        assertThat(target.resolve("FETCH_HEAD")).doesNotExist();
    }

    @Test void existingObjectsResumeWithoutAccessingUnavailableSourceOrResolvingItsBranch() throws Exception {
        Path source = project("source", "sha1"), target = directory.resolve("snapshot.git");
        var selected = snapshots.source(source, branch("main", null));
        String sha = snapshots.resolve(selected);
        var first = snapshots.capture(selected, target, sha);
        Files.move(source, directory.resolve("source-offline"));

        assertThat(snapshots.capture(selected, target, sha)).isEqualTo(first);
    }

    @Test void registeredSubdirectoryHasRelativeInventoryAndExcludesSiblingProjects() throws Exception {
        Path source = project("source", "sha1"), module = Files.createDirectories(source.resolve("modules/api"));
        Files.writeString(module.resolve("endpoint.txt"), "module implementation\n");
        Files.writeString(source.resolve("modules/sibling.txt"), "another project\n");
        commit(source);
        var selected = snapshots.source(module, branch("main", null));
        String sha = snapshots.resolve(selected);
        var result = snapshots.capture(selected, directory.resolve("snapshot.git"), sha);

        assertThat(result.projectPrefix()).isEqualTo("modules/api/");
        assertThat(result.treeSha()).isEqualTo(read(source, "rev-parse", sha + ":modules/api").strip());
        assertThat(result.files()).extracting(GitSnapshotInventory.Entry::path).containsExactly("endpoint.txt");
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void remoteSelectionUsesRemoteBranchAndPreservesResolvedCommitAfterRemoteAdvances(boolean relative) throws Exception {
        Path source = project("source", "sha1"), remote = directory.resolve("remote.git");
        read(directory, "clone", "--bare", source.toString(), remote.toString());
        String original = read(source, "rev-parse", "HEAD").strip();
        read(source, "remote", "add", "origin", relative ? "../remote.git" : remote.toUri().toString());
        Files.writeString(source.resolve("service.txt"), "local ahead\n"); commit(source);
        var selected = snapshots.source(source, branch("main", "origin"));
        String frozen = snapshots.resolve(selected);
        read(source, "push", "origin", "main");

        var result = snapshots.capture(selected, directory.resolve("snapshot.git"), frozen);

        assertThat(frozen).isEqualTo(original).isNotEqualTo(snapshots.resolve(selected));
        assertThat(result.commitSha()).isEqualTo(original);
        assertThat(read(directory.resolve("snapshot.git"), "cat-file", "blob", result.files().getFirst().blobSha()))
                .isEqualTo("original\n");
    }

    @Test void sha256RepositoryRetainsItsObjectFormat() throws Exception {
        Path source = project("sha256-source", "sha256"), target = directory.resolve("snapshot.git");
        var selected = snapshots.source(source, branch("main", null));
        String sha = snapshots.resolve(selected);

        var result = snapshots.capture(selected, target, sha);

        assertThat(result.commitSha()).hasSize(64).isEqualTo(sha);
        assertThat(result.files().getFirst().blobSha()).hasSize(64);
        assertThat(read(target, "rev-parse", "--show-object-format").strip()).isEqualTo("sha256");
    }

    @Test void refusesSourceGitDirectoriesSymlinksAndExistingWorkingRepositories() throws Exception {
        Path source = project("source", "sha1"), other = project("other", "sha1");
        var selected = snapshots.source(source, branch("main", null));
        String sha = snapshots.resolve(selected), head = read(source, "rev-parse", "HEAD");
        for (Path unsafe : new Path[] {source, source.resolve(".git"), source.resolve(".git/capture.git"), other}) {
            assertPathRejected(selected, unsafe, sha);
        }
        Path parentLink = Files.createSymbolicLink(directory.resolve("redirected"), source);
        assertPathRejected(selected, parentLink.resolve("capture.git"), sha);
        Path targetLink = Files.createSymbolicLink(directory.resolve("capture.git"), other);
        assertPathRejected(selected, targetLink, sha);

        assertThat(read(source, "rev-parse", "HEAD")).isEqualTo(head);
        assertThat(read(source, "status", "--porcelain")).isEmpty();
        assertThat(read(other, "status", "--porcelain")).isEmpty();
    }

    @Test void applicationOwnedDataDirectoryMayRemainInsideRegisteredProject() throws Exception {
        Path source = project("source", "sha1");
        Path data = Files.createDirectories(source.resolve("data/document-templates/run"));
        var selected = snapshots.source(source, branch("main", null));

        var result = snapshots.capture(selected, data.resolve("code.git"), snapshots.resolve(selected));

        assertThat(result.files()).extracting(GitSnapshotInventory.Entry::path).containsExactly("service.txt");
        assertThat(read(source, "symbolic-ref", "HEAD").strip()).isEqualTo("refs/heads/main");
    }

    @Test void linkedWorktreeCannotCaptureIntoTheSharedGitObjectDirectory() throws Exception {
        Path source = project("source", "sha1"), linked = directory.resolve("linked");
        read(source, "worktree", "add", "-b", "linked", linked.toString());
        var selected = snapshots.source(linked, branch("linked", null));
        String sha = snapshots.resolve(selected);
        assertPathRejected(selected, source.resolve(".git/objects/capture.git"), sha);
        assertPathRejected(selected, source.resolve(".git"), sha);
        var result = snapshots.capture(selected, directory.resolve("snapshot.git"), sha);

        assertThat(result.commitSha()).isEqualTo(sha);
        assertThat(source.resolve(".git/objects/capture.git")).doesNotExist();
        assertThat(read(linked, "status", "--porcelain")).isEmpty();
    }

    @Test void transactionGuardRejectsBeforeAnyFilesystemOrProcessOperation() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> snapshots.source(directory.resolve("absent"), branch("main", null)))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("transaction");
            assertThatThrownBy(() -> snapshots.resolve(null)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> snapshots.capture(null, directory.resolve("never-created"), "invalid"))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(directory.resolve("never-created")).doesNotExist();
        } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
    }

    @ParameterizedTest @ValueSource(strings = {"TEMPLATE_GIT_STOP_UNCONFIRMED", "TEMPLATE_GIT_TIMEOUT"})
    void failedTransportCannotReturnASnapshotOrDiscardUnconfirmedStop(String code) throws Exception {
        Path source = project("source", "sha1"), target = directory.resolve("snapshot.git");
        var transport = spy(git); var capture = new GitCommitSnapshots(transport);
        var selected = capture.source(source, branch("main", null));
        String sha = capture.resolve(selected);
        var failure = new TaskFailure(code, "fixture transport failure");
        doThrow(failure).when(transport).remote(eq(target), eq(selected.project()), any(), anyList(), eq(selected.remote()));

        assertThatThrownBy(() -> capture.capture(selected, target, sha)).isSameAs(failure);
        assertThat(target.resolve("refs/heads/frozen")).doesNotExist();
    }

    @Test void diagnosticRepresentationDoesNotExposeRemoteCredentials() {
        var source = new GitCommitReader.Source(directory, directory, directory.resolve(".git"), "",
                "https://example.invalid/fake-test-secret/repository", new GitCommitReader.Selection("refs/heads/main", "origin"));
        assertThat(source.toString()).contains("refs/heads/main").doesNotContain("fake-test-secret", "https://");
    }

    private void assertPathRejected(GitCommitReader.Source source, Path target, String sha) {
        assertThatThrownBy(() -> snapshots.capture(source, target, sha)).isInstanceOfSatisfying(BadRequestException.class,
                error -> assertThat(error.code()).isEqualTo("DOCUMENT_CODE_PATH_INVALID"));
    }
    private Path project(String name, String format) throws Exception {
        Path path = Files.createDirectory(directory.resolve(name));
        read(path, "init", "--initial-branch=main", "--template=", "--object-format=" + format);
        read(path, "config", "user.name", "Snapshot Fixture");
        read(path, "config", "user.email", "fixture@example.invalid");
        Files.writeString(path.resolve("service.txt"), "original\n"); commit(path); return path;
    }
    private void commit(Path path) { read(path, "add", "."); read(path, "commit", "-m", "snapshot fixture"); }
    private String read(Path path, String... arguments) { return git.read(path, arguments); }
    private static ProjectBranchService.Branch branch(String name, String remote) {
        String ref = "refs/heads/" + name;
        return new ProjectBranchService.Branch((remote == null ? "local:" : "remote:" + remote + ":") + ref, name, ref, remote);
    }
}
