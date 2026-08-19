package io.opencode.loopper.verification;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Closed, shell-free registry for Maven, Gradle, npm, pytest, and unittest test targets. */
public final class TestFrameworkPolicy {
    private static final Set<String> PYTHON = Set.of("python", "python3", "python.exe", "py", "py.exe");
    private static final Set<String> PYTEST = Set.of("pytest", "pytest.exe", "py.test", "py.test.exe");
    private static final Set<String> MAVEN = Set.of("mvn", "mvn.cmd", "mvn.bat", "mvn.exe", "mvnw", "mvnw.cmd", "mvnw.bat", "mvnw.exe");
    private static final Set<String> GRADLE = Set.of("gradle", "gradle.cmd", "gradle.bat", "gradle.exe", "gradlew", "gradlew.cmd", "gradlew.bat", "gradlew.exe");
    private static final Set<String> NPM = Set.of("npm", "npm.cmd", "npm.exe");
    private static final Set<String> PYTHON_FILTERS = Set.of("--ignore", "--ignore-glob", "--deselect", "-k", "-m", "--exclude");
    private static final Set<String> PYTHON_OPTIONS_WITH_VALUE = Set.of("--maxfail", "--tb", "--rootdir", "--confcutdir", "--capture", "--color", "--durations", "--junitxml");
    private static final Set<String> UNITTEST_DISCOVERY_OPTIONS = Set.of("-s", "--start-directory", "-p", "--pattern", "-t", "--top-level-directory");

    private TestFrameworkPolicy() { }

    public static Assessment assess(List<String> command) {
        if (command == null || command.isEmpty() || command.getFirst() == null) return unknown();
        String executable = baseName(command.getFirst());
        List<String> args = command.stream().skip(1).map(value -> value == null ? "" : value).toList();
        if (MAVEN.contains(executable)) return maven(args);
        if (GRADLE.contains(executable)) return gradle(args);
        if (NPM.contains(executable)) return npm(args);
        if (PYTEST.contains(executable)) return python("PYTEST", args, 0);
        if (PYTHON.contains(executable) && args.size() >= 2 && "-m".equals(args.getFirst())) {
            if ("pytest".equalsIgnoreCase(args.get(1))) return python("PYTEST", args, 2);
            if ("unittest".equalsIgnoreCase(args.get(1))) return python("UNITTEST", args, 2);
        }
        return unknown();
    }

    private static Assessment maven(List<String> args) {
        boolean recognized = args.stream().map(TestFrameworkPolicy::lower)
                .anyMatch(value -> Set.of("test", "integration-test", "verify").contains(value));
        if (!recognized) return unknown();
        boolean skipped = args.stream().map(TestFrameworkPolicy::lower).map(value -> value.replace(" ", ""))
                .anyMatch(value -> value.equals("-dskiptests") || value.equals("-dmaven.test.skip")
                        || value.equals("-dskiptests=true") || value.equals("-dmaven.test.skip=true")
                        || value.equals("--skiptests") || value.equals("--skip-tests")
                        || value.equals("-dskipits") || value.equals("-dskipits=true")
                        || value.equals("-dsurefire.failifnospecifiedtests=false")
                        || value.equals("-dfailsafe.failifnospecifiedtests=false"));
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        for (String argument : args) {
            String compact = argument.replace(" ", ""); String value = null;
            if (lower(compact).startsWith("-dtest=")) value = compact.substring("-Dtest=".length());
            else if (lower(compact).startsWith("-dit.test=")) value = compact.substring("-Dit.test=".length());
            if (value != null) for (String target : value.split(",")) if (!target.isBlank() && !target.startsWith("!")) targets.add(target.trim());
        }
        return result("MAVEN", skipped, targets);
    }

