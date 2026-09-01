package io.opencode.loopper.service;

import io.opencode.loopper.domain.ProjectStackProfileState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.charset.StandardCharsets;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Captures one bounded, deterministic convention catalog from a frozen stack snapshot. */
@Component
final class ProjectConventionEvidenceCatalogCapture {
    private static final int MAX_COMPONENTS = 64;
    private static final int MAX_COMMANDS = 64;
    private static final int MAX_PATHS = 128;
    private static final long MAX_MANIFEST_BYTES = 512_000;
    private static final long MAX_TOTAL_BYTES = 16L * 1024 * 1024;
    private static final Pattern COMPONENT_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    ProjectConventionCompilation.EvidenceCatalog capture(Path registeredRoot, ProjectStackSnapshot snapshot) {
        Path root = canonicalRoot(registeredRoot);
        requireSnapshot(snapshot);
        if (snapshot.components().size() > MAX_COMPONENTS) throw tooLarge();
        if (snapshot.components().stream().anyMatch(component -> component == null
                || component.key() == null || component.relativeRoot() == null)) {
            throw invalid("Frozen project stack contains an incomplete component");
        }

        List<ProjectStackSnapshot.Component> components = snapshot.components().stream()
                .sorted(Comparator.comparing(ProjectStackSnapshot.Component::relativeRoot)
                        .thenComparing(ProjectStackSnapshot.Component::key))
                .toList();
        Set<String> componentKeys = new HashSet<>();
        Set<String> componentRoots = new HashSet<>();
        Set<String> manifestPaths = new HashSet<>();
        List<ProjectConventionCompilation.ComponentEvidence> componentEvidence = new ArrayList<>();
        List<ProjectConventionCompilation.CommandEvidence> commands = new ArrayList<>();
        List<ProjectConventionCompilation.PathEvidence> paths = new ArrayList<>();
        List<ManifestFact> manifests = new ArrayList<>();
        long[] totalBytes = {0};

        for (ProjectStackSnapshot.Component component : components) {
            if (component == null || !validKey(component.key()) || !componentKeys.add(component.key())) {
                throw invalid("Frozen project stack contains an invalid or duplicate component key");
            }
            String relativeRoot = normalizedRelative(component.relativeRoot(), true);
            if (!componentRoots.add(relativeRoot)) {
                throw invalid("Frozen project stack contains a duplicate component root");
            }
            Path componentRoot = resolve(root, relativeRoot);
            requireDirectory(root, componentRoot);
            List<String> technologies = sorted(component.technologies());
            List<String> buildTools = sorted(component.buildTools());
            List<String> testFrameworks = sorted(component.testFrameworks());
            componentEvidence.add(new ProjectConventionCompilation.ComponentEvidence(component.key(), relativeRoot,
                    technologies, buildTools, testFrameworks));
            paths.add(new ProjectConventionCompilation.PathEvidence(component.key() + ":root", component.key(),
                    relativeRoot, ProjectConventionCompilation.PathKind.COMPONENT_ROOT));
            if (paths.size() > MAX_PATHS) throw tooLarge();

            List<String> componentManifests = component.manifestSources().stream().sorted().toList();
            for (String source : componentManifests) {
                if (paths.size() >= MAX_PATHS) throw tooLarge();
                String relative = normalizedRelative(source, false);
                if (!withinComponent(relativeRoot, relative) || !manifestPaths.add(relative)) {
                    throw invalid("Frozen project stack contains a duplicate or out-of-component manifest path");
                }
                Path manifest = resolve(root, relative);
                ManifestFact fact = readManifest(root, manifest, relative, totalBytes);
                manifests.add(fact);
                paths.add(new ProjectConventionCompilation.PathEvidence(
                        component.key() + ":manifest:" + relative, component.key(), relative,
                        ProjectConventionCompilation.PathKind.MANIFEST));
            }
            addCommands(commands, component.key(), componentRoot, componentManifests,
                    buildTools, testFrameworks);
            if (commands.size() > MAX_COMMANDS) throw tooLarge();
        }
        requireCurrentFingerprint(snapshot.manifestFingerprint(), manifests);
        return new ProjectConventionCompilation.EvidenceCatalog(snapshot.manifestFingerprint(),
                componentEvidence, commands, paths);
    }

