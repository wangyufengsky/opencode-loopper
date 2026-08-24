package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.opencode.loopper.domain.ProjectStackProfileState;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectStackAnalyzerTest {
    @TempDir Path root;
    private final ProjectStackAnalyzer analyzer = new ProjectStackAnalyzer();

    @Test void separatesJavaRootAndFrontendNodeIntoStableComponents() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project><artifactId>backend</artifactId></project>");
        Path frontend = Files.createDirectory(root.resolve("frontend"));
        Files.writeString(frontend.resolve("package.json"), "{\"scripts\":{\"test\":\"vitest\"}}");

        ProjectStackAnalyzer.Analysis first = analyzer.analyze(root);
        ProjectStackAnalyzer.Analysis second = analyzer.analyze(root);

        assertThat(first.state()).isEqualTo(ProjectStackProfileState.READY);
        assertThat(first.technologyFamilies()).containsExactly("java", "node");
        assertThat(first.components()).extracting(ProjectStackAnalyzer.ComponentResult::relativeRoot)
                .containsExactly(".", "frontend");
        assertThat(first.components().get(1).testFrameworks()).contains("npm");
        assertThat(second.manifestFingerprint()).isEqualTo(first.manifestFingerprint());
    }

    @Test void onlyMarksMixedWhenFamiliesShareTheSameComponentDirectory() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project />");
        Files.writeString(root.resolve("package.json"), "{}");

        ProjectStackAnalyzer.Analysis result = analyzer.analyze(root);

        assertThat(result.components()).hasSize(1);
        assertThat(result.components().getFirst().technologyFamilies()).containsExactly("java", "node");
    }

    @Test void recognizesPythonGoRustAndSkipsSymbolicLinks() throws Exception {
        Path python = Files.createDirectory(root.resolve("python-tool"));
        Files.writeString(python.resolve("pyproject.toml"), "[tool.pytest.ini_options]\n");
        Files.writeString(root.resolve("go.mod"), "module example.test/service\n");
        Path rust = Files.createDirectory(root.resolve("rust-tool"));
        Files.writeString(rust.resolve("Cargo.toml"), "[package]\nname='tool'\n");
        Path generated = Files.createDirectory(root.resolve("target"));
        Path external = Files.createDirectory(generated.resolve("external"));
        Files.writeString(external.resolve("package.json"), "{}");
        try { Files.createSymbolicLink(root.resolve("linked-node"), external); }
        catch (UnsupportedOperationException ignored) { }

        ProjectStackAnalyzer.Analysis result = analyzer.analyze(root);

        assertThat(result.technologies()).contains("python", "go", "rust").doesNotContain("node");
        assertThat(result.components()).extracting(ProjectStackAnalyzer.ComponentResult::relativeRoot)
                .doesNotContain("linked-node");
    }

    @Test void fileLimitProducesPartialEvidenceInsteadOfGuessing() throws Exception {
        for (int index = 0; index <= ProjectStackAnalyzer.MAX_FILES; index++) {
            Files.writeString(root.resolve("file-" + index + ".txt"), "x");
        }

        ProjectStackAnalyzer.Analysis result = analyzer.analyze(root);

        assertThat(result.state()).isEqualTo(ProjectStackProfileState.PARTIAL);
        assertThat(result.filesScanned()).isEqualTo(ProjectStackAnalyzer.MAX_FILES);
        assertThat(result.evidence()).contains("file-limit-reached=" + ProjectStackAnalyzer.MAX_FILES);
    }

    @Test void missingRootIsPersistableFailureEvidence() {
        ProjectStackAnalyzer.Analysis result = analyzer.analyze(root.resolve("missing"));

        assertThat(result.state()).isEqualTo(ProjectStackProfileState.FAILED);
        assertThat(result.errorCode()).isEqualTo("PROJECT_STACK_ROOT_INVALID");
    }
}
