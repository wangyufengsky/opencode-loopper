package io.opencode.loopper.runtime;

import io.opencode.loopper.domain.TaskFailure;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Predicate;

/** Restores only a Task-owned checkout or its recorded source, without discarding local work. */
final class GitSourceBranchRestorer {
    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration WORKTREE_CREATE_TIMEOUT = GitWorktreeManager.WORKTREE_CREATE_TIMEOUT;
    private static final String BRANCH_NAMESPACE = "loopper/";
    private final SafeProcessRunner runner;
    private final Predicate<Path> hasChanges;

    GitSourceBranchRestorer(SafeProcessRunner runner, Predicate<Path> hasChanges) {
        this.runner = runner;
        this.hasChanges = hasChanges;
    }

    /** Restores a clean registered checkout after its Task changes have been committed to the Task branch. */
    void restoreSourceBranch(Path projectRoot, String taskBranch, String recordedSourceBranch) {
        restore(projectRoot, taskBranch, recordedSourceBranch, null);
    }

    private void restore(Path projectRoot, String taskBranch, String recordedSourceBranch, String permittedSource) {
        try {
            Path root = projectRoot.toRealPath();
            String current = optionalOutput(root, List.of("git", "symbolic-ref", "--quiet", "--short", "HEAD"));
            String sourceBranch = recordedSourceBranch == null || recordedSourceBranch.isBlank()
                    ? inferHistoricalSourceBranch(root, taskBranch) : recordedSourceBranch;
            if (sourceBranch == null || sourceBranch.equals(taskBranch) || sourceBranch.startsWith(BRANCH_NAMESPACE)) {
                throw new TaskFailure("TASK_SOURCE_BRANCH_UNAVAILABLE",
                        "Task start branch is unavailable; the registered checkout was not switched");
            }
            if (sourceBranch.equals(current)) return;
            boolean atPermittedSource = permittedSource != null && !permittedSource.isBlank()
                    && !permittedSource.startsWith(BRANCH_NAMESPACE) && permittedSource.equals(current);
            if (!taskBranch.equals(current) && !atPermittedSource) {
                throw new TaskFailure("TASK_SOURCE_BRANCH_RESTORE_MISMATCH",
                        "Registered checkout is on " + (current == null ? "detached HEAD" : current)
                                + " instead of Task branch " + taskBranch + "; release target is " + sourceBranch
                                + (permittedSource == null ? "" : "; recorded source is " + permittedSource));
            }
            if (hasChanges.test(root)) {
                throw new TaskFailure("TASK_SOURCE_BRANCH_RESTORE_DIRTY",
                        "Task branch still has uncommitted files; the registered checkout was not switched");
            }
            ProcessResult switched = runner.run(root,
                    List.of("git", "-c", "core.longpaths=true", "switch", sourceBranch), WORKTREE_CREATE_TIMEOUT);
            if (switched.timedOut() || switched.outputTruncated() || switched.exitCode() != 0) {
                throw new TaskFailure("TASK_SOURCE_BRANCH_RESTORE_FAILED",
                        "Unable to restore source branch " + sourceBranch + ": " + trim(switched.output()));
            }
            String restored = optionalOutput(root, List.of("git", "symbolic-ref", "--quiet", "--short", "HEAD"));
            if (!sourceBranch.equals(restored)) {
                throw new TaskFailure("TASK_SOURCE_BRANCH_RESTORE_UNCONFIRMED",
                        "Git switch completed without restoring the recorded source branch");
            }
        } catch (TaskFailure failure) {
            throw failure;
        } catch (Exception failure) {
            throw new TaskFailure("TASK_SOURCE_BRANCH_RESTORE_FAILED",
                    "Unable to restore the Task start branch: " + failure.getMessage());
        }
    }

    /** Cancellation returns to the repository default branch without discarding files. */
    void restoreMainBranch(Path root, String taskBranch, String recordedSourceBranch) {
        String remoteHead = optionalOutput(root, List.of("git", "symbolic-ref", "--quiet", "refs/remotes/origin/HEAD"));
        java.util.LinkedHashSet<String> candidates = new java.util.LinkedHashSet<>();
        if (remoteHead != null && remoteHead.startsWith("refs/remotes/origin/")) {
            candidates.add(remoteHead.substring("refs/remotes/origin/".length()));
        }
        candidates.add("main"); candidates.add("master");
        for (String candidate : candidates) {
            if (!candidate.equals(taskBranch) && !candidate.startsWith(BRANCH_NAMESPACE)
                    && optionalOutput(root, List.of("git", "rev-parse", "--verify", "refs/heads/" + candidate)) != null) {
                restore(root, taskBranch, candidate, recordedSourceBranch);
                return;
            }
        }
        throw new TaskFailure("TASK_MAIN_BRANCH_UNAVAILABLE", "未找到本地主分支，已保留任务分支与修改快照");
    }

    private String inferHistoricalSourceBranch(Path root, String taskBranch) {
        String reflog = optionalOutput(root, List.of("git", "reflog", "--format=%gs", "-n", "100", "HEAD"));
        if (reflog == null) return null;
        String suffix = " to " + taskBranch;
        for (String line : reflog.lines().toList()) {
            String value = line.strip();
            if (!value.startsWith("checkout: moving from ") || !value.endsWith(suffix)) continue;
            return value.substring("checkout: moving from ".length(), value.length() - suffix.length());
        }
        return null;
    }

    private String optionalOutput(Path root, List<String> command) {
        ProcessResult result = runner.run(root, command, GIT_TIMEOUT);
        if (result.timedOut() || result.outputTruncated()) {
            throw new TaskFailure("WORKTREE_GIT_INSPECTION_FAILED", "Git inspection timed out or exceeded its output limit");
        }
        if (result.exitCode() != 0) return null;
        String output = result.output().strip();
        return output.isBlank() ? null : output;
    }

    private String trim(String value) {
        if (value == null) return "";
        int start = Math.max(0, value.length() - 2000);
        return value.substring(start).strip();
    }
}