    private static void addCommands(List<ProjectConventionCompilation.CommandEvidence> target,
                                    String key, Path componentRoot, List<String> manifests,
                                    List<String> frozenBuildTools, List<String> frozenTestFrameworks) {
        Set<String> names = manifests.stream().map(ProjectConventionEvidenceCatalogCapture::fileName)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> buildTools = Set.copyOf(frozenBuildTools);
        Set<String> tests = Set.copyOf(frozenTestFrameworks);
        if (buildTools.contains("maven") && names.contains("pom.xml")) {
            String executable = managedExecutable(componentRoot, "mvnw") ? "./mvnw" : "mvn";
            target.add(command(key, "maven:build", executable, "package"));
            if (tests.stream().anyMatch(Set.of("junit", "testng", "surefire")::contains)) {
                target.add(command(key, "maven:test", executable, "test"));
            }
        }
        if (buildTools.contains("gradle") && names.stream().anyMatch(name -> name.startsWith("build.gradle")
                || name.startsWith("settings.gradle"))) {
            String executable = managedExecutable(componentRoot, "gradlew") ? "./gradlew" : "gradle";
            target.add(command(key, "gradle:build", executable, "build"));
            if (tests.stream().anyMatch(Set.of("junit", "testng")::contains)) {
                target.add(command(key, "gradle:test", executable, "test"));
            }
        }
        if (buildTools.contains("npm") && names.contains("package.json")) {
            if (managedFile(componentRoot, "package-lock.json")) {
                target.add(command(key, "npm:install", "npm", "ci"));
            }
            if (tests.contains("npm")) target.add(command(key, "npm:test", "npm", "test"));
        }
        if (buildTools.contains("python") && names.stream().anyMatch(Set.of(
                "pyproject.toml", "pytest.ini", "setup.cfg", "tox.ini")::contains)) {
            if (tests.contains("pytest")) {
                target.add(command(key, "python:pytest", "python3", "-m", "pytest"));
            }
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
            String key, String suffix, String... argv) {
        return new ProjectConventionCompilation.CommandEvidence(
                key + ":" + suffix, key, List.of(argv));
    }

    private static Path canonicalRoot(Path input) {
        if (input == null) throw invalid("Registered project root is required");
        try {
            Path declared = input.toAbsolutePath().normalize();
            Path canonical = input.toRealPath();
            if (!declared.equals(canonical) || !Files.isDirectory(canonical, LinkOption.NOFOLLOW_LINKS)) {
                throw invalid("Registered project root is not its canonical directory");
            }
            return canonical;
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof ConflictException conflict) throw conflict;
            throw invalid("Registered project root cannot be resolved safely");
        }
    }

    private static void requireSnapshot(ProjectStackSnapshot snapshot) {
        if (snapshot == null || snapshot.state() != ProjectStackProfileState.READY
                && snapshot.state() != ProjectStackProfileState.PARTIAL
                || snapshot.manifestFingerprint() == null
                || !snapshot.manifestFingerprint().matches("[0-9a-f]{64}")) {
            throw invalid("A usable frozen project stack snapshot is required");
        }
    }

