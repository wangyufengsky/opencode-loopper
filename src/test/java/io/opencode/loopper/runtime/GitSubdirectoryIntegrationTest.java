package io.opencode.loopper.runtime;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.TaskFailure;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class GitSubdirectoryIntegrationTest {
    @TempDir Path temp;
    private final SafeProcessRunner runner = new SafeProcessRunner();

    @Test void recognizesHttpRemoteAndKeepsExecutionInsideTheRegisteredModule() throws Exception {
        Path module = project();
        run(module, "remote", "add", "origin", "http://gitlab.invalid/group/project.git");
        assertThat(manager().inspect(module).isolatedWorktree()).isTrue();
        run(module, "remote", "remove", "origin");
        var task = manager().checkoutSourceBranch(module, "task-1", "子模块任务", null);
        assertThat(task.path()).isEqualTo(module.toRealPath());
        assertThat(run(module, "branch", "--show-current")).isEqualTo(task.branch());
        assertThat(manager().inspect(module.getParent().getParent()).branch()).isEqualTo(task.branch());
        manager().requireExecutionWorkspace(task.path(), module, task.branch(), task.baselineCommit());
        manager().restoreSourceBranch(module, task.branch(), task.sourceBranch());
        assertThat(run(module, "branch", "--show-current")).isEqualTo("main");
    }

    @Test void dirtyDialogUsesModuleRelativePathsAndSelectedCommitPreservesSiblings() throws Exception {
        Path module = project();
        Files.writeString(module.resolve("file.txt"), "changed\n");
        var dirty = manager().inspectDirtyWorkspace(module);
        assertThat(dirty.files()).extracting(GitWorktreeManager.DirtyFile::path).containsExactly("file.txt");
        var clean = manager().resolveDirtyWorkspace(module, dirty.snapshotId(),
                List.of(new GitWorktreeManager.DirtyFileResolution("file.txt", GitWorktreeManager.DirtyFileAction.COMMIT)),
                "module change");
        assertThat(clean.clean()).isTrue();
        assertThat(run(module, "show", "HEAD:other/file.txt")).isEqualTo("sibling");
    }

    @Test void siblingDirtyFilesBlockSwitchAndAreNeverOfferedAsModuleCleanup() throws Exception {
        Path module = project();
        Files.writeString(module.getParent().getParent().resolve("other/file.txt"), "preserve sibling\n");
        assertThatThrownBy(() -> manager().checkoutSourceBranch(module, "task-2", "blocked", null))
                .isInstanceOfSatisfying(TaskFailure.class, failure -> assertThat(failure.code()).isEqualTo("GIT_PROJECT_OUTSIDE_CHANGES"));
        assertThat(run(module, "branch", "--show-current")).isEqualTo("main");
        assertThat(Files.readString(module.getParent().getParent().resolve("other/file.txt"))).isEqualTo("preserve sibling\n");
    }

    @Test void checkpointRoundTripAndReadOnlySnapshotStayScopedToModule() throws Exception {
        Path module = project();
        var manager = manager();
        var task = manager.checkoutSourceBranch(module, "task-3", "checkpoint", null);
        Files.writeString(module.resolve("file.txt"), "module checkpoint\n");
        Files.writeString(module.resolve("new.txt"), "new module file\n");
        var checkpoint = manager.freezeWorkspace(module, "task-3", "cycle-1", task.branch());
        assertThat(checkpoint.workspace().files()).extracting(GitWorktreeManager.DirtyFile::path)
                .containsExactlyInAnyOrder("file.txt", "new.txt");
        assertThat(run(module, "status", "--porcelain")).isEmpty();
        var resumed = manager.freezeWorkspace(module, "task-3", "cycle-1", task.branch());
        assertThat(resumed.workspace().files()).extracting(GitWorktreeManager.DirtyFile::path)
                .containsExactlyInAnyOrder("file.txt", "new.txt");
        Path snapshot = manager.materializeReadOnlySnapshot(module, "task-3", "checkpoint-1", checkpoint.checkpointTree());
        assertThat(Files.readString(snapshot.resolve("file.txt"))).isEqualTo("module checkpoint\n");
        assertThat(Files.exists(snapshot.resolve("other"))).isFalse();
        assertThat(manager.materializeReadOnlySnapshot(module, "task-3", "checkpoint-1", checkpoint.checkpointTree())).isEqualTo(snapshot);
        manager.restoreSourceBranch(module, task.branch(), task.sourceBranch());
        manager.restoreWorkspaceCheckpoint(module, task.branch(), task.sourceBranch(), task.baselineCommit(),
                checkpoint.checkpointRef(), checkpoint.checkpointCommit(), checkpoint.checkpointTree());
        assertThat(manager.workspaceMatchesCheckpointTree(module, task.branch(), checkpoint.checkpointRef(),
                checkpoint.checkpointCommit(), checkpoint.checkpointTree())).isTrue();
        assertThat(Files.readString(module.resolve("new.txt"))).isEqualTo("new module file\n");
        assertThat(Files.readString(module.getParent().getParent().resolve("other/file.txt"))).isEqualTo("sibling\n");
    }

    @Test void siblingChangeCannotBeCapturedOrRestoredByModuleCheckpoint() throws Exception {
        Path module = project();
        var manager = manager();
        var task = manager.checkoutSourceBranch(module, "task-4", "checkpoint guard", null);
        Files.writeString(module.getParent().getParent().resolve("other/file.txt"), "outside\n");
        assertThatThrownBy(() -> manager.freezeWorkspace(module, "task-4", "cycle-1", task.branch()))
                .isInstanceOfSatisfying(TaskFailure.class, failure -> assertThat(failure.code()).isEqualTo("GIT_PROJECT_OUTSIDE_CHANGES"));
        assertThat(Files.readString(module.getParent().getParent().resolve("other/file.txt"))).isEqualTo("outside\n");
        assertThat(run(module, "stash", "list")).isEmpty();
    }

    @Test void stagedSiblingChangeCannotHideBehindAnUnchangedWorkingFile() throws Exception {
        Path module = project();
        Path sibling = module.getParent().getParent().resolve("other/file.txt");
        Files.writeString(sibling, "staged sibling\n");
        run(sibling.getParent(), "add", "file.txt");
        Files.writeString(sibling, "sibling\n");
        assertThat(run(module, "diff", "HEAD", "--name-only")).isEmpty();
        assertThatThrownBy(() -> GitProjectScope.require(runner, module).requireContainedChanges(runner, "HEAD", null))
                .isInstanceOfSatisfying(TaskFailure.class, failure -> assertThat(failure.code()).isEqualTo("GIT_PROJECT_OUTSIDE_CHANGES"));
        assertThat(run(module, "show", ":other/file.txt")).isEqualTo("staged sibling");
    }

    private GitWorktreeManager manager() {
        LoopperProperties properties = new LoopperProperties();
        properties.setDataDir(temp.resolve("data"));
        return new GitWorktreeManager(runner, properties, null);
    }

    private Path project() throws Exception {
        Path repo = Files.createDirectory(temp.resolve("repo"));
        run(repo, "init", "-b", "main");
        run(repo, "config", "user.name", "Fixture");
        run(repo, "config", "user.email", "fixture@example.invalid");
        run(repo, "config", "core.autocrlf", "false");
        run(repo, "config", "core.safecrlf", "false");
        Path module = Files.createDirectories(repo.resolve("module space/中文"));
        Files.createDirectory(repo.resolve("other"));
        Files.writeString(module.resolve("file.txt"), "original\n");
        Files.writeString(repo.resolve("other/file.txt"), "sibling\n");
        run(repo, "add", "."); run(repo, "commit", "-m", "initial");
        Path gitRoot = Path.of(new GitEvidenceProcess(runner).read(module, "rev-parse", "--show-toplevel").strip());
        assertThat(Files.isSameFile(gitRoot, repo)).as("Git reports the fixture repository root").isTrue();
        return module;
    }

    private String run(Path directory, String... args) {
        var command = new java.util.ArrayList<>(List.of("git")); command.addAll(List.of(args));
        var result = runner.run(directory, command, Duration.ofSeconds(10));
        assertThat(result.exitCode()).describedAs(result.output()).isZero();
        return result.output().strip();
    }
}