    private static Assessment gradle(List<String> args) {
        boolean recognized = args.stream().map(TestFrameworkPolicy::lower)
                .anyMatch(TestFrameworkPolicy::isGradleTestTask);
        if (!recognized) return unknown();
        boolean skipped = false; LinkedHashSet<String> targets = new LinkedHashSet<>();
        for (int index = 0; index < args.size(); index++) {
            String value = args.get(index); String normalized = lower(value);
            if ((normalized.equals("-x") || normalized.equals("--exclude-task")) && index + 1 < args.size()) {
                if (isGradleTestTask(args.get(index + 1))) skipped = true;
                index++;
                continue;
            }
            if (normalized.startsWith("--exclude-task=")
                    && isGradleTestTask(normalized.substring("--exclude-task=".length()))) skipped = true;
            if (normalized.startsWith("-x") && normalized.length() > 2) {
                String excluded = normalized.substring(2);
                if (excluded.startsWith("=")) excluded = excluded.substring(1);
                if (isGradleTestTask(excluded)) skipped = true;
            }
            if (normalized.startsWith("--tests=") && value.length() > "--tests=".length()) targets.add(value.substring("--tests=".length()).trim());
            else if (normalized.equals("--tests") && index + 1 < args.size() && !args.get(index + 1).isBlank()) targets.add(args.get(++index).trim());
        }
        return result("GRADLE", skipped, targets);
    }

    private static Assessment npm(List<String> args) {
        if (args.isEmpty()) return unknown();
        int selectorStart; String first = lower(args.getFirst());
        if (first.equals("test")) selectorStart = 1;
        else if (first.equals("run") && args.size() > 1 && lower(args.get(1)).startsWith("test")) selectorStart = 2;
        else return unknown();
        boolean skipped = false; LinkedHashSet<String> targets = new LinkedHashSet<>(); boolean afterSeparator = false;
        for (int index = selectorStart; index < args.size(); index++) {
            String value = args.get(index); String normalized = lower(value);
            if (value.equals("--")) { afterSeparator = true; continue; }
            if (normalized.equals("--if-present") || normalized.equals("--ignore-scripts")
                    || normalized.equals("--passwithnotests") || normalized.startsWith("--testpathignorepatterns")) skipped = true;
            if (afterSeparator && !value.isBlank() && !value.startsWith("-")) targets.add(value.trim());
        }
        return result("NPM", skipped, targets);
    }

    private static Assessment python(String framework, List<String> args, int start) {
        boolean skipped = false; boolean discovery = false; LinkedHashSet<String> targets = new LinkedHashSet<>();
        for (int index = start; index < args.size(); index++) {
            String raw = args.get(index); String value = lower(raw);
            if (framework.equals("UNITTEST") && value.equals("discover")) { discovery = true; continue; }
            if (framework.equals("UNITTEST") && discovery && UNITTEST_DISCOVERY_OPTIONS.contains(value)) {
                if (index + 1 < args.size()) index++;
                continue;
            }
            if (PYTHON_FILTERS.contains(value)) { skipped = true; if (index + 1 < args.size()) index++; continue; }
            if (value.startsWith("--ignore=") || value.startsWith("--ignore-glob=") || value.startsWith("--deselect=") || value.startsWith("--exclude=")) { skipped = true; continue; }
            if (PYTHON_OPTIONS_WITH_VALUE.contains(value)) { if (index + 1 < args.size()) index++; continue; }
            if (value.startsWith("--") || value.startsWith("-")) continue;
            targets.add(raw);
        }
        return result(framework, skipped, targets);
    }

    private static Assessment result(String framework, boolean skipped, LinkedHashSet<String> targets) {
        boolean focused = !targets.isEmpty();
        return new Assessment(framework, true, skipped, focused, List.copyOf(targets),
                skipped ? "test command excludes or tolerates missing tests" : focused ? "explicit " + framework + " test target" : framework + " test discovery");
    }
    private static Assessment unknown() { return new Assessment(null, false, false, false, List.of(), "command is not a recognized test invocation"); }
    private static boolean isGradleTestTask(String value) {
        String task = lower(value);
        return task.equals("test") || task.equals("check") || task.endsWith(":test") || task.endsWith(":check");
    }
    public static List<String> explicitTargets(List<String> command) { Assessment value = assess(command); return value.recognized() && !value.skipped() ? value.targets() : List.of(); }
    private static String lower(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT); }
    private static String baseName(String executable) { String normalized = executable.replace('\\', '/').toLowerCase(Locale.ROOT); return normalized.substring(normalized.lastIndexOf('/') + 1); }

    public record Assessment(String framework, boolean recognized, boolean skipped,
                             boolean focused, List<String> targets, String reason) { }
}
