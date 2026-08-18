package io.opencode.loopper.verification;

import io.opencode.loopper.domain.LoopSpec.VerifierSpec;
import io.opencode.loopper.domain.TaskFailure;
import io.opencode.loopper.domain.VerificationState;
import io.opencode.loopper.runtime.ProcessResult;
import io.opencode.loopper.runtime.DirectWorkspaceBaselineManager;
import io.opencode.loopper.runtime.ExecutableResolver;
import io.opencode.loopper.runtime.SafeProcessRunner;
import io.opencode.loopper.runtime.StageWorkspaceBaselineManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

@Component
public class VerifierEngine {
    private static final long PATH_POLICY_WORK_BUDGET = 10_000_000L;
    private static final Duration MAX_VERIFIER_TIMEOUT = Duration.ofHours(1);
    private final SafeProcessRunner runner;
    private final DirectWorkspaceBaselineManager directBaselines;
    private final StageWorkspaceBaselineManager stageBaselines;
    private final BinaryArtifactStore artifacts;
    private final NativeVerifierRegistry nativeVerifiers;
    public VerifierEngine(SafeProcessRunner runner) {
        this(runner, null, null, new BinaryArtifactStore(Path.of("./data")));
    }
    public VerifierEngine(SafeProcessRunner runner, DirectWorkspaceBaselineManager directBaselines) {
        this(runner, directBaselines, null, new BinaryArtifactStore(Path.of("./data")));
    }
    public VerifierEngine(SafeProcessRunner runner, DirectWorkspaceBaselineManager directBaselines, BinaryArtifactStore artifacts) {
        this(runner, directBaselines, null, artifacts);
    }
    @Autowired
    public VerifierEngine(SafeProcessRunner runner, DirectWorkspaceBaselineManager directBaselines,
                          StageWorkspaceBaselineManager stageBaselines, BinaryArtifactStore artifacts) {
        this.runner = runner;
        this.directBaselines = directBaselines;
        this.stageBaselines = stageBaselines;
        this.artifacts = artifacts;
        this.nativeVerifiers = new NativeVerifierRegistry();
    }

    public VerifierOutcome verify(Path worktree, String baselineCommit, VerifierSpec spec, Duration timeout) {
        Duration boundedTimeout = requireBoundedTimeout(timeout);
        String type = spec.type().toUpperCase();
        return switch (type) {
            case "PROCESS" -> process(worktree, spec, boundedTimeout);
            case "FILE_EXISTS" -> advisoryFileExists(worktree, spec);
            case "FILE_NOT_EXISTS" -> file(worktree, spec, false);
            case "GIT_DIFF" -> gitDiff(worktree, baselineCommit, spec, boundedTimeout);
            default -> nativeVerifiers.verify(new NativeVerifierContext(worktree, boundedTimeout, artifacts), spec);
        };
    }

    public DiffPreview previewDiff(Path worktree, String baseline, String path, boolean untracked, Duration timeout) {
        return previewDiff(worktree, baseline, null, path, untracked, timeout);
    }

