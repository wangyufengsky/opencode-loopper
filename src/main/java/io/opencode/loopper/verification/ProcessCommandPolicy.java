package io.opencode.loopper.verification;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Static checks for PROCESS argv mistakes that cannot be repaired safely by shell-style splitting. */
public final class ProcessCommandPolicy {
    private static final Set<String> MAVEN_EXECUTABLES = Set.of(
            "mvn", "mvn.cmd", "mvn.bat", "mvn.exe",
            "mvnw", "mvnw.cmd", "mvnw.bat", "mvnw.exe");
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

    public static Optional<CollapsedArgument> collapsedMavenArgument(List<String> command) {
        if (command == null || command.isEmpty()) return Optional.empty();
        String declaredExecutable = command.getFirst();
        if (declaredExecutable == null) return Optional.empty();

        int executableBoundary = firstWhitespace(declaredExecutable.trim());
        if (executableBoundary > 0
                && isMavenExecutable(declaredExecutable.trim().substring(0, executableBoundary))) {
            return Optional.of(new CollapsedArgument(0));
        }
        if (!isMavenExecutable(declaredExecutable)) return Optional.empty();

        for (int index = 1; index < command.size(); index++) {
            String argument = command.get(index);
            if (argument == null) continue;
            String trimmed = argument.trim();
            int boundary = firstWhitespace(trimmed);
            if (boundary < 0) continue;
            String firstToken = trimmed.substring(0, boundary);
            if (MAVEN_PHASES.contains(firstToken)
                    || MAVEN_PLUGIN_GOAL.matcher(firstToken).matches()
                    || (firstToken.startsWith("-") && !firstToken.contains("="))) {
                return Optional.of(new CollapsedArgument(index));
            }
        }
        return Optional.empty();
    }

    private static boolean isMavenExecutable(String executable) {
        String normalized = executable.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String fileName = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        return MAVEN_EXECUTABLES.contains(fileName.toLowerCase(Locale.ROOT));
    }

    private static int firstWhitespace(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isWhitespace(value.charAt(index))) return index;
        }
        return -1;
    }

    public record CollapsedArgument(int index) {
        public String message() {
            return "Maven command appears to contain multiple argv tokens in one item; "
                    + "split the lifecycle/goal and every option into separate command array items";
        }
    }
}
