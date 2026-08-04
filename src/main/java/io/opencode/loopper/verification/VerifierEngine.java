package io.opencode.loopper.verification;

import io.opencode.loopper.domain.LoopSpec.VerifierSpec;
import io.opencode.loopper.domain.TaskFailure;
import io.opencode.loopper.domain.VerificationState;
import io.opencode.loopper.runtime.ProcessResult;
import io.opencode.loopper.runtime.SafeProcessRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class VerifierEngine {
    private static final int MAX_PATH_RULE_LENGTH = 512;
    private static final int MAX_GIT_PATH_LENGTH = 32_768;
    private static final long PATH_POLICY_WORK_BUDGET = 10_000_000L;
    private static final Duration MAX_VERIFIER_TIMEOUT = Duration.ofHours(1);
    private static final Set<String> SHELL_EXECUTABLES = Set.of(
            "sh", "bash", "zsh", "dash", "ksh", "fish", "csh", "tcsh",
            "cmd", "cmd.exe", "powershell", "powershell.exe", "pwsh", "pwsh.exe");
    private final SafeProcessRunner runner;
    public VerifierEngine(SafeProcessRunner runner) { this.runner = runner; }

    public VerifierOutcome verify(Path worktree, String baselineCommit, VerifierSpec spec, Duration timeout) {
        Duration boundedTimeout = requireBoundedTimeout(timeout);
        String type = spec.type().toUpperCase();
        return switch (type) {
            case "PROCESS" -> process(worktree, spec, boundedTimeout);
            case "FILE_EXISTS" -> file(worktree, spec, true);
            case "FILE_NOT_EXISTS" -> file(worktree, spec, false);
            case "GIT_DIFF" -> gitDiff(worktree, baselineCommit, spec, boundedTimeout);
            default -> throw new TaskFailure("VERIFIER_TYPE_INVALID", "Unknown verifier type: " + type);
        };
    }

    private VerifierOutcome process(Path worktree, VerifierSpec spec, Duration timeout) {
        requireDirectExecutable(spec.command());
        ProcessResult result = runner.run(worktree, spec.command(), timeout);
        boolean passed = !result.timedOut() && !result.outputTruncated() && result.exitCode() == 0;
        String summary = result.timedOut() ? "Process verifier timed out"
                : result.outputTruncated() ? "Process verifier output exceeded the safe limit"
                : "Process exited " + result.exitCode();
        return new VerifierOutcome("PROCESS", passed ? VerificationState.PASS : VerificationState.FAIL,
                summary,
                Map.of("argv", spec.command(), "exitCode", result.exitCode(), "timedOut", result.timedOut(),
                        "outputTruncated", result.outputTruncated(), "output", truncate(result.output())));
    }

    private Duration requireBoundedTimeout(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative() || timeout.compareTo(MAX_VERIFIER_TIMEOUT) > 0) {
            throw new TaskFailure("VERIFIER_TIMEOUT_INVALID", "Verifier timeout must be between 1 millisecond and 1 hour");
        }
        return timeout;
    }

    /**
     * A PROCESS verifier is an argv contract, not a shell snippet. Rejecting shell
     * launchers keeps quoting, expansion and command chaining outside this trust boundary.
     */
    private void requireDirectExecutable(List<String> command) {
        if (command == null || command.isEmpty()) {
            throw new TaskFailure("VERIFIER_COMMAND_INVALID", "PROCESS verifier requires a non-empty argv array");
        }
        String executable;
        try { executable = Path.of(command.getFirst()).getFileName().toString().toLowerCase(Locale.ROOT); }
        catch (RuntimeException invalidPath) {
            throw new TaskFailure("VERIFIER_COMMAND_INVALID", "PROCESS verifier executable is invalid");
        }
        if (SHELL_EXECUTABLES.contains(executable)) {
            throw new TaskFailure("VERIFIER_SHELL_FORBIDDEN", "PROCESS verifier must invoke a program directly, not a shell");
        }
    }

    private VerifierOutcome file(Path worktree, VerifierSpec spec, boolean expected) {
        Path target = managedRelative(worktree, spec.path());
        boolean actual = Files.exists(target);
        boolean passed = expected == actual;
        return new VerifierOutcome(expected ? "FILE_EXISTS" : "FILE_NOT_EXISTS", passed ? VerificationState.PASS : VerificationState.FAIL,
                (expected ? "Expected file to exist: " : "Expected file not to exist: ") + spec.path(),
                Map.of("path", target.toString(), "exists", actual));
    }

    private VerifierOutcome gitDiff(Path worktree, String baseline, VerifierSpec spec, Duration timeout) {
        if (baseline == null || baseline.isBlank()) throw new TaskFailure("GIT_BASELINE_MISSING", "GIT_DIFF verifier requires a task baseline commit");
        ProcessResult result = runner.run(worktree, List.of("git", "diff", "--name-status", baseline), timeout);
        if (result.outputTruncated()) {
            throw new TaskFailure("GIT_DIFF_OUTPUT_TRUNCATED", "Git diff exceeded the safe evidence limit");
        }
        if (result.timedOut() || result.exitCode() != 0) {
            return new VerifierOutcome("GIT_DIFF", VerificationState.ERROR, "Unable to inspect Git diff",
                    Map.of("exitCode", result.exitCode(), "output", truncate(result.output())));
        }
        ProcessResult untrackedResult = runner.run(worktree, List.of("git", "ls-files", "--others", "--exclude-standard"), timeout);
        if (untrackedResult.outputTruncated()) {
            throw new TaskFailure("GIT_DIFF_OUTPUT_TRUNCATED", "Untracked-file evidence exceeded the safe limit");
        }
        if (untrackedResult.timedOut() || untrackedResult.exitCode() != 0) {
            return new VerifierOutcome("GIT_DIFF", VerificationState.ERROR, "Unable to inspect untracked files",
                    Map.of("exitCode", untrackedResult.exitCode(), "output", truncate(untrackedResult.output())));
        }
        List<String> changed = changedPaths(result.output());
        List<String> untracked = untrackedResult.output().lines().filter(s -> !s.isBlank()).toList();
        changed = new ArrayList<>(changed);
        changed.addAll(untracked);
        List<String> violations = new ArrayList<>();
        SlashGlobMatcher.WorkBudget policyBudget = new SlashGlobMatcher.WorkBudget(PATH_POLICY_WORK_BUDGET);
        try {
            for (String path : changed) {
                if (isForbidden(path, spec.forbiddenPaths(), policyBudget)) violations.add("forbidden path: " + path);
                if (!spec.allowedPaths().isEmpty() && !isAllowed(path, spec.allowedPaths(), policyBudget)) violations.add("outside allowed paths: " + path);
            }
        } catch (SlashGlobMatcher.WorkLimitExceeded exhausted) {
            throw new TaskFailure("VERIFIER_PATH_POLICY_LIMIT_EXCEEDED", "Verifier path policy exceeded its bounded matching budget");
        }
        if (Boolean.TRUE.equals(spec.forbidDeletes())) {
            for (String line : result.output().lines().toList()) if (line.startsWith("D\t")) violations.add("deletion: " + line.substring(2));
        }
        boolean requiresChanges = Boolean.TRUE.equals(spec.requireChanges());
        if (requiresChanges && changed.isEmpty()) violations.add("expected a Git diff, but no files changed");
        boolean passed = violations.isEmpty();
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("baseline", baseline); evidence.put("changedPaths", changed); evidence.put("untrackedPaths", untracked); evidence.put("violations", violations);
        return new VerifierOutcome("GIT_DIFF", passed ? VerificationState.PASS : VerificationState.FAIL,
                passed ? "Git diff satisfies policy" : String.join("; ", violations), evidence);
    }

    private Path managedRelative(Path worktree, String input) {
        if (input == null || input.isBlank()) throw new TaskFailure("VERIFIER_PATH_INVALID", "File verifier requires a path");
        Path supplied;
        try { supplied = Path.of(input); }
        catch (RuntimeException invalidPath) {
            throw new TaskFailure("VERIFIER_PATH_INVALID", "Verifier path is not valid on this platform");
        }
        if (supplied.isAbsolute()) throw new TaskFailure("VERIFIER_PATH_ESCAPE", "Verifier paths must be relative to the worktree");
        Path root;
        try { root = worktree.toRealPath(); }
        catch (Exception e) { throw new TaskFailure("WORKTREE_UNAVAILABLE", "Worktree cannot be resolved for file verification"); }
        Path resolved = root.resolve(supplied).normalize();
        if (!resolved.startsWith(root)) throw new TaskFailure("VERIFIER_PATH_ESCAPE", "Verifier path escaped its worktree");
        try {
            Path check = Files.exists(resolved) ? resolved.toRealPath() : resolved.getParent().toRealPath();
            if (!check.startsWith(root)) throw new TaskFailure("VERIFIER_SYMLINK_ESCAPE", "Verifier path resolved outside its worktree");
        } catch (TaskFailure e) { throw e; }
        catch (Exception e) { throw new TaskFailure("VERIFIER_PATH_INVALID", "Verifier path cannot be resolved safely"); }
        return resolved;
    }
    private List<String> changedPaths(String output) {
        List<String> paths = new ArrayList<>();
        for (String line : output.lines().toList()) {
            String[] fields = line.split("\\t");
            if (fields.length >= 2) paths.add(fields[fields.length - 1]);
        }
        return paths;
    }
    private boolean isForbidden(String path, List<String> rules, SlashGlobMatcher.WorkBudget budget) {
        return rules.stream().anyMatch(rule -> matchesPathRule(path, rule, budget));
    }
    private boolean isAllowed(String path, List<String> rules, SlashGlobMatcher.WorkBudget budget) {
        return rules.stream().anyMatch(rule -> matchesPathRule(path, rule, budget));
    }

    /**
     * LoopSpec path policies accept either a directory/file prefix or a glob.
     * Git emits slash-separated paths on every platform. Match normalized
     * strings instead of the host FileSystem so the policy has identical
     * semantics on Linux, macOS and Windows.
     */
    private boolean matchesPathRule(String path, String inputRule, SlashGlobMatcher.WorkBudget budget) {
        if (inputRule == null || inputRule.isBlank()) {
            budget.consume(1);
            return false;
        }
        String rule = inputRule.replace('\\', '/').replaceAll("^\\./+", "");
        String candidate = path.replace('\\', '/').replaceAll("^\\./+", "");
        if (rule.length() > MAX_PATH_RULE_LENGTH || candidate.length() > MAX_GIT_PATH_LENGTH) {
            throw new TaskFailure("VERIFIER_PATH_POLICY_INVALID", "Verifier path policy exceeds its safety limit");
        }
        if (!containsGlob(rule)) {
            budget.consume(rule.length() + candidate.length() + 1L);
            String prefix = rule.endsWith("/") ? rule : rule + "/";
            return candidate.equals(rule) || candidate.startsWith(prefix);
        }
        try {
            return SlashGlobMatcher.matches(rule, candidate, budget);
        } catch (SlashGlobMatcher.WorkLimitExceeded exhausted) {
            throw new TaskFailure("VERIFIER_PATH_POLICY_LIMIT_EXCEEDED", "Verifier path policy exceeded its bounded matching budget");
        } catch (RuntimeException invalidPattern) {
            throw new TaskFailure("VERIFIER_PATH_PATTERN_INVALID", "Invalid verifier path pattern: " + inputRule);
        }
    }

    private boolean containsGlob(String value) {
        return value.indexOf('*') >= 0 || value.indexOf('?') >= 0 || value.indexOf('[') >= 0 || value.indexOf('{') >= 0;
    }
    private String truncate(String value) { return value == null ? "" : value.substring(0, Math.min(value.length(), 10_000)); }
}
