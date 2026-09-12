package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.runtime.GitEvidenceProcess;
import io.opencode.loopper.runtime.SafeProcessRunner;
import io.opencode.loopper.template.TemplateDateRange;
import io.opencode.loopper.template.TemplateGitEvidence;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class TemplateGitEvidenceIntegrationTest {
    @TempDir Path temporary;
    private Path root;
    private final GitEvidenceProcess git = new GitEvidenceProcess(new SafeProcessRunner());
    private final SafeProcessRunner runner = new SafeProcessRunner();
    private final ProjectService projects = mock(ProjectService.class);
    private TemplateGitSnapshotService snapshots;

    @BeforeEach void setUp() throws Exception {
        root = Files.createDirectory(temporary.resolve("source"));
        git.read(root, "init", "-b", "main");
        git.read(root, "config", "user.name", "Alice");
        git.read(root, "config", "user.email", "alice@example.test");
        var project = mock(ProjectRow.class);
        when(project.rootPath()).thenReturn(root.toString());
        when(projects.get("project")).thenReturn(project);
        var properties = new LoopperProperties();
        properties.setDataDir(temporary.resolve("data"));
        snapshots = new TemplateGitSnapshotService(projects, git, properties);
    }

    @Test void freezesSelectedBranchWithoutChangingDirtyCheckoutAndUsesExactCommitTime() throws Exception {
        commit("before.txt", "before", "2026-09-10T15:59:59Z", "before");
        String first = commit("inside.txt", "inside", "2026-09-10T16:00:00Z", "inside");
        String last = commit("last.txt", "last", "2026-09-11T15:59:59Z", "last");
        commit("outside.txt", "outside", "2026-09-11T16:00:00Z", "outside");
        git.read(root, "switch", "-c", "feature");
        commit("unmerged.txt", "unmerged", "2026-09-11T02:00:00Z", "unmerged");
        Files.writeString(root.resolve("inside.txt"), "dirty");
        Files.writeString(root.resolve("new file.txt"), "untracked");
        String status = git.read(root, "status", "--porcelain=v1", "-z");
        String refs = git.read(root, "show-ref");
        var snapshot = snapshots.freeze("run-1", "project", main());
        var evidence = collect(snapshot);
        assertThat(evidence.commits()).extracting(TemplateGitEvidence.Commit::sha).containsExactly(first, last);
        assertThat(git.read(root, "status", "--porcelain=v1", "-z")).isEqualTo(status);
        assertThat(git.read(root, "show-ref")).isEqualTo(refs);
        assertThat(git.read(root, "branch", "--show-current").strip()).isEqualTo("feature");
        assertThat(Files.readString(root.resolve("inside.txt"))).isEqualTo("dirty");
        assertThat(evidence.commits().getFirst().changes().getFirst().patch()).contains("+inside");
        // Moving the original branch later cannot change a persisted run snapshot.
        git.read(root, "branch", "-f", "main", "feature");
        assertThat(snapshots.freeze("run-1", "project", main()).head()).isEqualTo(snapshot.head());
    }

    @Test void freezesMailmapAndSharesCoauthorIdentityWithoutCombiningEqualNames() throws Exception {
        commit(".mailmap", "Alice Canonical <alice@canonical.test> Alice <alice@example.test>\n", "2026-09-10T01:00:00Z", "map");
        commit("file.txt", "hello\n", "2026-09-11T01:00:00Z", "work\n\nCo-authored-by: Alice <other@example.test>\n");
        var evidence = collect(snapshots.freeze("authors", "project", main()));
        assertThat(evidence.mailmapHash()).hasSize(40);
        assertThat(evidence.commits().getFirst().contributors()).extracting(TemplateGitEvidence.Contributor::email)
                .containsExactly("alice@canonical.test", "other@example.test");
        assertThat(evidence.commits().getFirst().contributors()).extracting(TemplateGitEvidence.Contributor::identity).doesNotHaveDuplicates();
    }

    @Test void excludesDeclaredGeneratedCodeButPreservesIndentationChangesAndUnusualPaths() throws Exception {
        assertGeneratedCodeAndIndentationEvidence("space file.py");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void preservesTabDelimitedFileNamesOnSupportedFilesystems() throws Exception {
        assertGeneratedCodeAndIndentationEvidence("space\tfile.py");
    }

    private void assertGeneratedCodeAndIndentationEvidence(String fileName) throws Exception {
        commit(".gitattributes", "generated.txt linguist-generated=true\n", "2026-09-10T01:00:00Z", "attributes");
        commit("generated.txt", "generated\n", "2026-09-11T01:00:00Z", "generated");
        commit(fileName, "if True:\n  pass\n", "2026-09-11T02:00:00Z", "python");
        commit(fileName, "if True:\n    pass\n", "2026-09-11T03:00:00Z", "indentation");
        var evidence = collect(snapshots.freeze("generated", "project", main()));
        var generated = evidence.commits().getFirst().changes().getFirst();
        assertThat(generated.additions()).isEqualTo(1);
        assertThat(generated.effectiveLines()).isZero();
        assertThat(generated.exclusionReason()).isEqualTo("DECLARED_LINGUIST_GENERATED");
        assertThat(evidence.commits().getLast().changes().getFirst().effectiveLines()).isEqualTo(2);
        assertThat(evidence.commits().getLast().changes().getFirst().path()).isEqualTo(fileName);
    }

    @Test void branchDiscoveryDefaultsToMainNotCurrentBranchAndSeparatesRemoteSource() throws Exception {
        commit("file", "content", "2026-09-11T01:00:00Z", "root");
        git.read(root, "switch", "-c", "feature");
        var branches = new ProjectBranchService(projects, git);
        assertThat(branches.list("project", "", null, 50).defaultBranchId()).isEqualTo(main().id());
        Path remote = temporary.resolve("remote.git");
        git.read(temporary, "clone", "--bare", "--", root.toString(), remote.toString());
        git.read(remote, "symbolic-ref", "HEAD", "refs/heads/main");
        git.read(root, "remote", "add", "origin", remote.toString());
        var page = branches.list("project", "main", null, 1);
        assertThat(page.defaultBranchId()).isEqualTo("remote:origin:refs/heads/main");
        assertThat(page.page().items()).hasSize(1);
        var next = branches.list("project", "main", page.page().nextCursor(), 1);
        assertThat(next.page().items().getFirst().remote()).isEqualTo("origin");
        var selected = branches.require("project", page.defaultBranchId());
        var snapshot = snapshots.freeze("remote", "project", selected);
        assertThat(snapshot.head()).isEqualTo(git.read(remote, "rev-parse", "refs/heads/main").strip());
        assertThatThrownBy(() -> branches.require("project", "--upload-pack=bad")).isInstanceOf(BadRequestException.class);
    }

    @Test void cleanMergeDoesNotCountIncomingChangesAgain() throws Exception {
        commit("base", "base", "2026-09-10T01:00:00Z", "base");
        git.read(root, "switch", "-c", "feature");
        commit("feature", "feature", "2026-09-11T01:00:00Z", "feature");
        git.read(root, "switch", "main");
        commit("main", "main", "2026-09-11T02:00:00Z", "main");
        commandAt("2026-09-11T03:00:00Z", List.of("git", "merge", "--no-ff", "feature", "-m", "merge"));
        var evidence = collect(snapshots.freeze("merge", "project", main()));
        assertThat(evidence.commits()).hasSize(3);
        assertThat(evidence.commits().getLast().disposition()).isEqualTo("MERGE_RESOLUTION");
        assertThat(evidence.commits().getLast().changes()).isEmpty();
    }

    @Test void duplicatePatchesCountOnceAndSecretFilesNeverReachModelEvidence() throws Exception {
        commit("file", "one\n", "2026-09-11T01:00:00Z", "first");
        commit("file", "two\n", "2026-09-11T02:00:00Z", "second");
        commit("file", "one\n", "2026-09-11T03:00:00Z", "undo");
        commit("file", "two\n", "2026-09-11T04:00:00Z", "repeat");
        commit(".env", "SECRET=fixture-only\n", "2026-09-11T05:00:00Z", "config");
        var evidence = collect(snapshots.freeze("duplicates", "project", main()));
        assertThat(evidence.commits().get(3).changes().getFirst().exclusionReason()).isEqualTo("DUPLICATE_PATCH");
        assertThat(evidence.commits().get(3).changes().getFirst().effectiveLines()).isZero();
        assertThat(evidence.commits().getLast().changes().getFirst().exclusionReason()).isEqualTo("SENSITIVE_CONTENT_WITHHELD");
        assertThat(evidence.commits().getLast().changes().getFirst().patch()).isEmpty();
    }

    @Test void mergeResolutionUsesSyntheticConflictBlobAsBeforeEvidence() throws Exception {
        commit("file", "base\n", "2026-09-10T01:00:00Z", "base");
        git.read(root, "switch", "-c", "feature");
        commit("file", "feature\n", "2026-09-11T01:00:00Z", "feature");
        git.read(root, "switch", "main");
        commit("file", "main\n", "2026-09-11T02:00:00Z", "main");
        String parentBlob = git.read(root, "rev-parse", "HEAD:file").strip();
        assertThat(git.run(root, Duration.ofSeconds(30), List.of("merge", "--no-ff", "feature", "-m", "merge")).exitCode()).isNotZero();
        commit("file", "resolved\n", "2026-09-11T03:00:00Z", "resolve");
        var change = collect(snapshots.freeze("resolved", "project", main())).commits().getLast().changes().getFirst();
        assertThat(change.beforeBlob()).hasSize(40).isNotEqualTo(parentBlob);
        assertThat(change.patch()).contains("<<<<<<<", "+resolved");
        assertThat(change.afterBlob()).isEqualTo(git.read(root, "rev-parse", "HEAD:file").strip());
    }

    @Test void paginationAndNonMonotonicCommitClocksDoNotLoseReachableCommits() throws Exception {
        commit("file", "base", "2026-09-11T00:00:00Z", "first");
        for (int i = 1; i < 105; i++) {
            String date = java.time.Instant.parse("2026-09-11T00:00:00Z").plusSeconds(i).toString();
            commandAt(date, List.of("git", "commit", "--allow-empty", "-m", "empty-" + i));
        }
        commandAt("2026-09-09T00:00:00Z", List.of("git", "commit", "--allow-empty", "-m", "old-clock-tip"));
        var evidence = collect(snapshots.freeze("many", "project", main()));
        assertThat(evidence.commits()).hasSize(105);
        assertThat(evidence.commits()).extracting(TemplateGitEvidence.Commit::sha).doesNotHaveDuplicates();
    }

    @Test void attributesComeFromEachCommitInsteadOfTheTipOrDirtySourceIndex() throws Exception {
        commit(".gitattributes", "file.txt linguist-generated=true\n", "2026-09-10T01:00:00Z", "generated declaration");
        commit("file.txt", "generated\n", "2026-09-11T01:00:00Z", "generated work");
        commit(".gitattributes", "file.txt -linguist-generated\n", "2026-09-11T02:00:00Z", "hand maintained");
        commit("file.txt", "maintained\n", "2026-09-11T03:00:00Z", "manual work");
        Files.writeString(root.resolve(".gitattributes"), "file.txt linguist-generated=true\n");
        git.read(root, "add", ".gitattributes");
        String sourceIndex = TemplateGitEvidenceCollector.hash(java.util.HexFormat.of().formatHex(Files.readAllBytes(root.resolve(".git/index"))));
        var evidence = collect(snapshots.freeze("attributes-history", "project", main()));
        assertThat(evidence.commits().getFirst().changes().getFirst().effectiveLines()).isZero();
        assertThat(evidence.commits().getLast().changes().getFirst().effectiveLines()).isEqualTo(2);
        assertThat(TemplateGitEvidenceCollector.hash(java.util.HexFormat.of().formatHex(Files.readAllBytes(root.resolve(".git/index"))))).isEqualTo(sourceIndex);
    }

    @Test void recursiveBaselineCombinesDisjointEditsWithoutCreditingThemToMerger() throws Exception {
        commit("file", "one\ntwo\nthree\nfour\nfive\n", "2026-09-10T01:00:00Z", "base");
        git.read(root, "switch", "-c", "feature");
        commit("file", "FEATURE\ntwo\nthree\nfour\nfive\n", "2026-09-11T01:00:00Z", "feature");
        git.read(root, "switch", "main");
        commit("file", "one\ntwo\nthree\nfour\nMAIN\n", "2026-09-11T02:00:00Z", "main");
        commandAt("2026-09-11T03:00:00Z", List.of("git", "merge", "--no-ff", "feature", "-m", "merge"));
        var snapshot = snapshots.freeze("disjoint-merge", "project", main());
        assertThat(collect(snapshot).commits().getLast().changes()).isEmpty();
        try (var files = Files.list(snapshot.directory())) {
            assertThat(files.map(path -> path.getFileName().toString()).toList()).noneMatch(name -> name.startsWith("merge-baseline-"));
        }
    }

    @Test void deletedAndBinaryFilesStayVisibleOnTheCompatibilityPath() throws Exception {
        commit("deleted.txt", "to delete\n", "2026-09-10T01:00:00Z", "before");
        Files.delete(root.resolve("deleted.txt"));
        Files.write(root.resolve("binary.dat"), new byte[] {0, 1, 2, 3});
        git.read(root, "add", "-A");
        commandAt("2026-09-11T01:00:00Z", List.of("git", "commit", "-m", "delete and binary"));
        var changes = collect(snapshots.freeze("delete-binary", "project", main())).commits().getFirst().changes();
        assertThat(changes).anySatisfy(change -> {
            assertThat(change.path()).isEqualTo("deleted.txt");
            assertThat(change.beforeBlob()).hasSize(40);
            assertThat(change.afterBlob()).isNull();
            assertThat(change.effectiveLines()).isEqualTo(1);
        }).anySatisfy(change -> {
            assertThat(change.path()).isEqualTo("binary.dat");
            assertThat(change.binary()).isTrue();
            assertThat(change.exclusionReason()).isEqualTo("BINARY_NO_LINE_METRIC");
        });
    }

    @Test void rejectsShallowRemoteAndShallowRecoveredSnapshot() throws Exception {
        commit("file", "before", "2026-09-10T01:00:00Z", "before");
        commit("file", "after", "2026-09-11T01:00:00Z", "after");
        Path remote = temporary.resolve("shallow.git");
        git.read(temporary, "clone", "--bare", "--depth=1", "--", root.toUri().toString(), remote.toString());
        git.read(root, "remote", "add", "origin", remote.toString());
        var branch = new ProjectBranchService.Branch("remote:origin:refs/heads/main", "main", "refs/heads/main", "origin");
        assertThatThrownBy(() -> snapshots.freeze("shallow-remote", "project", branch))
                .isInstanceOf(io.opencode.loopper.domain.TaskFailure.class);
        Path recovered = snapshots.prepareDirectory("shallow-recovered").resolve("repository.git");
        git.read(temporary, "clone", "--bare", "--depth=1", "--", root.toUri().toString(), recovered.toString());
        git.read(recovered, "update-ref", "refs/heads/snapshot", "HEAD");
        assertThatThrownBy(() -> snapshots.freeze("shallow-recovered", "project", main()))
                .isInstanceOf(io.opencode.loopper.domain.TaskFailure.class).hasMessageContaining("历史不完整");
    }

    private TemplateGitEvidence collect(TemplateGitSnapshotService.Snapshot snapshot) {
        return new TemplateGitEvidenceCollector(git).collect(snapshot, main().id(),
                TemplateDateRange.parse("2026-09-11", "2026-09-11", Clock.systemUTC()));
    }
    private ProjectBranchService.Branch main() { return new ProjectBranchService.Branch("local:refs/heads/main", "main（本地）", "refs/heads/main", null); }
    private String commit(String file, String content, String date, String message) throws Exception {
        Files.writeString(root.resolve(file), content);
        git.read(root, "add", "--", file);
        commandAt(date, List.of("git", "-c", "core.hooksPath=/dev/null", "commit", "-m", message));
        return git.read(root, "rev-parse", "HEAD").strip();
    }
    private void commandAt(String date, List<String> argv) {
        var result = runner.run(root, argv, Duration.ofSeconds(10), Map.of("GIT_AUTHOR_DATE", date, "GIT_COMMITTER_DATE", date));
        assertThat(result.exitCode()).as(result.output()).isZero();
    }
}
