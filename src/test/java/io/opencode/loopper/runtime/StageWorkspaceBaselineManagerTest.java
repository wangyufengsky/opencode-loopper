package io.opencode.loopper.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.TaskFailure;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StageWorkspaceBaselineManagerTest {
    @TempDir Path temporaryDirectory;

    @Test
    void rejectsTamperedReferencesAndCleansOnlyOrphanTaskDirectories() throws Exception {
        Path project = Files.createDirectory(temporaryDirectory.resolve("project"));
        Files.writeString(project.resolve("README.md"), "base");
        LoopperProperties properties = properties(temporaryDirectory.resolve("data"));
        StageWorkspaceBaselineManager manager = new StageWorkspaceBaselineManager(new SafeProcessRunner(), properties);
        String live = manager.capture(project, "task-live", "stage-live");
        manager.capture(project, "task-orphan", "stage-orphan");

        assertThat(manager.cleanupOrphans(Set.of("task-live"))).isEqualTo(1);
        assertThat(properties.getDataDir().resolve("stage-baselines/task-live")).isDirectory();
        assertThat(properties.getDataDir().resolve("stage-baselines/task-orphan")).doesNotExist();
        StageWorkspaceBaselineManager restarted = new StageWorkspaceBaselineManager(
                new SafeProcessRunner(), properties);
        restarted.requireAvailable(live);
        assertThat(restarted.diff(project, live, Duration.ofSeconds(5)).tracked().output()).isEmpty();
        assertThatThrownBy(() -> manager.requireAvailable(live.replace("stage-live", "../escaped")))
                .isInstanceOf(TaskFailure.class)
                .satisfies(error -> assertThat(((TaskFailure) error).code())
                        .isEqualTo("STAGE_WORKSPACE_BASELINE_INVALID"));

        Files.delete(properties.getDataDir().resolve("stage-baselines/task-live/indexes/stage-live.index"));
        assertThatThrownBy(() -> manager.requireAvailable(live))
                .isInstanceOf(TaskFailure.class)
                .satisfies(error -> assertThat(((TaskFailure) error).code())
                        .isEqualTo("STAGE_WORKSPACE_BASELINE_UNAVAILABLE"));
    }

    @Test
    void retriesOnceAndFailsWhenWorkspaceChangesAfterEverySnapshot() throws Exception {
        Path project = Files.createDirectory(temporaryDirectory.resolve("unstable-project"));
        Path changing = Files.writeString(project.resolve("changing.txt"), "initial");
        AtomicInteger snapshots = new AtomicInteger();
        SafeProcessRunner mutatingRunner = new SafeProcessRunner() {
            @Override
            public ProcessResult run(Path directory, List<String> argv, Duration timeout,
                                     Map<String, String> environment) {
                ProcessResult result = super.run(directory, argv, timeout, environment);
                if (argv.contains("write-tree")) {
                    try {
                        Files.writeString(changing, "changed-" + snapshots.incrementAndGet());
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                }
                return result;
            }
        };
        StageWorkspaceBaselineManager manager = new StageWorkspaceBaselineManager(
                mutatingRunner, properties(temporaryDirectory.resolve("unstable-data")));

        assertThatThrownBy(() -> manager.capture(project, "task-unstable", "stage-unstable"))
                .isInstanceOf(TaskFailure.class)
                .satisfies(error -> assertThat(((TaskFailure) error).code())
                        .isEqualTo("STAGE_WORKSPACE_BASELINE_UNSTABLE"));
        assertThat(snapshots).hasValue(2);
    }

    @Test
    void observesModificationDeletionRenameAndUntrackedFilesFromTheStageStart() throws Exception {
        Path project = Files.createDirectory(temporaryDirectory.resolve("change-types-project"));
        Files.writeString(project.resolve("modified.txt"), "before");
        Files.writeString(project.resolve("deleted.txt"), "before");
        Files.writeString(project.resolve("rename-source.txt"), "before");
        StageWorkspaceBaselineManager manager = new StageWorkspaceBaselineManager(
                new SafeProcessRunner(), properties(temporaryDirectory.resolve("change-types-data")));
        String marker = manager.capture(project, "task-changes", "stage-changes");

        Files.writeString(project.resolve("modified.txt"), "after");
        Files.delete(project.resolve("deleted.txt"));
        Files.move(project.resolve("rename-source.txt"), project.resolve("rename-target.txt"));
        Files.writeString(project.resolve("new.txt"), "new");

        StageWorkspaceBaselineManager.DiffResult diff = manager.diff(project, marker, Duration.ofSeconds(5));
        assertThat(diff.tracked().output()).contains("modified.txt", "deleted.txt", "rename-source.txt");
        assertThat(diff.untracked().output()).contains("rename-target.txt", "new.txt");
    }

    @Test
    void excludesProjectGitAndTheWholeConfiguredDataDirectory() throws Exception {
        Path project = Files.createDirectory(temporaryDirectory.resolve("excluded-project"));
        Files.writeString(project.resolve("source.txt"), "base");
        Path projectGit = Files.createDirectories(project.resolve(".git"));
        Files.writeString(projectGit.resolve("runtime-state"), "before");
        Path dataDirectory = Files.createDirectories(project.resolve("loopper-data"));
        Files.writeString(dataDirectory.resolve("loopper.db"), "before");
        StageWorkspaceBaselineManager manager = new StageWorkspaceBaselineManager(
                new SafeProcessRunner(), properties(dataDirectory));

        String marker = manager.capture(project, "task-excluded", "stage-excluded");
        Files.writeString(projectGit.resolve("runtime-state"), "after");
        Files.writeString(dataDirectory.resolve("loopper.db"), "after");

        StageWorkspaceBaselineManager.DiffResult diff = manager.diff(project, marker, Duration.ofSeconds(5));
        assertThat(diff.tracked().output()).isEmpty();
        assertThat(diff.untracked().output()).isEmpty();
    }

    private LoopperProperties properties(Path dataDirectory) {
        LoopperProperties properties = new LoopperProperties();
        properties.setDataDir(dataDirectory);
        return properties;
    }
}
