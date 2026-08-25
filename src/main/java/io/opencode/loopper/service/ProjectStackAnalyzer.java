package io.opencode.loopper.service;

import io.opencode.loopper.domain.ProjectStackProfileState;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Performs one bounded, non-symlink filesystem analysis without opening a database transaction. */
@Component
public final class ProjectStackAnalyzer {
    static final int MAX_FILES = 2_000;
    static final int MAX_DEPTH = 5;
    private static final long MAX_SIGNAL_BYTES = 512_000;
    private static final Set<String> SKIP = Set.of(
            ".git", "target", "build", "node_modules", "dist", "data", ".idea", ".gradle", ".venv", "venv");

    public Analysis analyze(Path root) {
        Path canonical;
        try {
            canonical = root.toRealPath();
            if (!Files.isDirectory(canonical, LinkOption.NOFOLLOW_LINKS)) {
                return failed("PROJECT_STACK_ROOT_INVALID", "Registered project root is not a directory");
            }
        } catch (IOException | RuntimeException failure) {
            return failed("PROJECT_STACK_ROOT_INVALID", safe(failure));
        }
        Scan scan = new Scan(canonical);
        try {
            Files.walkFileTree(canonical, Set.of(), MAX_DEPTH, scan);
        } catch (IOException failure) {
            if (scan.filesScanned == 0 && scan.signals.isEmpty()) {
                return failed("PROJECT_STACK_SCAN_FAILED", safe(failure));
            }
            scan.partial("scan-error=" + failure.getClass().getSimpleName());
        }
        return scan.finish();
    }

    private static Analysis failed(String code, String detail) {
        return new Analysis(ProjectStackProfileState.FAILED, sha256("failed:" + code),
                List.of(), List.of(), List.of("analysis-failed=" + code), 0, code, detail, List.of());
    }

    private static final class Scan extends SimpleFileVisitor<Path> {
        private final Path root;
        private final Map<String, MutableComponent> components = new LinkedHashMap<>();
        private final List<Signal> signals = new ArrayList<>();
        private final List<String> evidence = new ArrayList<>();
        private int filesScanned;
        private boolean partial;

        private Scan(Path root) { this.root = root; }

        @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            if (!dir.equals(root) && (Files.isSymbolicLink(dir) || SKIP.contains(dir.getFileName().toString()))) {
                return FileVisitResult.SKIP_SUBTREE;
            }
            return partial && filesScanned >= MAX_FILES ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
        }

