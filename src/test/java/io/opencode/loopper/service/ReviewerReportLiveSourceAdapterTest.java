package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReviewerReportLiveSourceAdapterTest {
    @TempDir
    Path root;

    @Test
    void capturesOnlyManagedRegularSourcesAndKeepsThePublicEnvironmentExample() throws Exception {
        Files.createDirectories(root.resolve("src"));
        Files.createDirectories(root.resolve("config"));
        Files.createDirectories(root.resolve("data"));
        Files.writeString(root.resolve("src/Main.java"), "class Main {}\n");
        Files.writeString(root.resolve("config/.env.example"), "TOKEN=replace-me\n");
        Files.writeString(root.resolve("config/.env.production"), "TOKEN=secret\n");
        Files.writeString(root.resolve("data/runtime.db"), "not source\n");

        List<ReviewerReportCompilation.SourceFile> sources = new ReviewerReportLiveSourceAdapter().capture(root,
                List.of(finding("src/Main.java"), finding("config/.env.example"),
                        finding("config/.env.production"), finding("data/runtime.db"), finding("missing.txt")));

        assertThat(sources).extracting(ReviewerReportCompilation.SourceFile::path)
                .containsExactly("src/Main.java", "config/.env.example");
        assertThat(sources).allSatisfy(source -> {
            assertThat(source.lineCount()).isEqualTo(1);
            assertThat(source.sha256()).hasSize(64);
        });
    }

    private static ReviewerReportCompilation.Finding finding(String path) {
        return new ReviewerReportCompilation.Finding("INFO", "Finding", "Detail", path, 1,
                "Recommendation");
    }
}
