package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opencode.loopper.domain.ProjectStackProfileState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectConventionEvidenceCatalogCaptureTest {
    @TempDir Path root;

    @Test
    void capturesOnlySnapshotFactsAndMechanicallyProvenCommandsInStableOrder() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project><build>junit surefire</build></project>");
        Path wrapper = Files.writeString(root.resolve("mvnw"), "#!/bin/sh\n");
        assertThat(wrapper.toFile().setExecutable(true)).isTrue();
        Path frontend = Files.createDirectory(root.resolve("frontend"));
        Files.writeString(frontend.resolve("package.json"), "{\"scripts\":{\"test\":\"vitest\"}}");
        Files.writeString(frontend.resolve("package-lock.json"), "{\"lockfileVersion\":3}");
        ProjectStackSnapshot snapshot = reversedSnapshot(new ProjectStackAnalyzer().analyze(root));

        ProjectConventionCompilation.EvidenceCatalog catalog =
                new ProjectConventionEvidenceCatalogCapture().capture(root.toRealPath(), snapshot);

        assertThat(catalog.stackFingerprint()).isEqualTo(snapshot.manifestFingerprint());
        assertThat(new ProjectConventionEvidenceCatalogCapture().capture(root.toRealPath(), snapshot))
                .isEqualTo(catalog);
        assertThat(catalog.components()).extracting(ProjectConventionCompilation.ComponentEvidence::relativeRoot)
                .containsExactly(".", "frontend");
        ProjectConventionCompilation.ComponentEvidence java = catalog.components().getFirst();
        ProjectConventionCompilation.ComponentEvidence node = catalog.components().getLast();
        assertThat(catalog.commands()).containsExactly(
                new ProjectConventionCompilation.CommandEvidence(java.key() + ":maven:build", java.key(),
                        List.of("./mvnw", "package")),
                new ProjectConventionCompilation.CommandEvidence(java.key() + ":maven:test", java.key(),
                        List.of("./mvnw", "test")),
                new ProjectConventionCompilation.CommandEvidence(node.key() + ":npm:install", node.key(),
                        List.of("npm", "ci")),
                new ProjectConventionCompilation.CommandEvidence(node.key() + ":npm:test", node.key(),
                        List.of("npm", "test")));
        assertThat(catalog.paths()).containsExactly(
                new ProjectConventionCompilation.PathEvidence(java.key() + ":root", java.key(), ".",
                        ProjectConventionCompilation.PathKind.COMPONENT_ROOT),
                new ProjectConventionCompilation.PathEvidence(java.key() + ":manifest:pom.xml", java.key(),
                        "pom.xml", ProjectConventionCompilation.PathKind.MANIFEST),
                new ProjectConventionCompilation.PathEvidence(node.key() + ":root", node.key(), "frontend",
                        ProjectConventionCompilation.PathKind.COMPONENT_ROOT),
                new ProjectConventionCompilation.PathEvidence(node.key() + ":manifest:frontend/package.json",
                        node.key(), "frontend/package.json", ProjectConventionCompilation.PathKind.MANIFEST));
    }

    @Test
    void failsClosedOnMalformedSnapshotPathsBeforeSortingOrFilesystemAccess() throws Exception {
        ProjectStackSnapshot malformed = new ProjectStackSnapshot("stack-1", "project-1",
                ProjectStackProfileState.READY, "a".repeat(64), List.of("java"), List.of("java"),
                List.of(), 1, null, null, "2026-09-02T00:00:00Z", List.of(
                new ProjectStackSnapshot.Component("java-root", null, List.of("java"), List.of("java"),
                        List.of("maven"), List.of("junit"), List.of("pom.xml"), List.of())));

        assertThatThrownBy(() -> new ProjectConventionEvidenceCatalogCapture()
                .capture(root.toRealPath(), malformed))
                .isInstanceOfSatisfying(ConflictException.class, failure -> assertThat(failure.code())
                        .isEqualTo("PROJECT_CONVENTION_EVIDENCE_CAPTURE_INVALID"));
    }

    @Test
    void rejectsMoreThanSixtyFourFrozenComponentsBeforeReadingTheirPaths() throws Exception {
        List<ProjectStackSnapshot.Component> components = new ArrayList<>();
        for (int index = 0; index < 65; index++) {
            components.add(new ProjectStackSnapshot.Component("component-" + index, "module-" + index,
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));
        }
        ProjectStackSnapshot oversized = new ProjectStackSnapshot("stack-1", "project-1",
                ProjectStackProfileState.READY, "a".repeat(64), List.of(), List.of(), List.of(),
                65, null, null, "2026-09-02T00:00:00Z", components);

        assertThatThrownBy(() -> new ProjectConventionEvidenceCatalogCapture()
                .capture(root.toRealPath(), oversized))
                .isInstanceOfSatisfying(ConflictException.class, failure -> assertThat(failure.code())
                        .isEqualTo("PROJECT_CONVENTION_EVIDENCE_CAPTURE_TOO_LARGE"));
    }

    @Test
    void rejectsMoreThanSixtyFourMechanicallyDerivedCommands() throws Exception {
        for (int index = 0; index < 6; index++) {
            Path module = Files.createDirectory(root.resolve("module-" + index));
            Files.writeString(module.resolve("pom.xml"), "<project>junit</project>");
            Files.writeString(module.resolve("build.gradle"), "plugins { id 'java' }");
            Files.writeString(module.resolve("package.json"), "{\"scripts\":{\"test\":\"vitest\"}}");
            Files.writeString(module.resolve("package-lock.json"), "{\"lockfileVersion\":3}");
            Files.writeString(module.resolve("pyproject.toml"), "[tool.pytest.ini_options]\n");
            Files.writeString(module.resolve("go.mod"), "module example.test/module\n");
            Files.writeString(module.resolve("Cargo.toml"), "[package]\nname='module'\n");
        }
        ProjectStackSnapshot snapshot = reversedSnapshot(new ProjectStackAnalyzer().analyze(root));

        assertThatThrownBy(() -> new ProjectConventionEvidenceCatalogCapture()
                .capture(root.toRealPath(), snapshot))
                .isInstanceOfSatisfying(ConflictException.class, failure -> assertThat(failure.code())
                        .isEqualTo("PROJECT_CONVENTION_EVIDENCE_CAPTURE_TOO_LARGE"));
    }

    @Test
    void rejectsMoreThanOneHundredTwentyEightSnapshotOwnedPaths() throws Exception {
        for (int index = 0; index < 43; index++) {
            Path module = Files.createDirectory(root.resolve("python-" + index));
            Files.writeString(module.resolve("requirements.txt"), "requests==2.0\n");
            Files.writeString(module.resolve("setup.py"), "from setuptools import setup\n");
        }
        ProjectStackSnapshot snapshot = reversedSnapshot(new ProjectStackAnalyzer().analyze(root));

        assertThatThrownBy(() -> new ProjectConventionEvidenceCatalogCapture()
                .capture(root.toRealPath(), snapshot))
                .isInstanceOfSatisfying(ConflictException.class, failure -> assertThat(failure.code())
                        .isEqualTo("PROJECT_CONVENTION_EVIDENCE_CAPTURE_TOO_LARGE"));
    }

    @Test
    void rejectsDuplicateComponentRootsEvenWhenTheirSnapshotKeysDiffer() throws Exception {
        List<ProjectStackSnapshot.Component> components = List.of(
                new ProjectStackSnapshot.Component("component-a", ".", List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of()),
                new ProjectStackSnapshot.Component("component-b", ".", List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of()));
        ProjectStackSnapshot duplicated = new ProjectStackSnapshot("stack-1", "project-1",
                ProjectStackProfileState.READY,
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                List.of(), List.of(), List.of(), 0, null, null, "2026-09-02T00:00:00Z", components);

        assertThatThrownBy(() -> new ProjectConventionEvidenceCatalogCapture()
                .capture(root.toRealPath(), duplicated))
                .isInstanceOfSatisfying(ConflictException.class, failure -> assertThat(failure.code())
                        .isEqualTo("PROJECT_CONVENTION_EVIDENCE_CAPTURE_INVALID"));
    }

    @Test
    void rejectsTraversalAndSymbolicLinkPathsFromTheFrozenSnapshot() throws Exception {
        ProjectStackSnapshot traversal = snapshotWithComponent(new ProjectStackSnapshot.Component(
                "component-a", "../outside", List.of(), List.of(), List.of(), List.of(), List.of(), List.of()),
                "a".repeat(64));
        assertThatThrownBy(() -> new ProjectConventionEvidenceCatalogCapture()
                .capture(root.toRealPath(), traversal))
                .isInstanceOfSatisfying(ConflictException.class, failure -> assertThat(failure.code())
                        .isEqualTo("PROJECT_CONVENTION_EVIDENCE_CAPTURE_INVALID"));

        Path actual = Files.writeString(root.resolve("actual-pom.xml"), "<project/>\n");
        Files.createSymbolicLink(root.resolve("pom.xml"), actual);
        ProjectStackSnapshot symbolic = snapshotWithComponent(new ProjectStackSnapshot.Component(
                "component-a", ".", List.of("java"), List.of("java"), List.of("maven"), List.of(),
                List.of("pom.xml"), List.of()), "a".repeat(64));
        assertThatThrownBy(() -> new ProjectConventionEvidenceCatalogCapture()
                .capture(root.toRealPath(), symbolic))
                .isInstanceOfSatisfying(ConflictException.class, failure -> assertThat(failure.code())
                        .isEqualTo("PROJECT_CONVENTION_EVIDENCE_CAPTURE_INVALID"));

        Path rootAlias = root.resolve("root-alias");
        Files.createSymbolicLink(rootAlias, root);
        ProjectStackSnapshot empty = new ProjectStackSnapshot("stack-1", "project-1",
                ProjectStackProfileState.READY,
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                List.of(), List.of(), List.of(), 0, null, null, "2026-09-02T00:00:00Z", List.of());
        assertThatThrownBy(() -> new ProjectConventionEvidenceCatalogCapture().capture(rootAlias, empty))
                .isInstanceOfSatisfying(ConflictException.class, failure -> assertThat(failure.code())
                        .isEqualTo("PROJECT_CONVENTION_EVIDENCE_CAPTURE_INVALID"));
    }

    @Test
    void neverTurnsWrapperOrSnapshotTextIntoACommandWithoutMatchingManifestEvidence() throws Exception {
        Files.writeString(root.resolve("package.json"), "{}");
        Files.writeString(root.resolve("package-lock.json"), "{\"lockfileVersion\":3}");
        Path wrapper = Files.writeString(root.resolve("mvnw"), "#!/bin/sh\n");
        assertThat(wrapper.toFile().setExecutable(true)).isTrue();
        ProjectStackAnalyzer.Analysis analysis = new ProjectStackAnalyzer().analyze(root);
        ProjectStackAnalyzer.ComponentResult analyzed = analysis.components().getFirst();
        ProjectStackSnapshot snapshot = snapshotWithComponent(new ProjectStackSnapshot.Component(
                analyzed.key(), analyzed.relativeRoot(), analyzed.technologyFamilies(), analyzed.technologies(),
                List.of("maven", "npm"), List.of("junit"), analyzed.manifestSources(),
                List.of("command=rm -rf /", "command=bash -c publish")), analysis.manifestFingerprint());

        ProjectConventionCompilation.EvidenceCatalog catalog =
                new ProjectConventionEvidenceCatalogCapture().capture(root.toRealPath(), snapshot);

        assertThat(catalog.commands()).containsExactly(new ProjectConventionCompilation.CommandEvidence(
                analyzed.key() + ":npm:install", analyzed.key(), List.of("npm", "ci")));
    }

    @Test
    void rejectsARepositoryWhoseManifestsChangedAfterTheSnapshotWasFrozen() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>\n");
        ProjectStackSnapshot snapshot = reversedSnapshot(new ProjectStackAnalyzer().analyze(root));
        Files.writeString(root.resolve("pom.xml"), "<project><changed/></project>\n");

        assertThatThrownBy(() -> new ProjectConventionEvidenceCatalogCapture()
                .capture(root.toRealPath(), snapshot))
                .isInstanceOfSatisfying(ConflictException.class, failure -> assertThat(failure.code())
                        .isEqualTo("PROJECT_CONVENTION_STACK_SNAPSHOT_CHANGED"));
    }

    private static ProjectStackSnapshot reversedSnapshot(ProjectStackAnalyzer.Analysis analysis) {
        List<ProjectStackSnapshot.Component> components = new ArrayList<>(analysis.components().stream()
                .map(component -> new ProjectStackSnapshot.Component(component.key(), component.relativeRoot(),
                        component.technologyFamilies(), component.technologies(), component.buildTools(),
                        component.testFrameworks(), component.manifestSources(), component.evidence()))
                .toList());
        Collections.reverse(components);
        return new ProjectStackSnapshot("stack-1", "project-1", ProjectStackProfileState.READY,
                analysis.manifestFingerprint(), analysis.technologyFamilies(), analysis.technologies(),
                analysis.evidence(), analysis.filesScanned(), analysis.errorCode(), analysis.errorDetail(),
                "2026-09-02T00:00:00Z", components);
    }

    private static ProjectStackSnapshot snapshotWithComponent(ProjectStackSnapshot.Component component,
                                                              String fingerprint) {
        return new ProjectStackSnapshot("stack-1", "project-1", ProjectStackProfileState.READY, fingerprint,
                component.technologyFamilies(), component.technologies(), List.of(), 1, null, null,
                "2026-09-02T00:00:00Z", List.of(component));
    }
}