        @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (Files.isSymbolicLink(file) || !attrs.isRegularFile()) return FileVisitResult.CONTINUE;
            filesScanned++;
            if (filesScanned > MAX_FILES) {
                filesScanned = MAX_FILES;
                partial("file-limit-reached=" + MAX_FILES);
                return FileVisitResult.TERMINATE;
            }
            detect(file, attrs);
            return FileVisitResult.CONTINUE;
        }

        @Override public FileVisitResult visitFileFailed(Path file, IOException failure) {
            partial("read-error=" + relative(file));
            return FileVisitResult.CONTINUE;
        }

        private void detect(Path file, BasicFileAttributes attrs) {
            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
            SignalKind kind = SignalKind.from(name, file);
            if (kind == null) return;
            String relative = relative(file);
            String contentHash;
            String content = "";
            try {
                if (attrs.size() > MAX_SIGNAL_BYTES) {
                    partial("manifest-too-large=" + relative);
                    contentHash = sha256("oversize:" + attrs.size());
                } else {
                    byte[] bytes = Files.readAllBytes(file);
                    contentHash = sha256(bytes);
                    content = new String(bytes, StandardCharsets.UTF_8);
                }
            } catch (IOException failure) {
                partial("manifest-read-error=" + relative);
                contentHash = sha256("unreadable:" + relative);
            }
            String componentRoot = componentRoot(file, kind);
            MutableComponent component = components.computeIfAbsent(componentRoot, MutableComponent::new);
            component.add(kind, relative, content);
            signals.add(new Signal(relative, contentHash));
        }

        private String componentRoot(Path file, SignalKind kind) {
            Path parent = file.getParent();
            if (kind.pythonTest && parent != null && parent.getFileName() != null
                    && "tests".equalsIgnoreCase(parent.getFileName().toString())) {
                parent = parent.getParent();
            }
            if (parent == null || parent.equals(root)) return ".";
            return normalized(root.relativize(parent));
        }

        private String relative(Path path) {
            try { return normalized(root.relativize(path)); }
            catch (RuntimeException ignored) { return path.getFileName().toString(); }
        }

        private void partial(String reason) {
            partial = true;
            if (evidence.size() < 64) evidence.add(reason);
        }

        private Analysis finish() {
            signals.sort(Comparator.comparing(Signal::path));
            String fingerprintInput = signals.stream()
                    .map(signal -> signal.path + "\u0000" + signal.sha256).reduce("", (a, b) -> a + b + "\n");
            List<ComponentResult> results = components.values().stream()
                    .sorted(Comparator.comparing(component -> component.relativeRoot))
                    .map(MutableComponent::result).toList();
            LinkedHashSet<String> technologies = new LinkedHashSet<>();
            LinkedHashSet<String> families = new LinkedHashSet<>();
            results.forEach(component -> {
                technologies.addAll(component.technologies());
                families.addAll(component.technologyFamilies());
            });
            List<String> stableEvidence = new ArrayList<>(evidence);
            stableEvidence.sort(String::compareTo);
            stableEvidence.add("files-scanned=" + filesScanned);
            stableEvidence.add("components=" + results.size());
            return new Analysis(partial ? ProjectStackProfileState.PARTIAL : ProjectStackProfileState.READY,
                    sha256(fingerprintInput), sortedCopy(families), sortedCopy(technologies), List.copyOf(stableEvidence),
                    filesScanned, partial ? "PROJECT_STACK_PARTIAL" : null,
                    partial ? "The bounded scan completed with incomplete evidence" : null, results);
        }
    }

    private static final class MutableComponent {
        private final String relativeRoot;
        private final LinkedHashSet<String> families = new LinkedHashSet<>();
        private final LinkedHashSet<String> technologies = new LinkedHashSet<>();
        private final LinkedHashSet<String> buildTools = new LinkedHashSet<>();
        private final LinkedHashSet<String> testFrameworks = new LinkedHashSet<>();
        private final LinkedHashSet<String> manifests = new LinkedHashSet<>();
        private final LinkedHashSet<String> evidence = new LinkedHashSet<>();

        private MutableComponent(String relativeRoot) { this.relativeRoot = relativeRoot; }

        private void add(SignalKind kind, String source, String content) {
            families.add(kind.family);
            technologies.add(kind.technology);
            if (kind.buildTool != null) buildTools.add(kind.buildTool);
            if (kind.testFramework != null) testFrameworks.add(kind.testFramework);
            manifests.add(source);
            evidence.add("source=" + source);
            String lower = content.toLowerCase(Locale.ROOT);
            if (kind.family.equals("java")) {
                if (lower.contains("junit")) testFrameworks.add("junit");
                if (lower.contains("testng")) testFrameworks.add("testng");
                if (lower.contains("surefire")) testFrameworks.add("surefire");
            } else if (kind.family.equals("node") && lower.matches("(?s).*\"test[^\"]*\"\s*:.*")) {
                testFrameworks.add("npm");
            } else if (kind.family.equals("python")) {
                if (lower.contains("pytest")) testFrameworks.add("pytest");
                if (lower.contains("unittest")) testFrameworks.add("unittest");
            }
        }

        private ComponentResult result() {
            return new ComponentResult(componentKey(relativeRoot), relativeRoot, sortedCopy(families),
                    sortedCopy(technologies), sortedCopy(buildTools), sortedCopy(testFrameworks),
                    sortedCopy(manifests), sortedCopy(evidence));
        }
    }

    private enum SignalKind {
        MAVEN("java", "java", "maven", null, false),
        GRADLE("java", "java", "gradle", null, false),
        NODE("node", "node", "npm", null, false),
        PYTHON("python", "python", "python", null, false),
        PYTEST("python", "python", "python", "pytest", true),
        UNITTEST("python", "python", "python", "unittest", true),
        GO("other", "go", "go", "go-test", false),
        RUST("other", "rust", "cargo", "cargo-test", false);

        private final String family;
        private final String technology;
        private final String buildTool;
        private final String testFramework;
        private final boolean pythonTest;

        SignalKind(String family, String technology, String buildTool, String testFramework, boolean pythonTest) {
            this.family = family; this.technology = technology; this.buildTool = buildTool;
            this.testFramework = testFramework; this.pythonTest = pythonTest;
        }

        private static SignalKind from(String name, Path path) {
            return switch (name) {
                case "pom.xml" -> MAVEN;
                case "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts" -> GRADLE;
                case "package.json" -> NODE;
                case "pyproject.toml", "requirements.txt", "setup.py", "setup.cfg", "tox.ini" -> PYTHON;
                case "pytest.ini", "conftest.py" -> PYTEST;
                case "go.mod" -> GO;
                case "cargo.toml" -> RUST;
                default -> name.startsWith("test_") && name.endsWith(".py") ? PYTEST
                        : name.endsWith("_test.py") || name.endsWith(".py")
                                && normalized(path).contains("/tests/") ? UNITTEST : null;
            };
        }
    }

    public record Analysis(ProjectStackProfileState state, String manifestFingerprint,
                           List<String> technologyFamilies, List<String> technologies, List<String> evidence,
                           int filesScanned, String errorCode, String errorDetail,
                           List<ComponentResult> components) { }
    public record ComponentResult(String key, String relativeRoot, List<String> technologyFamilies,
                                  List<String> technologies, List<String> buildTools, List<String> testFrameworks,
                                  List<String> manifestSources, List<String> evidence) { }
    private record Signal(String path, String sha256) { }

    private static String componentKey(String root) { return "component-" + sha256(root).substring(0, 12); }
    private static List<String> sortedCopy(Set<String> values) { return values.stream().sorted().toList(); }
    private static String normalized(Path path) { return path.toString().replace('\\', '/'); }
    private static String sha256(String value) { return sha256(value.getBytes(StandardCharsets.UTF_8)); }
    private static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private static String safe(Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) return failure.getClass().getSimpleName();
        String normalized = message.replaceAll("[\\r\\n]+", " ");
        return normalized.substring(0, Math.min(500, normalized.length()));
    }
}
