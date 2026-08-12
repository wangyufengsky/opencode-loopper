package io.opencode.loopper.verification;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.nio.file.Path;

/** Normalizes high-confidence Maven argv mistakes without invoking a shell. */
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

    private static int firstWhitespace(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isWhitespace(value.charAt(index))) return index;
        }
        return -1;
    }

    public record Normalization(List<String> command, ParseFailure failure, boolean changed) { }

    public record ParseFailure(int index, String message) { }

    private record Tokenization(List<String> tokens, String error) { }
}
