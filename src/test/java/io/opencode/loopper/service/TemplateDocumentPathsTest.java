package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemplateDocumentPathsTest {
    @TempDir Path directory;
    private final TemplateDocumentPaths paths = new TemplateDocumentPaths();

    @Test void resolvesRelativeAndAbsoluteDirectoriesWithoutCreatingThem() throws Exception {
        Path root = directory.toRealPath();
        assertThat(paths.resolve(root.toString(), " docs/周报 ")).isEqualTo(root.resolve("docs/周报").toString());
        assertThat(paths.resolve(root.toString(), root.resolve("external reports").toString())).isEqualTo(root.resolve("external reports").toString());
        assertThat(paths.resolve(root.toString(), " ")).isNull();
        assertThat(root.resolve("docs")).doesNotExist();
    }

    @Test void rejectsTraversalSensitiveDirectoriesFilesAndSymlinkAncestors() throws Exception {
        Path root = directory.toRealPath();
        Files.writeString(root.resolve("occupied"), "existing");
        Path other = Files.createDirectory(root.resolve("other"));
        Files.createDirectories(other.resolve("existing"));
        Files.createSymbolicLink(root.resolve("link"), other);
        for (String value : new String[]{"../outside", ".git/reports", ".ssh/reports", "occupied", "link/existing/new", "bad\npath"}) {
            assertThatThrownBy(() -> paths.resolve(root.toString(), value)).isInstanceOf(BadRequestException.class);
        }
        assertThat(Files.readString(root.resolve("occupied"))).isEqualTo("existing");
    }

    @Test void writeGuardDetectsDirectoryReplacedAfterConfirmation() throws Exception {
        Path root = directory.toRealPath();
        Path target = Path.of(paths.resolve(root.toString(), "docs/new"));
        Path other = Files.createDirectory(root.resolve("other"));
        Files.createSymbolicLink(root.resolve("docs"), other);
        assertThatThrownBy(() -> TemplateDocumentPaths.requireSafeDirectory(target))
                .isInstanceOf(io.opencode.loopper.domain.TaskFailure.class).hasMessageContaining("符号链接");
        assertThat(other.resolve("new")).doesNotExist();
    }
}