    public DiffPreview previewDiff(Path worktree, String baseline, String taskBranch, String path,
                                   boolean untracked, Duration timeout) {
        Duration boundedTimeout = requireBoundedTimeout(timeout);
        if (baseline == null || baseline.isBlank()) {
            throw new TaskFailure("GIT_BASELINE_MISSING", "Diff preview requires a task baseline");
        }
        managedRelative(worktree, path);
        ProcessResult result;
        boolean direct = baseline.startsWith(DirectWorkspaceBaselineManager.PREFIX);
        boolean stage = baseline.startsWith(StageWorkspaceBaselineManager.PREFIX);
        boolean taskBranchCheckedOut = direct || stage || taskBranch == null || taskBranch.isBlank()
                || taskBranch.equals(currentGitBranch(worktree, boundedTimeout));
        if (untracked && taskBranchCheckedOut) {
            result = runner.run(worktree, List.of("git", "--literal-pathspecs", "diff", "--no-index", "--no-ext-diff",
                    "--no-textconv", "--no-color", "--unified=80", "--", "/dev/null", path), boundedTimeout);
        } else if (direct) {
            if (directBaselines == null) {
                throw new TaskFailure("DIRECT_BASELINE_UNAVAILABLE", "Direct-execution diff support is unavailable");
            }
            result = directBaselines.patch(worktree, baseline, path, boundedTimeout);
        } else if (stage) {
            if (stageBaselines == null) {
                throw new TaskFailure("STAGE_WORKSPACE_BASELINE_UNAVAILABLE",
                        "Stage workspace diff support is unavailable");
            }
            result = stageBaselines.patch(worktree, baseline, path, boundedTimeout);
        } else if (!taskBranchCheckedOut) {
            result = runner.run(worktree, List.of("git", "--literal-pathspecs", "diff", "--no-ext-diff", "--no-textconv",
                    "--no-color", "--unified=80", baseline, "refs/heads/" + taskBranch, "--", path), boundedTimeout);
        } else {
            result = runner.run(worktree, List.of("git", "--literal-pathspecs", "diff", "--no-ext-diff", "--no-textconv",
                    "--no-color", "--unified=80", baseline, "--", path), boundedTimeout);
        }
        boolean acceptedExit = untracked ? result.exitCode() == 0 || result.exitCode() == 1 : result.exitCode() == 0;
        if (result.timedOut()) {
            throw new TaskFailure("DIFF_PREVIEW_TIMEOUT", "Diff preview timed out");
        }
        if (!acceptedExit) {
            throw new TaskFailure("DIFF_PREVIEW_FAILED", "Unable to generate diff preview: " + truncate(result.output()));
        }
        return new DiffPreview(path, untracked ? "NEW" : "MODIFIED", result.output(), result.outputTruncated());
    }

    /** Previews a persisted immutable checkpoint even when its Task branch is not checked out. */
    public DiffPreview previewDiffAtRef(Path repository, String baseline, String targetRef, String path,
                                        boolean untracked, Duration timeout) {
        Duration boundedTimeout = requireBoundedTimeout(timeout);
        if (baseline == null || baseline.isBlank() || targetRef == null || targetRef.isBlank()) {
            throw new TaskFailure("GIT_BASELINE_MISSING", "Checkpoint diff preview requires baseline and target refs");
        }
        managedRelative(repository, path);
        ProcessResult result = runner.run(repository,
                List.of("git", "--literal-pathspecs", "diff", "--no-ext-diff", "--no-textconv",
                        "--no-color", "--unified=80", baseline, targetRef, "--", path), boundedTimeout);
        if (result.timedOut()) throw new TaskFailure("DIFF_PREVIEW_TIMEOUT", "Diff preview timed out");
        if (result.exitCode() != 0) {
            throw new TaskFailure("DIFF_PREVIEW_FAILED", "Unable to generate checkpoint diff preview: " + truncate(result.output()));
        }
        return new DiffPreview(path, untracked ? "NEW" : "MODIFIED", result.output(), result.outputTruncated());
    }

    private String currentGitBranch(Path worktree, Duration timeout) {
        ProcessResult result = runner.run(worktree, List.of("git", "branch", "--show-current"), timeout);
        if (result.timedOut() || result.outputTruncated() || result.exitCode() != 0) {
            throw new TaskFailure("DIFF_PREVIEW_BRANCH_FAILED", "Unable to identify the currently checked out branch");
        }
        return result.output().strip();
    }

