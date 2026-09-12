package io.opencode.loopper.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.opencode.loopper.config.LoopperProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DirectWorkspaceCheckpointTest {
    @TempDir Path directory;

    @Test void countsChangesBetweenFrozenTreesWithoutReadingLaterWorkspaceEdits() throws Exception {
        Path root = Files.createDirectory(directory.resolve("project"));
        Files.writeString(root.resolve("README.md"), "initial\n");
        Files.writeString(root.resolve("removed.txt"), "remove me\n");
        var properties = new LoopperProperties();
        properties.setDataDir(directory.resolve("data"));
        var snapshots = new DirectWorkspaceBaselineManager(new SafeProcessRunner(), properties);
        String baseline = snapshots.capture(root, "task");
        var unchanged = snapshots.captureCheckpoint(root, "task");
        assertThat(snapshots.checkpointChangedFileCount(root, baseline, unchanged.tree())).isZero();

        Files.writeString(root.resolve("README.md"), "changed\n");
        Files.delete(root.resolve("removed.txt"));
        Files.writeString(root.resolve("新文件 with spaces.txt"), "added\n");
        var changed = snapshots.captureCheckpoint(root, "task");
        Files.writeString(root.resolve("later.txt"), "not in the checkpoint\n");

        assertThat(snapshots.checkpointChangedFileCount(root, baseline, changed.tree())).isEqualTo(3);
        assertThat(snapshots.checkpointChangedFileCount(root, baseline, unchanged.tree())).isZero();
        assertThat(root.resolve(".git")).doesNotExist();
    }
}
