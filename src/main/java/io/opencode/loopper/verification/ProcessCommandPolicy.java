package io.opencode.loopper.verification;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.nio.file.Path;

/** Normalizes high-confidence Maven argv mistakes without invoking a shell. */
public final class ProcessCommandPolicy {
    private static final Set<String> SHELL_EXECUTABLES = Set.of(
            "sh", "bash", "zsh", "dash", "ksh", "fish", "csh", "tcsh",
            "cmd", "cmd.exe", "powershell", "powershell.exe", "pwsh", "pwsh.exe");
    private static final Set<String> SHELL_OPERATORS = Set.of("|", "||", "&&", ";", ">", ">>", "<", "<<");
    private static final Set<String> MAVEN_EXECUTABLES = Set.of(
            "mvn", "mvn.cmd", "mvn.bat", "mvn.exe",
            "mvnw", "mvnw.cmd", "mvnw.bat", "mvnw.exe");
    private static final Set<String> GRADLE_EXECUTABLES = Set.of(
            "gradle", "gradle.cmd", "gradle.bat", "gradle.exe",
            "gradlew", "gradlew.cmd", "gradlew.bat", "gradlew.exe");
    private static final Set<String> NPM_EXECUTABLES = Set.of("npm", "npm.cmd", "npm.exe");
    private static final Set<String> MAVEN_PHASES = Set.of(
            "pre-clean", "clean", "post-clean",
            "validate", "initialize", "generate-sources", "process-sources",
            "generate-resources", "process-resources", "compile", "process-classes",
            "generate-test-sources", "process-test-sources", "generate-test-resources",
            "process-test-resources", "test-compile", "process-test-classes", "test",
            "prepare-package", "package", "pre-integration-test", "integration-test",
            "post-integration-test", "verify", "install", "deploy",
            "pre-site", "site", "post-site", "site-deploy");
    private static final Pattern MAVEN_PLUGIN_GOAL = Pattern.compile(
            "[A-Za-z0-9_.-]+(?::[A-Za-z0-9_.-]+){1,3}");

    private ProcessCommandPolicy() { }

    /** Returns a stable validation message when argv attempts to smuggle shell or inline Java execution. */
    public static String directCommandError(List<String> command) {
        if (command == null || command.isEmpty()) return "PROCESS requires a non-empty direct argv command";
        String rawExecutable = command.getFirst();
        if (rawExecutable == null || rawExecutable.isBlank()) return "PROCESS executable is invalid";
        boolean pathLikeExecutable = rawExecutable.indexOf('/') >= 0 || rawExecutable.indexOf('\\') >= 0;
        if (!pathLikeExecutable && rawExecutable.chars().anyMatch(Character::isWhitespace)) {
            return "PROCESS executable must be one direct argv item, not a command line";
        }
        try { Path.of(rawExecutable); }
        catch (RuntimeException invalidPath) { return "PROCESS executable is invalid"; }
        String executable = baseName(rawExecutable);
        if (executable.isEmpty()) return "PROCESS executable is invalid";
        if (SHELL_EXECUTABLES.contains(executable)) return "PROCESS must invoke a program directly, not a shell; shell launchers are forbidden";
        for (int index = 1; index < command.size(); index++) {
            String argument = command.get(index);
            if (argument == null) continue;
            if (SHELL_OPERATORS.contains(argument) || argument.contains("$(") || argument.indexOf('`') >= 0) {
                return "PROCESS command[" + index + "] contains a forbidden shell fragment";
            }
        }
        if (Set.of("java", "java.exe").contains(executable) && command.stream().skip(1).anyMatch("-e"::equals)) {
            return "Java does not support -e inline execution; plan a focused unit test in this stage instead";
        }
        return null;
    }

    public static Normalization normalizeMavenCommand(List<String> command) {
        if (command == null || command.isEmpty() || command.getFirst() == null) {
            return new Normalization(command == null ? List.of() : List.copyOf(command), null, false);
        }

        String declaredExecutable = command.getFirst();
        int executableBoundary = firstWhitespace(declaredExecutable.trim());
        List<String> normalized = new ArrayList<>();
        boolean changed = false;
        if (executableBoundary > 0
                && isMavenExecutable(declaredExecutable.trim().substring(0, executableBoundary))) {
            Tokenization tokenization = tokenize(declaredExecutable);
            if (tokenization.error() != null) {
                return failure(command, 0, tokenization.error());
            }
            normalized.addAll(tokenization.tokens());
            changed = true;
        } else if (isMavenExecutable(declaredExecutable)) {
            normalized.add(declaredExecutable);
        } else {
            return new Normalization(List.copyOf(command), null, false);
        }

        for (int index = 1; index < command.size(); index++) {
            String argument = command.get(index);
            if (!looksCollapsedMavenArgument(argument)) {
                normalized.add(argument);
                continue;
            }
            Tokenization tokenization = tokenize(argument);
            if (tokenization.error() != null) {
                return failure(command, index, tokenization.error());
            }
            normalized.addAll(tokenization.tokens());
            changed = true;
        }
        return new Normalization(List.copyOf(normalized), null, changed);
    }

