package io.opencode.loopper.runtime;

import io.opencode.loopper.domain.TaskFailure;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Resolves Windows executable suffixes without accepting a caller-supplied shell command. */
public final class ExecutableResolver {
    private static final List<String> DEFAULT_WINDOWS_EXTENSIONS = List.of(".COM", ".EXE", ".BAT", ".CMD");
    private static final Set<String> SUPPORTED_WINDOWS_EXTENSIONS = Set.of(".COM", ".EXE", ".BAT", ".CMD");

    private final String osName;
    private final Map<String, String> environment;

    public ExecutableResolver() {
        this(System.getProperty("os.name", ""), System.getenv());
    }

    public ExecutableResolver(String osName, Map<String, String> environment) {
        this.osName = osName == null ? "" : osName;
        this.environment = environment == null ? Map.of() : Map.copyOf(environment);
    }

    public Resolution resolve(Path workingDirectory, List<String> argv) {
        return resolve(workingDirectory, argv, Map.of());
    }

    public Resolution resolve(Path workingDirectory, List<String> argv, Map<String, String> environmentOverlay) {
        if (!isWindows()) return new Resolution(List.copyOf(argv), null);

        String executable = argv.getFirst();
        Path resolved;
        String reason;
        if (isProjectWrapper(executable)) {
            resolved = resolveAgainstDirectory(workingDirectory, executable, environmentOverlay);
            reason = "WINDOWS_PROJECT_WRAPPER";
        } else if (containsPath(executable)) {
            resolved = resolveAgainstDirectory(workingDirectory, executable, environmentOverlay);
            reason = "WINDOWS_EXPLICIT_PATH";
        } else {
            resolved = resolveFromPath(executable, environmentOverlay);
            reason = "WINDOWS_PATHEXT_PATH";
        }
        if (resolved == null) {
            throw new TaskFailure("PROCESS_COMMAND_UNAVAILABLE",
                    "Executable is not available for Windows direct argv execution: " + executable
                            + ". Check the Loopper process PATH or declare an existing absolute/relative executable path");
        }

        List<String> actual = new ArrayList<>(argv);
        actual.set(0, resolved.toString());
        return new Resolution(List.copyOf(actual), executable.equals(resolved.toString()) ? null : reason);
    }

    private Path resolveAgainstDirectory(Path workingDirectory, String executable, Map<String, String> overlay) {
        String normalized = executable.replace('\\', '/');
        Path requested = Path.of(normalized);
        Path base = requested.isAbsolute() ? requested : workingDirectory.resolve(requested);
        return firstUsableCandidate(base, overlay);
    }

    private Path resolveFromPath(String executable, Map<String, String> overlay) {
        String path = environmentValue("PATH", overlay);
        if (path == null || path.isBlank()) return null;
        for (String rawDirectory : path.split(";")) {
            String directory = unquote(rawDirectory.trim());
            if (directory.isBlank()) continue;
            Path resolved = firstUsableCandidate(Path.of(directory).resolve(executable), overlay);
            if (resolved != null) return resolved;
        }
        return null;
    }

    private Path firstUsableCandidate(Path base, Map<String, String> overlay) {
        for (Path candidate : candidates(base, overlay)) {
            if (Files.isRegularFile(candidate)) return candidate.toAbsolutePath().normalize();
        }
        return null;
    }

    private List<Path> candidates(Path base, Map<String, String> overlay) {
        String fileName = base.getFileName() == null ? "" : base.getFileName().toString();
        if (hasAnyExtension(fileName)) return List.of(base);
        List<Path> candidates = new ArrayList<>();
        for (String extension : windowsExtensions(overlay)) {
            candidates.add(Path.of(base.toString() + extension.toLowerCase(Locale.ROOT)));
            candidates.add(Path.of(base.toString() + extension.toUpperCase(Locale.ROOT)));
        }
        return candidates.stream().distinct().toList();
    }

    private List<String> windowsExtensions(Map<String, String> overlay) {
        String configured = environmentValue("PATHEXT", overlay);
        if (configured == null || configured.isBlank()) return DEFAULT_WINDOWS_EXTENSIONS;
        LinkedHashSet<String> extensions = new LinkedHashSet<>();
        for (String item : configured.split(";")) {
            String extension = item.trim().toUpperCase(Locale.ROOT);
            if (!extension.startsWith(".")) extension = "." + extension;
            if (SUPPORTED_WINDOWS_EXTENSIONS.contains(extension)) extensions.add(extension);
        }
        return extensions.isEmpty() ? DEFAULT_WINDOWS_EXTENSIONS : List.copyOf(extensions);
    }

    private String environmentValue(String name, Map<String, String> overlay) {
        String value = caseInsensitiveValue(overlay, name);
        return value != null ? value : caseInsensitiveValue(environment, name);
    }

    private static String caseInsensitiveValue(Map<String, String> values, String name) {
        String exact = values.get(name);
        if (exact != null) return exact;
        return values.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst().orElse(null);
    }

    private boolean isWindows() {
        return osName.toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean containsPath(String executable) {
        return Path.of(executable.replace('\\', '/')).isAbsolute()
                || executable.contains("/") || executable.contains("\\");
    }

    private static boolean isProjectWrapper(String executable) {
        String normalized = executable.replace('\\', '/');
        String fileName = normalized.substring(normalized.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return fileName.equals("mvnw") || fileName.equals("mvnw.cmd") || fileName.equals("mvnw.bat")
                || fileName.equals("gradlew") || fileName.equals("gradlew.cmd") || fileName.equals("gradlew.bat");
    }

    private static boolean hasAnyExtension(String fileName) {
        return fileName.lastIndexOf('.') >= 0;
    }

    private static String unquote(String value) {
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1) : value;
    }

    public record Resolution(List<String> argv, String reason) {
        public boolean changed() { return reason != null; }
    }
}
