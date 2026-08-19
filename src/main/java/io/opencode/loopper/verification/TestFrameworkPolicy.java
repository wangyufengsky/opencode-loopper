package io.opencode.loopper.verification;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Closed registry for direct, non-shell test commands across supported stacks. */
public final class TestFrameworkPolicy {
    private static final Set<String> PYTHON = Set.of("python", "python3", "python.exe", "py", "py.exe");
    private static final Set<String> PYTEST = Set.of("pytest", "pytest.exe", "py.test", "py.test.exe");

    private TestFrameworkPolicy() { }

    public static Assessment assess(List<String> command) {
        if (command == null || command.isEmpty() || command.getFirst() == null)
            return new Assessment(null, false, false, false, List.of(), "command is not a recognized test invocation");
        String executable = baseName(command.getFirst());
        List<String> args = command.stream().skip(1).map(value -> value == null ? "" : value).toList();
        if (PYTEST.contains(executable)) return python("PYTEST", args, 0);
        if (PYTHON.contains(executable) && args.size() >= 2 && "-m".equals(args.getFirst())) {
            if ("pytest".equalsIgnoreCase(args.get(1))) return python("PYTEST", args, 2);
            if ("unittest".equalsIgnoreCase(args.get(1))) return python("UNITTEST", args, 2);
        }
        return new Assessment(null, false, false, false, List.of(), "command is not a Python test invocation");
    }

    private static Assessment python(String framework, List<String> args, int start) {
        boolean skipped = false;
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        for (int index = start; index < args.size(); index++) {
            String raw = args.get(index);
            String value = raw.toLowerCase(Locale.ROOT);
            if (Set.of("--ignore", "--ignore-glob", "--deselect", "-k", "-m", "--exclude").contains(value)) {
                skipped = true;
                if (index + 1 < args.size()) index++;
                continue;
            }
            if (value.startsWith("--ignore=") || value.startsWith("--ignore-glob=")
                    || value.startsWith("--deselect=") || value.startsWith("--exclude=")) skipped = true;
            if (value.startsWith("--") || value.startsWith("-")) continue;
            targets.add(raw);
        }
        boolean focused = !targets.isEmpty();
        return new Assessment(framework, true, skipped, focused, List.copyOf(targets),
                skipped ? "test command excludes tests" : focused ? "explicit Python test target" : "Python test discovery");
    }

    public static List<String> explicitTargets(List<String> command) {
        Assessment assessment = assess(command);
        return assessment.recognized() && !assessment.skipped() ? assessment.targets() : List.of();
    }

    private static String baseName(String executable) {
        String normalized = executable.replace('\\', '/').toLowerCase(Locale.ROOT);
        return normalized.substring(normalized.lastIndexOf('/') + 1);
    }

    public record Assessment(String framework, boolean recognized, boolean skipped,
                             boolean focused, List<String> targets, String reason) { }
}