    private VerifierOutcome process(Path worktree, VerifierSpec spec, Duration timeout) {
        List<String> originalCommand = List.copyOf(spec.command());
        ProcessCommandPolicy.Normalization normalization = ProcessCommandPolicy.normalizeMavenCommand(originalCommand);
        if (normalization.failure() != null) {
            throw new TaskFailure("VERIFIER_COMMAND_INVALID",
                    "PROCESS command[" + normalization.failure().index() + "]: " + normalization.failure().message());
        }
        List<String> declaredCommand = normalization.command();
        requireDirectExecutable(declaredCommand);
        if ("TEST".equals(spec.processPurpose())) {
            ProcessCommandPolicy.TestCommandAssessment test = ProcessCommandPolicy.assessTestCommand(declaredCommand);
            if (!test.recognized()) {
                throw new TaskFailure("VERIFIER_TEST_COMMAND_INVALID",
                        "PROCESS TEST requires a recognized Maven, Gradle, or npm test invocation");
            }
            if (test.skipped()) {
                throw new TaskFailure("VERIFIER_TESTS_SKIPPED",
                        "PROCESS TEST must not disable or skip tests, or ignore missing target tests");
            }
        }
        ResolvedProcessCommand resolved = resolveProcessCommand(worktree, declaredCommand);
        ProcessResult result;
        try {
            result = runner.run(worktree, resolved.argv(), timeout);
        } catch (TaskFailure startFailure) {
            if (usesMavenWrapper(declaredCommand) && !resolved.fallback()
                    && "PROCESS_START_FAILED".equals(startFailure.code())) {
                resolved = mavenFallback(worktree, declaredCommand, "MAVEN_WRAPPER_START_FAILED", startFailure);
                result = runMavenFallback(worktree, resolved.argv(), timeout, startFailure);
            } else if ((resolved.fallback() || usesSystemMaven(declaredCommand))
                    && "PROCESS_START_FAILED".equals(startFailure.code())) {
                throw mavenUnavailable(startFailure);
            } else {
                throw startFailure;
            }
        }
        boolean outputMatched = spec.outputContains() == null || result.output().contains(spec.outputContains());
        boolean passed = !result.timedOut() && !result.outputTruncated() && result.exitCode() == 0 && outputMatched;
        String summary = result.timedOut() ? "Process verifier timed out"
                : result.outputTruncated() ? "Process verifier output exceeded the safe limit"
                : result.exitCode() != 0 ? "Process exited " + result.exitCode()
                : !outputMatched ? "Process output did not contain required text: " + spec.outputContains()
                : "Process exited 0";
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("workingDirectory", canonicalWorkingDirectory(worktree));
        evidence.put("argv", resolved.argv());
        if (normalization.changed()) {
            evidence.put("declaredArgv", originalCommand);
            evidence.put("commandNormalization", "MAVEN_ARGUMENTS_SPLIT");
        }
        if (resolved.fallback()) {
            evidence.putIfAbsent("declaredArgv", declaredCommand);
            evidence.put("commandResolution", resolved.commandReason());
        }
        if (resolved.executableReason() != null) {
            evidence.putIfAbsent("declaredArgv", declaredCommand);
            evidence.put("executableResolution", resolved.executableReason());
        }
        evidence.put("exitCode", result.exitCode());
        evidence.put("timedOut", result.timedOut());
        evidence.put("outputTruncated", result.outputTruncated());
        evidence.put("output", truncate(result.output()));
        if (spec.outputContains() != null) {
            evidence.put("outputContains", spec.outputContains());
            evidence.put("outputMatched", outputMatched);
        }
        return new VerifierOutcome("PROCESS", passed ? VerificationState.PASS : VerificationState.FAIL,
                summary, evidence);
    }

    private String canonicalWorkingDirectory(Path worktree) {
        try {
            return worktree.toRealPath().toString();
        } catch (java.io.IOException unavailable) {
            throw new TaskFailure("WORKTREE_UNAVAILABLE",
                    "Verifier working directory is unavailable: " + unavailable.getMessage());
        }
    }