    private static Path resolve(Path root, String relative) {
        Path resolved = ".".equals(relative) ? root : root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) throw invalid("Frozen evidence path escapes the registered project root");
        Path current = root;
        Path relativePath = root.relativize(resolved);
        for (Path segment : relativePath) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw invalid("Frozen evidence path traverses a symbolic link");
            }
        }
        return resolved;
    }

    private static void requireDirectory(Path root, Path value) {
        if (!value.startsWith(root) || !Files.isDirectory(value, LinkOption.NOFOLLOW_LINKS)) {
            throw invalid("Frozen component root is not a managed directory");
        }
    }

    private static ManifestFact readManifest(Path root, Path file, String relative, long[] totalBytes) {
        try {
            if (!file.startsWith(root) || Files.isSymbolicLink(file)
                    || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw invalid("Frozen manifest is not a managed regular file");
            }
            BasicFileAttributes before = Files.readAttributes(
                    file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!before.isRegularFile()) throw invalid("Frozen manifest is not a managed regular file");
            if (before.size() > MAX_MANIFEST_BYTES) throw tooLarge();
            byte[] bytes;
            Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
            try (SeekableByteChannel channel = Files.newByteChannel(file, options)) {
                bytes = Channels.newInputStream(channel).readNBytes((int) MAX_MANIFEST_BYTES + 1);
            }
            if (bytes.length > MAX_MANIFEST_BYTES) throw tooLarge();
            totalBytes[0] += bytes.length;
            if (totalBytes[0] > MAX_TOTAL_BYTES) throw tooLarge();
            BasicFileAttributes after = Files.readAttributes(
                    file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!sameIdentity(before, after)) {
                throw new ConflictException("PROJECT_CONVENTION_EVIDENCE_CAPTURE_CHANGED",
                        "Project convention evidence changed during its bounded capture");
            }
            return new ManifestFact(relative, sha256(bytes));
        } catch (IOException failure) {
            throw invalid("Frozen manifest could not be read safely");
        }
    }

    private static void requireCurrentFingerprint(String expected, List<ManifestFact> manifests) {
        StringBuilder source = new StringBuilder();
        manifests.stream().sorted(Comparator.comparing(ManifestFact::path)).forEach(manifest -> source
                .append(manifest.path()).append('\0').append(manifest.sha256()).append('\n'));
        if (!Objects.equals(expected, sha256(source.toString().getBytes(StandardCharsets.UTF_8)))) {
            throw new ConflictException("PROJECT_CONVENTION_STACK_SNAPSHOT_CHANGED",
                    "Project manifests no longer match the frozen stack snapshot");
        }
    }

    private static boolean sameIdentity(BasicFileAttributes before, BasicFileAttributes after) {
        return before.isRegularFile() && after.isRegularFile()
                && before.size() == after.size()
                && before.lastModifiedTime().equals(after.lastModifiedTime())
                && Objects.equals(before.fileKey(), after.fileKey());
    }

    private static boolean managedExecutable(Path root, String name) {
        return managedFile(root, name) && Files.isExecutable(root.resolve(name));
    }

    private static boolean managedFile(Path root, String name) {
        Path value = root.resolve(name);
        return !Files.isSymbolicLink(value) && Files.isRegularFile(value, LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean withinComponent(String componentRoot, String path) {
        return ".".equals(componentRoot) || path.startsWith(componentRoot + "/");
    }

    private static String normalizedRelative(String value, boolean rootAllowed) {
        if (value == null || value.isBlank() || value.length() > 1_024 || value.indexOf('\\') >= 0
                || value.startsWith("/") || value.chars().anyMatch(Character::isISOControl)) {
            throw invalid("Frozen evidence path must be a normalized relative path");
        }
        if (".".equals(value)) {
            if (rootAllowed) return value;
            throw invalid("Frozen manifest path cannot be the project root");
        }
        Path path;
        try { path = Path.of(value); }
        catch (RuntimeException failure) { throw invalid("Frozen evidence path is invalid"); }
        if (path.isAbsolute() || !path.normalize().toString().replace('\\', '/').equals(value)
                || path.startsWith("..")) {
            throw invalid("Frozen evidence path must be normalized and contained");
        }
        return value;
    }

    private static boolean validKey(String value) {
        return value != null && COMPONENT_KEY.matcher(value).matches();
    }

    private static List<String> sorted(List<String> values) {
        if (values == null || values.stream().anyMatch(Objects::isNull)) {
            throw invalid("Frozen component facts are incomplete");
        }
        return values.stream().distinct().sorted().toList();
    }

    private static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static ConflictException invalid(String detail) {
        return new ConflictException("PROJECT_CONVENTION_EVIDENCE_CAPTURE_INVALID", detail);
    }

    private static ConflictException tooLarge() {
        return new ConflictException("PROJECT_CONVENTION_EVIDENCE_CAPTURE_TOO_LARGE",
                "Project convention evidence exceeds its bounded capture limit");
    }

    private record ManifestFact(String path, String sha256) { }
}