    private static Normalization failure(List<String> command, int index, String detail) {
        return new Normalization(List.copyOf(command), new ParseFailure(index,
                "Maven command contains collapsed argv tokens that cannot be parsed safely: " + detail), false);
    }

    private static boolean looksCollapsedMavenArgument(String argument) {
        if (argument == null) return false;
        String trimmed = argument.trim();
        int boundary = firstWhitespace(trimmed);
        if (boundary < 0) return false;
        String firstToken = trimmed.substring(0, boundary);
        return MAVEN_PHASES.contains(firstToken)
                || MAVEN_PLUGIN_GOAL.matcher(firstToken).matches()
                || (firstToken.startsWith("-") && !firstToken.contains("="));
    }

    private static Tokenization tokenize(String value) {
        List<String> tokens = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        char quote = 0;
        boolean tokenStarted = false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (quote != 0) {
                if (current == quote) {
                    quote = 0;
                    tokenStarted = true;
                } else if (current == '\\' && quote == '"') {
                    if (index + 1 >= value.length()) return new Tokenization(List.of(), "dangling escape");
                    token.append(value.charAt(++index));
                    tokenStarted = true;
                } else {
                    token.append(current);
                    tokenStarted = true;
                }
            } else if (current == '\'' || current == '"') {
                quote = current;
                tokenStarted = true;
            } else if (current == '\\') {
                if (index + 1 >= value.length()) return new Tokenization(List.of(), "dangling escape");
                token.append(value.charAt(++index));
                tokenStarted = true;
            } else if (Character.isWhitespace(current)) {
                if (tokenStarted) {
                    tokens.add(token.toString());
                    token.setLength(0);
                    tokenStarted = false;
                }
            } else {
                token.append(current);
                tokenStarted = true;
            }
        }
        if (quote != 0) return new Tokenization(List.of(), "unclosed quote");
        if (tokenStarted) tokens.add(token.toString());
        return new Tokenization(List.copyOf(tokens), null);
    }

    public static boolean isMavenExecutable(String executable) {
        String normalized = executable.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String fileName = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        return MAVEN_EXECUTABLES.contains(fileName.toLowerCase(Locale.ROOT));
    }

    public static boolean isMavenWrapper(String executable) {
        return mavenBaseName(executable).equals("mvnw");
    }

    public static boolean isSystemMaven(String executable) {
        return mavenBaseName(executable).equals("mvn");
    }

    /**
     * Classifies the small set of direct test invocations that LoopSpec v2 may use as
     * behavior evidence. This is shared by draft validation and the execution boundary so
     * a persisted contract cannot bypass the policy after confirmation.
     */
    public static TestCommandAssessment assessTestCommand(List<String> command) {
        if (command == null || command.isEmpty()) {
            return new TestCommandAssessment(false, false, "command is not a recognized test invocation");
        }
        String executable = baseName(command.getFirst());
        List<String> args = command.stream().skip(1)
                .map(value -> value == null ? "" : value.toLowerCase(Locale.ROOT))
                .toList();
        boolean recognized;
        if (MAVEN_EXECUTABLES.contains(executable)) {
            recognized = args.stream().anyMatch(arg -> Set.of("test", "integration-test", "verify").contains(arg));
        } else if (GRADLE_EXECUTABLES.contains(executable)) {
            recognized = args.stream().anyMatch(ProcessCommandPolicy::isGradleTestTask);
        } else if (NPM_EXECUTABLES.contains(executable)) {
            recognized = !args.isEmpty() && (args.getFirst().equals("test")
                    || (args.size() > 1 && args.getFirst().equals("run") && args.get(1).startsWith("test")));
        } else {
            recognized = false;
        }
        if (!recognized) {
            return new TestCommandAssessment(false, false, "command is not a recognized test invocation");
        }
        boolean skipped = skipsTests(executable, args);
        return new TestCommandAssessment(true, skipped,
                skipped ? "test command disables tests" : "targeted test command");
    }

    /** True only for a focused Java test command, never npm or an unfiltered full suite. */
    public static boolean isFocusedJavaTestCommand(List<String> command) {
        TestCommandAssessment assessment = assessTestCommand(command);
        if (!assessment.recognized() || assessment.skipped() || command == null || command.isEmpty()) return false;
        String executable = baseName(command.getFirst());
        List<String> args = command.stream().skip(1)
                .map(value -> value == null ? "" : value.toLowerCase(Locale.ROOT).replace(" ", ""))
                .toList();
        if (MAVEN_EXECUTABLES.contains(executable)) {
            return args.stream().anyMatch(value -> value.startsWith("-dtest=") || value.startsWith("-dit.test="));
        }
        if (GRADLE_EXECUTABLES.contains(executable)) {
            for (int index = 0; index < args.size(); index++) {
                String value = args.get(index);
                if (value.startsWith("--tests=") && value.length() > "--tests=".length()) return true;
                if (value.equals("--tests") && index + 1 < args.size() && !args.get(index + 1).isBlank()) return true;
            }
        }
        return false;
    }

    /**
     * Extracts only test targets explicitly present in a recognized focused Maven/Gradle argv.
     * The result is safe for mechanical LoopSpec canonicalization: it never guesses a test from
     * source text, a full-suite command, or an exclusion-only selector.
     */
    public static List<String> explicitFocusedJavaTestTargets(List<String> command) {
        if (command == null || command.isEmpty()) return List.of();
        Normalization normalization = normalizeMavenCommand(command);
        List<String> normalized = normalization.failure() == null ? normalization.command() : List.copyOf(command);
        if (!isFocusedJavaTestCommand(normalized)) return List.of();

        String executable = baseName(normalized.getFirst());
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        if (MAVEN_EXECUTABLES.contains(executable)) {
            for (String argument : normalized.stream().skip(1).toList()) {
                if (argument == null) continue;
                String compact = argument.replace(" ", "");
                String lower = compact.toLowerCase(Locale.ROOT);
                String value = null;
                if (lower.startsWith("-dtest=")) value = compact.substring("-Dtest=".length());
                else if (lower.startsWith("-dit.test=")) value = compact.substring("-Dit.test=".length());
                if (value == null) continue;
                for (String target : value.split(",")) {
                    String explicit = target.trim();
                    if (!explicit.isEmpty() && !explicit.startsWith("!")) targets.add(explicit);
                }
            }
        } else if (GRADLE_EXECUTABLES.contains(executable)) {
            for (int index = 1; index < normalized.size(); index++) {
                String argument = normalized.get(index);
                if (argument == null) continue;
                if (argument.regionMatches(true, 0, "--tests=", 0, "--tests=".length())) {
                    String target = argument.substring("--tests=".length()).trim();
                    if (!target.isEmpty() && !target.startsWith("!")) targets.add(target);
                } else if (argument.equalsIgnoreCase("--tests") && index + 1 < normalized.size()) {
                    String target = normalized.get(++index);
                    if (target != null && !target.isBlank() && !target.trim().startsWith("!")) {
                        targets.add(target.trim());
                    }
                }
            }
        }
        return List.copyOf(targets);
    }

    public static Path platformMavenWrapper(Path projectRoot, String osName) {
        boolean windows = osName != null && osName.toLowerCase(Locale.ROOT).contains("win");
        return projectRoot.resolve(windows ? "mvnw.cmd" : "mvnw");
    }

    private static String mavenBaseName(String executable) {
        if (executable == null) return "";
        String normalized = executable.replace('\\', '/');
        String fileName = normalized.substring(normalized.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        for (String extension : List.of(".cmd", ".bat", ".exe")) {
            if (fileName.endsWith(extension)) return fileName.substring(0, fileName.length() - extension.length());
        }
        return fileName;
    }

    private static String baseName(String executable) {
        if (executable == null) return "";
        String normalized = executable.replace('\\', '/').toLowerCase(Locale.ROOT);
        return normalized.substring(normalized.lastIndexOf('/') + 1);
    }

    private static boolean skipsTests(String executable, List<String> args) {
        if (MAVEN_EXECUTABLES.contains(executable)) {
            return args.stream().map(value -> value.replace(" ", ""))
                    .anyMatch(value -> value.equals("-dskiptests") || value.equals("-dskiptests=true")
                            || value.equals("-dmaven.test.skip") || value.equals("-dmaven.test.skip=true")
                            || value.equals("--skiptests")
                            || value.equals("--skip-tests") || value.equals("-dskipits")
                            || value.equals("-dskipits=true")
                            || value.equals("-dsurefire.failifnospecifiedtests=false")
                            || value.equals("-dfailsafe.failifnospecifiedtests=false"));
        }
        if (GRADLE_EXECUTABLES.contains(executable)) {
            for (int index = 0; index < args.size(); index++) {
                String argument = args.get(index);
                if ((argument.equals("-x") || argument.equals("--exclude-task")) && index + 1 < args.size()
                        && isGradleTestTask(args.get(index + 1))) {
                    return true;
                }
                if (argument.startsWith("--exclude-task=")
                        && isGradleTestTask(argument.substring("--exclude-task=".length()))) {
                    return true;
                }
                if (argument.startsWith("-x") && argument.length() > 2) {
                    String excluded = argument.substring(2);
                    if (excluded.startsWith("=")) excluded = excluded.substring(1);
                    if (isGradleTestTask(excluded)) return true;
                }
            }
            return false;
        }
        return NPM_EXECUTABLES.contains(executable)
                && args.stream().anyMatch(value -> value.equals("--if-present") || value.equals("--ignore-scripts"));
    }

    private static boolean isGradleTestTask(String value) {
        if (value == null) return false;
        String task = value.toLowerCase(Locale.ROOT);
        return task.equals("test") || task.equals("check") || task.endsWith(":test") || task.endsWith(":check");
    }

    private static int firstWhitespace(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isWhitespace(value.charAt(index))) return index;
        }
        return -1;
    }

    public record Normalization(List<String> command, ParseFailure failure, boolean changed) { }

    public record ParseFailure(int index, String message) { }

    public record TestCommandAssessment(boolean recognized, boolean skipped, String reason) { }

    private record Tokenization(List<String> tokens, String error) { }
}