    private ResolvedProcessCommand resolveProcessCommand(Path worktree, List<String> declaredCommand) {
        if (usesMavenWrapper(declaredCommand)) {
            try {
                ExecutableResolver.Resolution platform = runner.resolve(worktree, declaredCommand);
                if (platform.changed()) {
                    return new ResolvedProcessCommand(platform.argv(), null, platform.reason());
                }
            } catch (TaskFailure unavailable) {
                if (!"PROCESS_COMMAND_UNAVAILABLE".equals(unavailable.code())) throw unavailable;
                return mavenFallback(worktree, declaredCommand,
                        "MAVEN_WRAPPER_UNAVAILABLE_IN_WORKTREE", unavailable);
            }
            Path wrapper = worktree.resolve("mvnw").normalize();
            if (!wrapper.equals(worktree.resolve("mvnw")) || !Files.isRegularFile(wrapper) || !Files.isExecutable(wrapper)) {
                return mavenFallback(worktree, declaredCommand,
                        "MAVEN_WRAPPER_UNAVAILABLE_IN_WORKTREE", null);
            }
        }
        try {
            ExecutableResolver.Resolution platform = runner.resolve(worktree, declaredCommand);
            return new ResolvedProcessCommand(platform.argv(), null, platform.reason());
        } catch (TaskFailure unavailable) {
            if (usesSystemMaven(declaredCommand) && "PROCESS_COMMAND_UNAVAILABLE".equals(unavailable.code())) {
                throw mavenUnavailable(unavailable);
            }
            throw unavailable;
        }
    }

    private ProcessResult runMavenFallback(Path worktree, List<String> fallbackCommand, Duration timeout,
                                           TaskFailure wrapperFailure) {
        try {
            return runner.run(worktree, fallbackCommand, timeout);
        } catch (TaskFailure fallbackFailure) {
            if ("PROCESS_START_FAILED".equals(fallbackFailure.code())) throw mavenUnavailable(wrapperFailure);
            throw fallbackFailure;
        }
    }

    private TaskFailure mavenUnavailable(TaskFailure cause) {
        return new TaskFailure("MAVEN_COMMAND_UNAVAILABLE",
                "Maven could not be resolved or started for direct argv execution. On Windows ensure mvn.cmd is on the "
                        + "Loopper process PATH; on Linux/macOS ensure mvn is executable. Cause: "
                        + (cause == null ? "no platform Maven executable was found" : cause.getMessage()));
    }

    private ResolvedProcessCommand mavenFallback(Path worktree, List<String> declaredCommand, String reason,
                                                  TaskFailure wrapperFailure) {
        List<String> fallback = new ArrayList<>(declaredCommand);
        fallback.set(0, "mvn");
        try {
            ExecutableResolver.Resolution platform = runner.resolve(worktree, List.copyOf(fallback));
            return new ResolvedProcessCommand(platform.argv(), reason, platform.reason());
        } catch (TaskFailure unavailable) {
            if ("PROCESS_COMMAND_UNAVAILABLE".equals(unavailable.code())) {
                throw mavenUnavailable(wrapperFailure == null ? unavailable : wrapperFailure);
            }
            throw unavailable;
        }
    }

    private boolean usesMavenWrapper(List<String> command) {
        return command != null && !command.isEmpty() && ProcessCommandPolicy.isMavenWrapper(command.getFirst());
    }

    private boolean usesSystemMaven(List<String> command) {
        return command != null && !command.isEmpty() && ProcessCommandPolicy.isSystemMaven(command.getFirst());
    }

    private record ResolvedProcessCommand(List<String> argv, String commandReason, String executableReason) {
        private boolean fallback() { return commandReason != null; }
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
        String error = ProcessCommandPolicy.directCommandError(command);
        if (error != null) throw new TaskFailure(error.contains("shell") ? "VERIFIER_SHELL_FORBIDDEN" : "VERIFIER_COMMAND_INVALID", error);
    }

    private VerifierOutcome file(Path worktree, VerifierSpec spec, boolean expected) {
        Path target = managedRelative(worktree, spec.path());
        boolean actual = Files.exists(target);
        boolean passed = expected == actual;
        return new VerifierOutcome(expected ? "FILE_EXISTS" : "FILE_NOT_EXISTS", passed ? VerificationState.PASS : VerificationState.FAIL,
                (expected ? "Expected file to exist: " : "Expected file not to exist: ") + spec.path(),
                Map.of("path", target.toString(), "exists", actual));
    }

