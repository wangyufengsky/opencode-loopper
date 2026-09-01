package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReviewerReportSourceManifestCaptureTest {
    @TempDir Path root;

    @Test
    void freezesSortedManagedTextFactsAndExcludesGeneratedProtectedAndSymlinkEntries() throws Exception {
        Files.createDirectories(root.resolve("src"));
        Files.createDirectories(root.resolve("target"));
        Files.createDirectories(root.resolve("config"));
        Files.writeString(root.resolve("src/Z.java"), "line one\nline two\n");
        Files.writeString(root.resolve("src/A.java"), "record A() {}\n");
        Files.writeString(root.resolve("target/generated.java"), "class Generated {}\n");
        Files.writeString(root.resolve("config/.env.production"), "TOKEN=secret\n");
        Files.writeString(root.resolve("config/.env.example"), "TOKEN=replace-me\n");
        Files.createSymbolicLink(root.resolve("src/link.java"), root.resolve("src/A.java"));

        var manifest = new ReviewerReportSourceManifestCapture().capture(root);

        assertThat(manifest).extracting(ReviewerReportCompilation.SourceFile::path)
                .containsExactly("config/.env.example", "src/A.java", "src/Z.java");
        assertThat(manifest).allSatisfy(source -> assertThat(source.sha256()).hasSize(64));
        assertThat(manifest.getLast().lineCount()).isEqualTo(2);
    }

    @Test
    void failsClosedBeforeRemoteIoWhenTheFrozenManifestExceedsItsBound() throws Exception {
        Files.writeString(root.resolve("one.txt"), "1\n");
        Files.writeString(root.resolve("two.txt"), "2\n");

        assertThatThrownBy(() -> new ReviewerReportSourceManifestCapture(1, 1024).capture(root))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("source manifest exceeds");
    }

    @Test
    void rejectsSameSizeAndTimestampWhenTheUnderlyingFileIdentityChanged() {
        BasicFileAttributes before = org.mockito.Mockito.mock(BasicFileAttributes.class);
        BasicFileAttributes after = org.mockito.Mockito.mock(BasicFileAttributes.class);
        org.mockito.Mockito.when(before.isRegularFile()).thenReturn(true);
        org.mockito.Mockito.when(after.isRegularFile()).thenReturn(true);
        org.mockito.Mockito.when(before.size()).thenReturn(12L);
        org.mockito.Mockito.when(after.size()).thenReturn(12L);
        org.mockito.Mockito.when(before.lastModifiedTime()).thenReturn(FileTime.fromMillis(7));
        org.mockito.Mockito.when(after.lastModifiedTime()).thenReturn(FileTime.fromMillis(7));
        org.mockito.Mockito.when(before.fileKey()).thenReturn("inode-1");
        org.mockito.Mockito.when(after.fileKey()).thenReturn("inode-2");

        assertThat(ReviewerReportSourceManifestCapture.sameIdentity(before, after)).isFalse();
    }
}
