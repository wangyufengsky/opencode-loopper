package io.opencode.loopper.runtime;

import io.opencode.loopper.domain.TaskFailure;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Selects the managed CLI; Windows uses the same argv resolution as model discovery. */
final class OpenCodeExecutableResolver {
    private final boolean windows;
    private final Map<String, String> environment;
    private final ExecutableResolver argvResolver;

    OpenCodeExecutableResolver() { this(System.getProperty("os.name", ""), System.getenv()); }

    OpenCodeExecutableResolver(String osName, Map<String, String> environment) {
        this.windows = osName.toLowerCase(Locale.ROOT).contains("win");
        this.environment = Map.copyOf(environment);
        this.argvResolver = new ExecutableResolver(osName, environment);
    }

    Path resolve(String configured) {
        List<String> requested = new ArrayList<>();
        if (configured != null && !configured.isBlank()) requested.add(configured.trim());
        String override = environmentValue("OPENCODE_EXECUTABLE");
        if (override != null && !override.isBlank()) requested.add(override.trim());
        requested.add("opencode");
        for (String item : requested) {
            Path match = windows ? resolveWindows(item) : resolveNative(item);
            if (match != null) return match;
        }
        throw new IllegalStateException("OpenCode executable was not found in OPENCODE_EXECUTABLE or PATH");
    }

    private Path resolveWindows(String item) {
        try { return Path.of(argvResolver.resolve(Path.of(".").toAbsolutePath(), List.of(item)).argv().getFirst()); }
        catch (TaskFailure unavailable) {
            if (!"PROCESS_COMMAND_UNAVAILABLE".equals(unavailable.code())) throw unavailable;
            return null;
        }
    }

    private Path resolveNative(String item) {
        Path candidate = Path.of(item);
        if (candidate.getNameCount() > 1 || item.contains("/") || item.contains("\\")) return usable(candidate);
        String path = environmentValue("PATH");
        if (path != null) for (String segment : path.split(Pattern.quote(File.pathSeparator))) {
            if (segment.isBlank()) continue;
            Path match = usable(Path.of(segment, item));
            if (match != null) return match;
        }
        return usable(candidate);
    }

    private static Path usable(Path path) {
        return Files.isRegularFile(path) && Files.isExecutable(path) ? path.toAbsolutePath().normalize() : null;
    }

    private String environmentValue(String name) {
        String exact = environment.get(name);
        if (exact != null || !windows) return exact;
        return environment.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }
}