    /**
     * FILE_EXISTS used to be a hard gate. In practice it duplicated PROCESS
     * self-checks while coupling otherwise-correct work to a Designer-guessed
     * output path. Keep evaluating legacy specs for audit visibility, but never
     * send an implementation back through the retry loop solely for this hint.
     */
    private VerifierOutcome advisoryFileExists(Path worktree, VerifierSpec spec) {
        Path target = managedRelative(worktree, spec.path());
        boolean actual = Files.exists(target);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("path", target.toString());
        evidence.put("exists", actual);
        evidence.put("blocking", false);
        String summary = actual
                ? "Optional file found: " + spec.path()
                : "Optional file not found (non-blocking): " + spec.path();
        return new VerifierOutcome("FILE_EXISTS", VerificationState.PASS, summary, evidence);
    }

    private VerifierOutcome gitDiff(Path worktree, String baseline, VerifierSpec spec, Duration timeout) {
        if (baseline == null || baseline.isBlank()) {
            throw new TaskFailure("GIT_BASELINE_MISSING", "GIT_DIFF verifier requires a workspace baseline");
        }
        ProcessResult result;
        ProcessResult untrackedResult;
        String baselineScope = "TASK";
        String stageId = null;
        if (baseline.startsWith(StageWorkspaceBaselineManager.PREFIX)) {
            if (stageBaselines == null) {
                throw new TaskFailure("STAGE_WORKSPACE_BASELINE_UNAVAILABLE",
                        "Stage workspace diff support is unavailable");
            }
            StageWorkspaceBaselineManager.DiffResult diff = stageBaselines.diff(worktree, baseline, timeout);
            result = diff.tracked();
            untrackedResult = diff.untracked();
            baselineScope = "STAGE";
            stageId = stageBaselines.stageId(baseline);
        } else if (baseline.startsWith(DirectWorkspaceBaselineManager.PREFIX)) {
            if (directBaselines == null) throw new TaskFailure("DIRECT_BASELINE_UNAVAILABLE", "Direct-execution diff support is unavailable");
            DirectWorkspaceBaselineManager.DiffResult diff = directBaselines.diff(worktree, baseline, timeout);
            result = diff.tracked();
            untrackedResult = diff.untracked();
        } else {
            result = runner.run(worktree, List.of("git", "diff", "--name-status", "-z", baseline), timeout);
            untrackedResult = runner.run(worktree, List.of("git", "ls-files", "-z", "--others", "--exclude-standard"), timeout);
        }
        if (result.outputTruncated()) {
            throw new TaskFailure("GIT_DIFF_OUTPUT_TRUNCATED", "Git diff exceeded the safe evidence limit");
        }
        if (result.timedOut() || result.exitCode() != 0) {
            return new VerifierOutcome("GIT_DIFF", VerificationState.ERROR, "Unable to inspect Git diff",
                    Map.of("exitCode", result.exitCode(), "output", truncate(result.output())));
        }
        if (untrackedResult.outputTruncated()) {
            throw new TaskFailure("GIT_DIFF_OUTPUT_TRUNCATED", "Untracked-file evidence exceeded the safe limit");
        }
        if (untrackedResult.timedOut() || untrackedResult.exitCode() != 0) {
            return new VerifierOutcome("GIT_DIFF", VerificationState.ERROR, "Unable to inspect untracked files",
                    Map.of("exitCode", untrackedResult.exitCode(), "output", truncate(untrackedResult.output())));
        }
        List<GitChange> changes = gitChanges(result.output());
        List<String> changed = changes.stream().flatMap(change -> policyPaths(change).stream()).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        List<String> untracked = paths(untrackedResult.output());
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
            for (GitChange change : changes) {
                if (change.kind() == 'D') violations.add("deletion: " + change.paths().getLast());
                if (change.kind() == 'R') violations.add("rename removes source path: " + change.paths().getFirst());
            }
        }
        boolean requiresChanges = Boolean.TRUE.equals(spec.requireChanges());
        if (requiresChanges && changed.isEmpty()) violations.add("expected a Git diff, but no files changed");
        boolean passed = violations.isEmpty();
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("baseline", baseline);
        evidence.put("baselineScope", baselineScope);
        if (stageId != null) evidence.put("stageId", stageId);
        evidence.put("changedPaths", changed);
        evidence.put("untrackedPaths", untracked);
        evidence.put("violations", violations);
        return new VerifierOutcome("GIT_DIFF", passed ? VerificationState.PASS : VerificationState.FAIL,
                passed ? "Git diff satisfies policy" : String.join("; ", violations), evidence);
    }

    private Path managedRelative(Path worktree, String input) {
        return VerifierSafety.managedRelative(worktree, input);
    }
    private List<GitChange> gitChanges(String output) {
        List<GitChange> changes = new ArrayList<>();
        if (output.indexOf('\0') >= 0) {
            String[] fields = output.split("\\x00", -1);
            int index = 0;
            while (index < fields.length && !fields[index].isEmpty()) {
                String status = fields[index++];
                int pathCount = renameOrCopy(status) ? 2 : 1;
                if (index + pathCount > fields.length) invalidGitDiff();
                List<String> changePaths = new ArrayList<>(pathCount);
                for (int pathIndex = 0; pathIndex < pathCount; pathIndex++) {
                    String path = fields[index++];
                    if (path.isEmpty()) invalidGitDiff();
                    changePaths.add(path);
                }
                changes.add(new GitChange(status, List.copyOf(changePaths)));
            }
            return List.copyOf(changes);
        }
        for (String line : output.lines().toList()) {
            if (line.isBlank()) continue;
            String[] fields = line.split("\\t", -1);
            int pathCount = fields.length == 0 || !renameOrCopy(fields[0]) ? 1 : 2;
            if (fields.length != pathCount + 1) invalidGitDiff();
            changes.add(new GitChange(fields[0], List.of(fields).subList(1, fields.length)));
        }
        return List.copyOf(changes);
    }

    private List<String> paths(String output) {
        if (output.indexOf('\0') >= 0) {
            return java.util.Arrays.stream(output.split("\\x00", -1)).filter(path -> !path.isEmpty()).toList();
        }
        return output.lines().filter(path -> !path.isBlank()).toList();
    }

    private List<String> policyPaths(GitChange change) {
        return change.kind() == 'R' ? change.paths() : List.of(change.paths().getLast());
    }

    private boolean renameOrCopy(String status) {
        return status != null && !status.isBlank() && (status.charAt(0) == 'R' || status.charAt(0) == 'C');
    }

    private void invalidGitDiff() {
        throw new TaskFailure("GIT_DIFF_OUTPUT_INVALID", "Git diff returned malformed path evidence");
    }

    private record GitChange(String status, List<String> paths) {
        private char kind() { return status.charAt(0); }
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
        try {
            return VerifierPathPolicy.matches(path, inputRule, budget);
        } catch (SlashGlobMatcher.WorkLimitExceeded exhausted) {
            throw new TaskFailure("VERIFIER_PATH_POLICY_LIMIT_EXCEEDED", "Verifier path policy exceeded its bounded matching budget");
        } catch (VerifierPathPolicy.InvalidRule invalidRule) {
            throw new TaskFailure("VERIFIER_PATH_POLICY_INVALID", "Verifier path policy exceeds its safety limit");
        } catch (VerifierPathPolicy.InvalidPattern invalidPattern) {
            throw new TaskFailure("VERIFIER_PATH_PATTERN_INVALID", "Invalid verifier path pattern: " + inputRule);
        }
    }
    private String truncate(String value) { return value == null ? "" : value.substring(0, Math.min(value.length(), 10_000)); }

    public record DiffPreview(String path, String changeType, String patch, boolean truncated) { }
}
