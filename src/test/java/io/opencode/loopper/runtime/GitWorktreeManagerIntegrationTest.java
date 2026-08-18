package io.opencode.loopper.runtime;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.TaskFailure;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitWorktreeManagerIntegrationTest {
    @TempDir Path temp;

    @Test
    void freezesAndRestoresModifiedDeletedAndUntrackedFilesWithoutMovingTaskBranch() throws Exception {
        Path project = initializedProject("recovery-checkpoint-project");
        Files.writeString(project.resolve("deleted.txt"), "delete me\n");
        run(project, "git", "add", "deleted.txt");
        run(project, "git", "commit", "-m", "tracked delete fixture");
        String baseline = run(project, "git", "rev-parse", "HEAD").strip();
        String branch = run(project, "git", "branch", "--show-current").strip();
        LoopperProperties properties = new LoopperProperties();
        properties.setDataDir(temp.resolve("recovery-checkpoint-data"));
        GitWorktreeManager manager = new GitWorktreeManager(new SafeProcessRunner(), properties, null);
        Files.writeString(project.resolve("README.md"), "modified\n");
        Files.delete(project.resolve("deleted.txt"));
        Files.createDirectories(project.resolve("special dir"));
        Files.writeString(project.resolve("special dir/未跟踪.txt"), "untracked\n");

        GitWorktreeManager.WorkspaceCheckpoint checkpoint =
                manager.freezeWorkspace(project, "task-1", "cycle-1", branch);

        assertThat(run(project, "git", "branch", "--show-current").strip()).isEqualTo(branch);
        assertThat(run(project, "git", "rev-parse", "HEAD").strip()).isEqualTo(baseline);
        assertThat(run(project, "git", "status", "--porcelain")).isBlank();
        assertThat(run(project, "git", "rev-parse", checkpoint.checkpointRef() + "^{tree}").strip())
                .isEqualTo(checkpoint.checkpointTree());

        GitWorktreeManager.WorkspaceCheckpoint resumed =
                manager.freezeWorkspace(project, "task-1", "cycle-1", branch);
        assertThat(resumed.checkpointCommit()).isEqualTo(checkpoint.checkpointCommit());
        assertThat(resumed.checkpointTree()).isEqualTo(checkpoint.checkpointTree());
        assertThat(resumed.workspace().files()).extracting(GitWorktreeManager.DirtyFile::path)
                .containsExactly("README.md", "deleted.txt", "special dir/未跟踪.txt");
        assertThat(manager.workspaceMatchesCheckpointTree(project, branch, checkpoint.checkpointRef(),
                checkpoint.checkpointCommit(), checkpoint.checkpointTree())).isFalse();

        GitWorktreeManager.DirtyWorkspace restored = manager.restoreWorkspaceCheckpoint(project, branch, branch,
                baseline, checkpoint.checkpointRef(), checkpoint.checkpointCommit(), checkpoint.checkpointTree());

        assertThat(manager.workspaceMatchesCheckpointTree(project, branch, checkpoint.checkpointRef(),
                checkpoint.checkpointCommit(), checkpoint.checkpointTree())).isTrue();
        assertThat(restored.files()).extracting(GitWorktreeManager.DirtyFile::path)
                .containsExactly("README.md", "deleted.txt", "special dir/未跟踪.txt");
        assertThat(Files.readString(project.resolve("README.md"))).isEqualTo("modified\n");
        assertThat(Files.exists(project.resolve("deleted.txt"))).isFalse();
        assertThat(Files.readString(project.resolve("special dir/未跟踪.txt"))).isEqualTo("untracked\n");
        assertThat(run(project, "git", "branch", "--show-current").strip()).isEqualTo(branch);
        assertThat(run(project, "git", "rev-parse", "HEAD").strip()).isEqualTo(baseline);

        run(project, "git", "update-ref", checkpoint.checkpointRef(), baseline);
        assertThatThrownBy(() -> manager.workspaceMatchesCheckpointTree(project, branch, checkpoint.checkpointRef(),
                checkpoint.checkpointCommit(), checkpoint.checkpointTree()))
                .isInstanceOfSatisfying(TaskFailure.class, failure ->
                        assertThat(failure.code()).isEqualTo("RECOVERY_CHECKPOINT_INTEGRITY_MISMATCH"));
    }

    @Test
    void switchesRegisteredCheckoutToTaskBranchWithoutRemote() throws Exception {
        Path project = initializedProject("local-source-branch-project");
        String baseline = run(project, "git", "rev-parse", "HEAD").strip();
        LoopperProperties properties = new LoopperProperties();
        properties.setDataDir(temp.resolve("local-source-branch-data"));
        GitWorktreeManager manager = new GitWorktreeManager(new SafeProcessRunner(), properties, null);

        GitWorktreeManager.Worktree task = manager.checkoutSourceBranch(
                project, "task-local", "无远端原项目分支", null);

        assertThat(task.path()).isEqualTo(project.toRealPath());
        assertThat(task.baselineCommit()).isEqualTo(baseline);
        assertThat(task.sourceBranch()).isEqualTo("main");
        assertThat(task.branch()).startsWith("loopper/");
        assertThat(run(project, "git", "branch", "--show-current").strip()).isEqualTo(task.branch());
        Files.writeString(project.resolve("task-only.txt"), "visible in IDEA checkout\n");
        assertThat(run(project, "git", "status", "--porcelain")).contains("?? task-only.txt");
        manager.requireExecutionWorkspace(project, project, task.branch(), baseline);

        run(project, "git", "add", "task-only.txt");
        run(project, "git", "commit", "-m", "task change");
        manager.restoreSourceBranch(project, task.branch(), task.sourceBranch());

        assertThat(run(project, "git", "branch", "--show-current").strip()).isEqualTo("main");
        assertThat(Files.exists(project.resolve("task-only.txt"))).isFalse();
        assertThat(run(project, "git", "show", task.branch() + ":task-only.txt").strip())
                .isEqualTo("visible in IDEA checkout");
    }

    @Test
    void refusesToSwitchRegisteredCheckoutWhenItHasLocalChanges() throws Exception {
        Path project = initializedProject("dirty-source-branch-project");
        Files.writeString(project.resolve("local-only.txt"), "preserve me\n");
        LoopperProperties properties = new LoopperProperties();
        properties.setDataDir(temp.resolve("dirty-source-branch-data"));
        GitWorktreeManager manager = new GitWorktreeManager(new SafeProcessRunner(), properties, null);

        assertThatThrownBy(() -> manager.checkoutSourceBranch(project, "task-dirty", "脏目录任务", null))
                .isInstanceOfSatisfying(TaskFailure.class, failure -> {
                    assertThat(failure.code()).isEqualTo("SOURCE_BRANCH_WORKSPACE_DIRTY");
                    assertThat(failure).hasMessageContaining("uncommitted or untracked files");
                });
        assertThat(run(project, "git", "branch", "--show-current").strip()).isEqualTo("main");
        assertThat(Files.readString(project.resolve("local-only.txt"))).isEqualTo("preserve me\n");
    }

    @Test
    void listsAndAppliesCommitStashAndRemoveForEveryDirtyFile() throws Exception {
        Path project = initializedProject("dirty-resolution-project");
        Files.writeString(project.resolve("commit me.txt"), "original commit\n");
        Files.writeString(project.resolve("remove-me.txt"), "original remove\n");
        run(project, "git", "add", "commit me.txt", "remove-me.txt");
        run(project, "git", "commit", "-m", "tracked fixtures");
        Files.writeString(project.resolve("commit me.txt"), "protected local change\n");
        Files.writeString(project.resolve("stash me.txt"), "stash-only untracked change\n");
        Files.writeString(project.resolve("remove-me.txt"), "discard this change\n");
        LoopperProperties properties = new LoopperProperties();
        properties.setDataDir(temp.resolve("dirty-resolution-data"));
        GitWorktreeManager manager = new GitWorktreeManager(new SafeProcessRunner(), properties, null);

        GitWorktreeManager.DirtyWorkspace snapshot = manager.inspectDirtyWorkspace(project);

        assertThat(snapshot.clean()).isFalse();
        assertThat(snapshot.files()).extracting(GitWorktreeManager.DirtyFile::path)
                .containsExactly("commit me.txt", "remove-me.txt", "stash me.txt");
        GitWorktreeManager.DirtyWorkspace resolved = manager.resolveDirtyWorkspace(project, snapshot.snapshotId(), List.of(
                new GitWorktreeManager.DirtyFileResolution("commit me.txt", GitWorktreeManager.DirtyFileAction.COMMIT),
                new GitWorktreeManager.DirtyFileResolution("remove-me.txt", GitWorktreeManager.DirtyFileAction.REMOVE),
                new GitWorktreeManager.DirtyFileResolution("stash me.txt", GitWorktreeManager.DirtyFileAction.STASH)
        ), "chore: preserve local source changes");

        assertThat(resolved.clean()).isTrue();
        assertThat(run(project, "git", "show", "HEAD:commit me.txt")).isEqualTo("protected local change\n");
        assertThat(Files.readString(project.resolve("remove-me.txt"))).isEqualTo("original remove\n");
        assertThat(Files.exists(project.resolve("stash me.txt"))).isFalse();
        assertThat(run(project, "git", "stash", "show", "--include-untracked", "--name-only", "stash@{0}"))
                .contains("stash me.txt");
    }

    @Test
    void rejectsAResolutionWhenTheDirtySnapshotChanged() throws Exception {
        Path project = initializedProject("dirty-snapshot-project");
        Files.writeString(project.resolve("first.txt"), "first\n");
        LoopperProperties properties = new LoopperProperties();
        properties.setDataDir(temp.resolve("dirty-snapshot-data"));
        GitWorktreeManager manager = new GitWorktreeManager(new SafeProcessRunner(), properties, null);
        GitWorktreeManager.DirtyWorkspace snapshot = manager.inspectDirtyWorkspace(project);
        Files.writeString(project.resolve("second.txt"), "second\n");

        assertThatThrownBy(() -> manager.resolveDirtyWorkspace(project, snapshot.snapshotId(), List.of(
                new GitWorktreeManager.DirtyFileResolution("first.txt", GitWorktreeManager.DirtyFileAction.STASH)
        ), null)).isInstanceOfSatisfying(TaskFailure.class, failure ->
                assertThat(failure.code()).isEqualTo("SOURCE_BRANCH_WORKSPACE_CHANGED"));
        assertThat(Files.exists(project.resolve("first.txt"))).isTrue();
        assertThat(Files.exists(project.resolve("second.txt"))).isTrue();
    }

    @Test
    void refreshesRemoteThenSwitchesRegisteredCheckoutToTaskBranch() throws Exception {
        Path remote = temp.resolve("source-branch-remote.git");
        run(temp, "git", "init", "--bare", "--initial-branch=main", remote.toString());
        Path seed = temp.resolve("source-branch-seed");
        run(temp, "git", "init", "--initial-branch=main", seed.toString());
        configureIdentity(seed);
        writeTextAttributes(seed);
        Files.writeString(seed.resolve("README.md"), "initial\n");
        run(seed, "git", "add", ".");
        run(seed, "git", "commit", "-m", "initial");
        run(seed, "git", "remote", "add", "origin", remote.toString());
        run(seed, "git", "push", "-u", "origin", "main");
        Path project = temp.resolve("source-branch-project");
        Path updater = temp.resolve("source-branch-updater");
        run(temp, "git", "-c", "core.autocrlf=false", "clone", remote.toString(), project.toString());
        run(temp, "git", "-c", "core.autocrlf=false", "clone", remote.toString(), updater.toString());
        configureIdentity(project);
        configureIdentity(updater);
        Files.writeString(updater.resolve("README.md"), "remote advance\n");
        run(updater, "git", "add", "README.md");
        run(updater, "git", "commit", "-m", "remote advance");
        run(updater, "git", "push", "origin", "main");
        String remoteHead = run(remote, "git", "rev-parse", "refs/heads/main").strip();
        LoopperProperties properties = new LoopperProperties();
        properties.setDataDir(temp.resolve("source-branch-data"));
        GitWorktreeManager manager = new GitWorktreeManager(new SafeProcessRunner(), properties, null);

        GitWorktreeManager.Worktree task = manager.checkoutSourceBranch(
                project, "task-remote", "有远端原项目分支", null);

        assertThat(task.path()).isEqualTo(project.toRealPath());
        assertThat(task.baselineCommit()).isEqualTo(remoteHead);
        assertThat(task.sourceBranch()).isEqualTo("main");
        assertThat(run(project, "git", "branch", "--show-current").strip()).isEqualTo(task.branch());
        assertThat(Files.readString(project.resolve("README.md"))).isEqualTo("remote advance\n");
        assertThat(run(remote, "git", "branch", "--list", task.branch())).isBlank();
    }

    @Test
    void fetchesLinearRemoteAdvanceWithoutMovingOrDirtyingTheRegisteredBranch() throws Exception {
        Path remote = temp.resolve("remote.git");
        run(temp, "git", "init", "--bare", "--initial-branch=main", remote.toString());
        Path seed = temp.resolve("seed");
        run(temp, "git", "init", "--initial-branch=main", seed.toString());
        configureIdentity(seed);
        writeTextAttributes(seed);
        Files.writeString(seed.resolve("README.md"), "initial\n");
        run(seed, "git", "add", ".");
        run(seed, "git", "commit", "-m", "initial");
        run(seed, "git", "remote", "add", "origin", remote.toString());
        run(seed, "git", "push", "-u", "origin", "main");

        Path project = temp.resolve("project");
        Path updater = temp.resolve("updater");
        run(temp, "git", "-c", "core.autocrlf=false", "clone", remote.toString(), project.toString());
        run(temp, "git", "-c", "core.autocrlf=false", "clone", remote.toString(), updater.toString());
        configureIdentity(project);
        configureIdentity(updater);
        Files.writeString(updater.resolve("README.md"), "remote advance\n");
        run(updater, "git", "add", "README.md");
        run(updater, "git", "commit", "-m", "remote advance");
        run(updater, "git", "push", "origin", "main");
        String originalHead = run(project, "git", "rev-parse", "HEAD").strip();
        String remoteHead = run(remote, "git", "rev-parse", "refs/heads/main").strip();

        LoopperProperties properties = new LoopperProperties();
        properties.setDataDir(temp.resolve("loopper-data"));
        GitWorktreeManager manager = new GitWorktreeManager(new SafeProcessRunner(), properties, null);

        GitWorktreeManager.Worktree task = manager.create(project, "task-1", "同步远端基线", null);

        assertThat(task.baselineCommit()).isEqualTo(remoteHead);
        assertThat(Files.readString(task.path().resolve("README.md"))).isEqualTo("remote advance\n");
        assertThat(Files.readString(project.resolve("README.md"))).isEqualTo("initial\n");
        assertThat(run(project, "git", "rev-parse", "HEAD").strip()).isEqualTo(originalHead);
        assertThat(run(project, "git", "status", "--porcelain")).isBlank();
        assertThat(runAllowFailure(remote, "git", "show-ref", "--verify", "--quiet", "refs/heads/" + task.branch()))
                .contains("exit=1");

        Files.writeString(task.path().resolve("task-only.txt"), "isolated\n");
        assertThat(Files.exists(project.resolve("task-only.txt"))).isFalse();
        assertThat(run(project, "git", "status", "--porcelain")).isBlank();
    }

    @Test
    void rejectsManagedWorktreeNestedInsideTheRegisteredProject() throws Exception {
        Path project = temp.resolve("overlap-project");
        run(temp, "git", "init", "--initial-branch=main", project.toString());
        configureIdentity(project);
        writeTextAttributes(project);
        Files.writeString(project.resolve("README.md"), "initial\n");
        run(project, "git", "add", ".");
        run(project, "git", "commit", "-m", "initial");
        LoopperProperties properties = new LoopperProperties();
        properties.setDataDir(project.resolve("data"));
        GitWorktreeManager manager = new GitWorktreeManager(new SafeProcessRunner(), properties, null);

        assertThatThrownBy(() -> manager.create(project, "task-overlap", "overlap", null))
                .isInstanceOf(TaskFailure.class)
                .hasMessageContaining("outside the registered project root");
        assertThat(run(project, "git", "status", "--porcelain")).isBlank();
    }

    @Test
    void givesLargeWindowsCheckoutsASeparateBoundedTimeoutAndEnablesLongPaths() throws Exception {
        Path project = initializedProject("slow-checkout-project");
        RecordingRunner runner = new RecordingRunner(new ProcessResult(-1, "Updating files: 28%", true, false));
        LoopperProperties properties = new LoopperProperties();
        properties.setDataDir(temp.resolve("slow-checkout-data"));
        GitWorktreeManager manager = new GitWorktreeManager(runner, properties, null);

        assertThatThrownBy(() -> manager.create(project, "task-slow", "Windows large checkout", null))
                .isInstanceOfSatisfying(TaskFailure.class, failure -> {
                    assertThat(failure.code()).isEqualTo("WORKTREE_CREATE_FAILED");
                    assertThat(failure).hasMessageContaining("10-minute safety limit");
                });
        assertThat(runner.worktreeTimeout).isEqualTo(GitWorktreeManager.WORKTREE_CREATE_TIMEOUT);
        assertThat(runner.worktreeCommand).containsSubsequence("git", "-c", "core.longpaths=true", "worktree", "add", "--quiet");
    }

    @Test
    void reportsTheFatalTailInsteadOfCheckoutProgressNoise() throws Exception {
        Path project = initializedProject("failed-checkout-project");
        String output = "Updating files: 28%\r".repeat(300) + "fatal: cannot create directory: Filename too long";
        RecordingRunner runner = new RecordingRunner(new ProcessResult(128, output, false, false));
        LoopperProperties properties = new LoopperProperties();
        properties.setDataDir(temp.resolve("failed-checkout-data"));
        GitWorktreeManager manager = new GitWorktreeManager(runner, properties, null);

        assertThatThrownBy(() -> manager.create(project, "task-failed", "Windows failed checkout", null))
                .isInstanceOfSatisfying(TaskFailure.class, failure -> {
                    assertThat(failure.code()).isEqualTo("WORKTREE_CREATE_FAILED");
                    assertThat(failure).hasMessageContaining("fatal: cannot create directory: Filename too long");
                });
    }

    private Path initializedProject(String name) throws Exception {
        Path project = temp.resolve(name);
        run(temp, "git", "init", "--initial-branch=main", project.toString());
        configureIdentity(project);
        writeTextAttributes(project);
        Files.writeString(project.resolve("README.md"), "initial\n");
        run(project, "git", "add", ".");
        run(project, "git", "commit", "-m", "initial");
        return project;
    }

    private static final class RecordingRunner extends SafeProcessRunner {
        private final ProcessResult worktreeResult;
        private List<String> worktreeCommand;
        private Duration worktreeTimeout;

        private RecordingRunner(ProcessResult worktreeResult) {
            this.worktreeResult = worktreeResult;
        }

        @Override
        public ProcessResult run(Path directory, List<String> argv, Duration timeout, Map<String, String> environment) {
            if (argv.contains("worktree") && argv.contains("add")) {
                worktreeCommand = List.copyOf(argv);
                worktreeTimeout = timeout;
                return worktreeResult;
            }
            return super.run(directory, argv, timeout, environment);
        }
    }

    private void configureIdentity(Path repository) throws Exception {
        run(repository, "git", "config", "user.email", "test@example.invalid");
        run(repository, "git", "config", "user.name", "test");
        run(repository, "git", "config", "core.autocrlf", "false");
    }

    private void writeTextAttributes(Path repository) throws Exception {
        Files.writeString(repository.resolve(".gitattributes"),
                "*.md text eol=lf\n*.txt text eol=lf\n");
    }

    private String run(Path directory, String... argv) throws Exception {
        return run(directory, false, argv);
    }

    private String runAllowFailure(Path directory, String... argv) throws Exception {
        return run(directory, true, argv);
    }

    private String run(Path directory, boolean allowFailure, String... argv) throws Exception {
        Process process = new ProcessBuilder(argv).directory(directory.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        int exit = process.waitFor();
        if (exit != 0 && !allowFailure) throw new AssertionError(String.join(" ", argv) + "\n" + output);
        return allowFailure ? "exit=" + exit + "\n" + output : output;
    }
}
