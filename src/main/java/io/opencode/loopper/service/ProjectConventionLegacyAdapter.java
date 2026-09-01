package io.opencode.loopper.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Extracts the old Markdown response and submits it to the PROJECT_CONVENTION_V1 authority. */
@Component
final class ProjectConventionLegacyAdapter {
    private static final int MAX_LEGACY_CONTENT = 24_000;
    private static final Pattern AI_PAYLOAD = Pattern.compile(
            "<!--\\s*LOOPPER_PROJECT_CONTEXT_START\\s*-->(.*?)<!--\\s*LOOPPER_PROJECT_CONTEXT_END\\s*-->",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private final AiOutputExtractor extractor;
    private final ProjectConventionCompilation compilation;

    ProjectConventionLegacyAdapter(AiOutputExtractor extractor, ProjectConventionCompilation compilation) {
        this.extractor = extractor;
        this.compilation = compilation;
    }

    Adapted adapt(String output, String sourceContent, ProjectStackSnapshot snapshot) {
        AiOutputExtractor.TextExtractionResult extracted = extractor.extractMarkdown(
                output, AI_PAYLOAD, "PROJECT_CONTEXT_OUTPUT", MAX_LEGACY_CONTENT);
        String markdown = extracted.value();
        if (markdown.contains(ProjectConventionDocumentStore.START_MARKER)
                || markdown.contains(ProjectConventionDocumentStore.END_MARKER)
                || AI_PAYLOAD.matcher(markdown).find()) {
            throw new BadRequestException("PROJECT_CONTEXT_OUTPUT_INVALID",
                    "AI project context contains reserved markers");
        }
        ProjectConventionCompilation.Result result = compilation.compileLegacy(
                new ProjectConventionCompilation.Input(sourceContent, evidenceFrom(snapshot)), markdown);
        if (!result.accepted()) {
            ProjectConventionCompilation.Problem first = result.problems().getFirst();
            throw new BadRequestException(first.code(), first.staticDetail());
        }
        return new Adapted(extracted, result);
    }

    /**
     * Legacy drafts predate the catalog snapshot table. Use only facts already frozen in their persisted stack
     * snapshot; wrapper executability and lock files are deliberately not inferred from the live repository.
     */
    ProjectConventionCompilation.EvidenceCatalog evidenceFrom(ProjectStackSnapshot snapshot) {
        if (snapshot == null || !snapshot.usable()) {
            throw new BadRequestException("PROJECT_CONVENTION_EVIDENCE_INVALID",
                    "Legacy project convention has no usable frozen stack snapshot");
        }
        List<ProjectStackSnapshot.Component> components = snapshot.components().stream()
                .sorted(Comparator.comparing(ProjectStackSnapshot.Component::relativeRoot,
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(ProjectStackSnapshot.Component::key,
                                Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
        List<ProjectConventionCompilation.ComponentEvidence> componentEvidence = new ArrayList<>();
        List<ProjectConventionCompilation.CommandEvidence> commands = new ArrayList<>();
        List<ProjectConventionCompilation.PathEvidence> paths = new ArrayList<>();
        for (ProjectStackSnapshot.Component component : components) {
            componentEvidence.add(new ProjectConventionCompilation.ComponentEvidence(
                    component.key(), component.relativeRoot(), sorted(component.technologies()),
                    sorted(component.buildTools()), sorted(component.testFrameworks())));
            paths.add(new ProjectConventionCompilation.PathEvidence(component.key() + ":root", component.key(),
                    component.relativeRoot(), ProjectConventionCompilation.PathKind.COMPONENT_ROOT));
            List<String> manifests = component.manifestSources().stream().sorted().toList();
            for (String manifest : manifests) {
                paths.add(new ProjectConventionCompilation.PathEvidence(
                        component.key() + ":manifest:" + manifest, component.key(), manifest,
                        ProjectConventionCompilation.PathKind.MANIFEST));
            }
            addCommands(commands, component, manifests);
        }
        return new ProjectConventionCompilation.EvidenceCatalog(
                snapshot.manifestFingerprint(), componentEvidence, commands, paths);
    }

    private static void addCommands(List<ProjectConventionCompilation.CommandEvidence> target,
                                    ProjectStackSnapshot.Component component, List<String> manifests) {
        Set<String> names = new HashSet<>();
        manifests.forEach(manifest -> names.add(fileName(manifest)));
        Set<String> buildTools = Set.copyOf(component.buildTools());
        Set<String> tests = Set.copyOf(component.testFrameworks());
        String key = component.key();
        if (buildTools.contains("maven") && names.contains("pom.xml")) {
            target.add(command(key, "maven:build", "mvn", "package"));
            if (tests.stream().anyMatch(Set.of("junit", "testng", "surefire")::contains)) {
                target.add(command(key, "maven:test", "mvn", "test"));
            }
        }
        if (buildTools.contains("gradle") && names.stream().anyMatch(name -> name.startsWith("build.gradle")
                || name.startsWith("settings.gradle"))) {
            target.add(command(key, "gradle:build", "gradle", "build"));
            if (tests.stream().anyMatch(Set.of("junit", "testng")::contains)) {
                target.add(command(key, "gradle:test", "gradle", "test"));
            }
        }
        if (buildTools.contains("npm") && names.contains("package.json") && tests.contains("npm")) {
            target.add(command(key, "npm:test", "npm", "test"));
        }
        if (buildTools.contains("python") && names.stream().anyMatch(Set.of(
                "pyproject.toml", "pytest.ini", "setup.cfg", "tox.ini")::contains)) {
            if (tests.contains("pytest")) target.add(command(key, "python:pytest", "python3", "-m", "pytest"));
            if (tests.contains("unittest")) {
                target.add(command(key, "python:unittest", "python3", "-m", "unittest"));
            }
        }
        if (buildTools.contains("go") && names.contains("go.mod")) {
            target.add(command(key, "go:build", "go", "build", "./..."));
            if (tests.contains("go-test")) target.add(command(key, "go:test", "go", "test", "./..."));
        }
        if (buildTools.contains("cargo") && names.contains("Cargo.toml")) {
            target.add(command(key, "cargo:build", "cargo", "build"));
            if (tests.contains("cargo-test")) target.add(command(key, "cargo:test", "cargo", "test"));
        }
    }

    private static ProjectConventionCompilation.CommandEvidence command(
            String componentKey, String suffix, String... argv) {
        return new ProjectConventionCompilation.CommandEvidence(
                componentKey + ":" + suffix, componentKey, List.of(argv));
    }

    private static List<String> sorted(List<String> values) {
        return values == null ? List.of() : values.stream().sorted().toList();
    }

    private static String fileName(String path) {
        int separator = path == null ? -1 : path.lastIndexOf('/');
        return separator < 0 ? path : path.substring(separator + 1);
    }

    record Adapted(AiOutputExtractor.TextExtractionResult extraction,
                   ProjectConventionCompilation.Result compilation) { }
}
