package io.opencode.loopper.runtime;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.TaskFailure;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class GitWorktreeManager {
    public static final String DIRECT_BRANCH = "DIRECT";
    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(30);
    static final Duration WORKTREE_CREATE_TIMEOUT = Duration.ofMinutes(10);
    private static final String BRANCH_NAMESPACE = "loopper/";
    private static final int MAX_BRANCH_LEAF_BYTES = 180;
    private static final int MAX_BRANCH_OCCURRENCES = 10_000;
    private final SafeProcessRunner runner;
    private final LoopperProperties properties;
    private final DirectWorkspaceBaselineManager directBaselines;

    public GitWorktreeManager(SafeProcessRunner runner, LoopperProperties properties,
                              DirectWorkspaceBaselineManager directBaselines) {
        this.runner = runner;
        this.properties = properties;
        this.directBaselines = directBaselines;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Worktree create(Path projectRoot, String taskId) {
        return create(projectRoot, taskId, taskId, null);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Worktree create(Path projectRoot, String taskId, String taskName, String requestedBaseline) {
        try {
            Path root = projectRoot.toRealPath();
            ProcessResult repository = runner.run(root, List.of("git", "rev-parse", "--is-inside-work-tree"), GIT_TIMEOUT);
            if (repository.timedOut() || repository.outputTruncated() || repository.exitCode() != 0
                    || !"true".equals(repository.output().trim())) {
                if (requestedBaseline != null) throw new TaskFailure("REWORK_REPOSITORY_REQUIRED", "Rework requires an isolated Git repository");
                return direct(root, taskId);
            }
            ProcessResult topLevel = runner.run(root, List.of("git", "rev-parse", "--show-toplevel"), GIT_TIMEOUT);
            if (topLevel.timedOut() || topLevel.outputTruncated() || topLevel.exitCode() != 0 || topLevel.output().isBlank()) {
                if (requestedBaseline != null) throw new TaskFailure("REWORK_REPOSITORY_REQUIRED", "Rework requires an isolated Git repository");
                return direct(root, taskId);
            }
            Path repositoryRoot;
            try { repositoryRoot = Path.of(topLevel.output().trim()).toRealPath(); }
            catch (Exception invalidTopLevel) {
                if (requestedBaseline != null) throw new TaskFailure("REWORK_REPOSITORY_REQUIRED", "Rework requires an isolated Git repository");
                return direct(root, taskId);
            }
            if (!repositoryRoot.equals(root)) {
                if (requestedBaseline != null) throw new TaskFailure("REWORK_REPOSITORY_REQUIRED", "Rework requires the registered Git repository root");
                return direct(root, taskId);
            }
            ProcessResult head = runner.run(root, List.of("git", "rev-parse", "HEAD"), GIT_TIMEOUT);
            if (head.timedOut() || head.outputTruncated() || head.exitCode() != 0) {
                if (requestedBaseline != null) throw new TaskFailure("REWORK_BASELINE_UNAVAILABLE", "Rework baseline cannot be resolved");
                return direct(root, taskId);
            }
            String baseline = head.output().trim();
            String sourceBranch = optionalOutput(root, List.of("git", "symbolic-ref", "--quiet", "--short", "HEAD"));
            if (requestedBaseline != null) {
                ProcessResult verified = runner.run(root,
                        List.of("git", "rev-parse", "--verify", requestedBaseline + "^{commit}"), GIT_TIMEOUT);
                if (verified.timedOut() || verified.outputTruncated() || verified.exitCode() != 0 || verified.output().isBlank()) {
                    throw new TaskFailure("REWORK_BASELINE_UNAVAILABLE", "The parent task baseline is no longer available in Git");
                }
                baseline = verified.output().trim();
            } else {
                baseline = refreshRemoteBaseline(root, baseline);
            }
            Path base = properties.getDataDir().toAbsolutePath().normalize().resolve("worktrees");
            Files.createDirectories(base);
            base = base.toRealPath();
            Path worktree = base.resolve(taskId).normalize();
            if (!worktree.getParent().equals(base)) throw new TaskFailure("WORKTREE_PATH_INVALID", "Task worktree escaped its managed directory");
            if (worktree.startsWith(root) || root.startsWith(worktree)) {
                throw new TaskFailure("WORKTREE_DATA_DIR_OVERLAP",
                        "Loopper data worktrees must be outside the registered project root");
            }
            if (Files.exists(worktree)) throw new TaskFailure("WORKTREE_ALREADY_EXISTS", "A managed worktree already exists for task " + taskId);
            for (int occurrence = 1; occurrence <= MAX_BRANCH_OCCURRENCES; occurrence++) {
                String branch = branchNameForTask(taskName, taskId, occurrence);
                if (branchExists(root, branch)) continue;
                ProcessResult added = runner.run(root,
                        List.of("git", "-c", "core.longpaths=true", "worktree", "add", "--quiet",
                                "-b", branch, worktree.toString(), baseline), WORKTREE_CREATE_TIMEOUT);
                if (added.timedOut()) {
                    throw new TaskFailure("WORKTREE_CREATE_FAILED",
                            "Git worktree checkout exceeded the 10-minute safety limit. "
                                    + "Check disk/antivirus performance and remove any incomplete managed worktree before retrying.");
                }
                if (added.outputTruncated()) {
                    throw new TaskFailure("WORKTREE_CREATE_FAILED",
                            "Git worktree checkout exceeded the process output safety limit: " + trim(added.output()));
                }
                if (added.exitCode() != 0) {
                    // Branch creation is atomic. If another Task won the same name concurrently,
                    // continue with the next occurrence while the managed path is still untouched.
                    if (!Files.exists(worktree) && branchExists(root, branch)) continue;
                    throw new TaskFailure("WORKTREE_CREATE_FAILED", trim(added.output()));
                }
                Path resolved = worktree.toRealPath();
                if (!resolved.startsWith(base.toRealPath())) {
                    throw new TaskFailure("WORKTREE_ESCAPE", "Created worktree did not remain inside the managed worktree directory");
                }
                return new Worktree(resolved, branch, baseline, sourceBranch);
            }
            throw new TaskFailure("WORKTREE_BRANCH_EXHAUSTED", "Too many isolated branches already use this task name");
        } catch (TaskFailure e) {
            throw e;
        } catch (Exception e) {
            throw new TaskFailure("WORKTREE_CREATE_FAILED", "Unable to create isolated worktree: " + e.getMessage());
        }
    }

    /**
     * Creates and checks out a Task branch in the registered repository itself.
     *
     * <p>This mode is intentionally serialized by the workspace lease coordinator:
     * IDE-bound tools such as AgentBridge always operate on the checkout opened by
     * IDEA, so the registered checkout must be the authoritative Task workspace.</p>
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Worktree checkoutSourceBranch(Path projectRoot, String taskId, String taskName, String requestedBaseline) {
        try {
            Path root = projectRoot.toRealPath();
            ProcessResult topLevel = runner.run(root, List.of("git", "rev-parse", "--show-toplevel"), GIT_TIMEOUT);
            ProcessResult head = runner.run(root, List.of("git", "rev-parse", "HEAD"), GIT_TIMEOUT);
            if (topLevel.timedOut() || topLevel.outputTruncated() || topLevel.exitCode() != 0
                    || head.timedOut() || head.outputTruncated() || head.exitCode() != 0) {
                throw new TaskFailure("SOURCE_BRANCH_REPOSITORY_REQUIRED",
                        "Source-branch execution requires a Git repository root with a valid HEAD");
            }
            Path repositoryRoot = Path.of(topLevel.output().trim()).toRealPath();
            if (!repositoryRoot.equals(root)) {
                throw new TaskFailure("SOURCE_BRANCH_REPOSITORY_ROOT_REQUIRED",
                        "Source-branch execution requires the registered Git repository root");
            }
            String status = optionalOutput(root,
                    List.of("git", "status", "--porcelain=v1", "--untracked-files=all"));
            if (status != null) {
                throw new TaskFailure("SOURCE_BRANCH_WORKSPACE_DIRTY",
                        "The registered source checkout has uncommitted or untracked files; commit, stash, or remove them before switching to a Task branch");
            }
            String sourceBranch = optionalOutput(root, List.of("git", "symbolic-ref", "--quiet", "--short", "HEAD"));
            if (sourceBranch == null || sourceBranch.startsWith(BRANCH_NAMESPACE)) {
                throw new TaskFailure("SOURCE_BRANCH_UNAVAILABLE",
                        "The registered checkout must start from a named non-Task branch");
            }
            String baseline = head.output().trim();
            if (requestedBaseline != null) {
                ProcessResult verified = runner.run(root,
                        List.of("git", "rev-parse", "--verify", requestedBaseline + "^{commit}"), GIT_TIMEOUT);
                if (verified.timedOut() || verified.outputTruncated() || verified.exitCode() != 0 || verified.output().isBlank()) {
                    throw new TaskFailure("REWORK_BASELINE_UNAVAILABLE", "The parent task baseline is no longer available in Git");
                }
                baseline = verified.output().trim();
            } else {
                baseline = refreshRemoteBaseline(root, baseline);
            }
            for (int occurrence = 1; occurrence <= MAX_BRANCH_OCCURRENCES; occurrence++) {
                String branch = branchNameForTask(taskName, taskId, occurrence);
                if (branchExists(root, branch)) continue;
                ProcessResult switched = runner.run(root,
                        List.of("git", "-c", "core.longpaths=true", "switch", "--create", branch, baseline),
                        WORKTREE_CREATE_TIMEOUT);
                if (switched.timedOut()) {
                    throw new TaskFailure("SOURCE_BRANCH_CHECKOUT_FAILED",
                            "Switching the registered source checkout to the Task branch exceeded the 10-minute safety limit");
                }
                if (switched.outputTruncated() || switched.exitCode() != 0) {
                    if (branchExists(root, branch)) {
                        throw new TaskFailure("SOURCE_BRANCH_CHECKOUT_FAILED", trim(switched.output()));
                    }
                    continue;
                }
                requireSourceBranch(root, branch, baseline);
                return new Worktree(root, branch, baseline, sourceBranch);
            }
            throw new TaskFailure("WORKTREE_BRANCH_EXHAUSTED", "Too many source branches already use this task name");
        } catch (TaskFailure failure) {
            throw failure;
        } catch (Exception failure) {
            throw new TaskFailure("SOURCE_BRANCH_CHECKOUT_FAILED",
                    "Unable to switch the registered source checkout to a Task branch: " + failure.getMessage());
        }
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RepositoryInspection inspect(Path projectRoot) {
        try {
            Path root = projectRoot.toRealPath();
            if (!Files.isDirectory(root)) return RepositoryInspection.unavailable();
            ProcessResult result = runner.run(root,
                    List.of("git", "rev-parse", "--is-inside-work-tree", "--show-toplevel", "HEAD", "--abbrev-ref", "HEAD"),
                    Duration.ofSeconds(3));
            if (result.timedOut() || result.outputTruncated() || result.exitCode() != 0) {
                return RepositoryInspection.direct();
            }
            String[] lines = result.output().lines().map(String::trim).filter(line -> !line.isEmpty()).toArray(String[]::new);
            if (lines.length < 4 || !"true".equals(lines[0])) return RepositoryInspection.direct();
            Path repositoryRoot;
            try { repositoryRoot = Path.of(lines[1]).toRealPath(); }
            catch (Exception invalidTopLevel) { return RepositoryInspection.direct(); }
            if (!repositoryRoot.equals(root)) return RepositoryInspection.direct();
            String head = lines[2];
            String branch = "HEAD".equals(lines[3]) ? "detached@" + head.substring(0, Math.min(12, head.length())) : lines[3];
            return new RepositoryInspection(true, true, branch);
        } catch (Exception unavailable) {
            return RepositoryInspection.unavailable();
        }
    }

    public void requireManaged(Path worktree) {
        try {
            Path base = properties.getDataDir().toAbsolutePath().normalize().resolve("worktrees").toRealPath();
            Path resolved = worktree.toRealPath();
            if (!resolved.startsWith(base)) throw new TaskFailure("WORKTREE_ESCAPE", "Task operation attempted outside its managed worktree");
        } catch (TaskFailure e) {
            throw e;
        } catch (Exception e) {
            throw new TaskFailure("WORKTREE_UNAVAILABLE", "Task worktree is not available: " + e.getMessage());
        }
    }

    public void requireExecutionWorkspace(Path executionPath, Path projectRoot, String branch, String baseline) {
        try {
            Path expected = projectRoot.toRealPath();
            Path actual = executionPath.toRealPath();
            if (!DIRECT_BRANCH.equals(branch) && actual.equals(expected)) {
                requireSourceBranch(actual, branch, baseline);
                return;
            }
            if (!DIRECT_BRANCH.equals(branch)) {
                requireManaged(actual);
                return;
            }
            if (!actual.equals(expected)) {
                throw new TaskFailure("DIRECT_WORKSPACE_MISMATCH", "Direct task operation must use the registered project root");
            }
            directBaselines.requireAvailable(baseline);
        } catch (TaskFailure failure) {
            throw failure;
        } catch (Exception exception) {
            throw new TaskFailure("DIRECT_WORKSPACE_UNAVAILABLE", "Direct project directory is not available: " + exception.getMessage());
        }
    }

    /**
     * Returns whether the registered Git checkout still contains unpublished file changes.
     * A caller uses this before releasing the serialized in-place writer lease; inability to
     * prove a clean checkout fails closed instead of allowing another Task to switch branches.
     */
    public boolean sourceCheckoutHasChanges(Path projectRoot) {
        try {
            Path root = projectRoot.toRealPath();
            ProcessResult result = runner.run(root,
                    List.of("git", "status", "--porcelain=v1", "--untracked-files=all"), GIT_TIMEOUT);
            if (result.timedOut() || result.outputTruncated() || result.exitCode() != 0) {
                throw new TaskFailure("SOURCE_BRANCH_STATUS_FAILED",
                        "Unable to confirm that the registered source checkout is clean");
            }
            return !result.output().isBlank();
        } catch (TaskFailure failure) {
            throw failure;
        } catch (Exception failure) {
            throw new TaskFailure("SOURCE_BRANCH_STATUS_FAILED",
                    "Unable to inspect the registered source checkout: " + failure.getMessage());
        }
    }

    /** Restores a clean registered checkout after its Task changes have been committed to the Task branch. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void restoreSourceBranch(Path projectRoot, String taskBranch, String recordedSourceBranch) {
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
            if (!taskBranch.equals(current)) {
                throw new TaskFailure("TASK_SOURCE_BRANCH_RESTORE_MISMATCH",
                        "Registered checkout is on " + (current == null ? "detached HEAD" : current)
                                + " instead of Task branch " + taskBranch);
            }
            if (sourceCheckoutHasChanges(root)) {
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

    private void requireSourceBranch(Path root, String expectedBranch, String baseline) {
        String branch = optionalOutput(root, List.of("git", "branch", "--show-current"));
        if (!expectedBranch.equals(branch)) {
            throw new TaskFailure("SOURCE_BRANCH_MISMATCH",
                    "Registered source checkout is on " + (branch == null ? "detached HEAD" : branch)
                            + " instead of Task branch " + expectedBranch);
        }
        if (baseline == null || baseline.isBlank()) {
            throw new TaskFailure("TASK_BASELINE_MISSING", "Source-branch Task has no Git baseline");
        }
        String head = optionalOutput(root, List.of("git", "rev-parse", "HEAD"));
        if (head == null || !ancestor(root, baseline, head)) {
            throw new TaskFailure("SOURCE_BRANCH_BASELINE_MISMATCH",
                    "Task branch HEAD no longer contains its recorded baseline");
        }
    }

    private Worktree direct(Path root, String taskId) {
        return new Worktree(root, DIRECT_BRANCH, directBaselines.capture(root, taskId), null);
    }

    /**
     * Refreshes the current branch's remote baseline without moving or rewriting the registered source branch.
     * A linear remote advance becomes the Task baseline; local unpublished commits remain included. Divergence is
     * fail-closed because silently choosing either history would make later publication ambiguous.
     */
    private String refreshRemoteBaseline(Path root, String localHead) {
        String branch = optionalOutput(root, List.of("git", "symbolic-ref", "--quiet", "--short", "HEAD"));
        if (branch == null) return localHead;
        List<String> remotes = remotes(root);
        if (remotes.isEmpty()) return localHead;
        String upstream = optionalOutput(root,
                List.of("git", "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{upstream}"));
        String remote = remoteForUpstream(upstream, remotes);
        if (remote == null) {
            remote = remotes.contains("origin") ? "origin" : remotes.size() == 1 ? remotes.getFirst() : null;
            if (remote == null) {
                throw new TaskFailure("WORKTREE_REMOTE_AMBIGUOUS",
                        "Multiple Git remotes exist without an upstream or origin; cannot refresh the Task baseline");
            }
            upstream = remote + "/" + branch;
        }
        ProcessResult fetched = runner.run(root, List.of("git", "fetch", "--prune", "--no-tags", remote),
                GIT_TIMEOUT, Map.of("GIT_TERMINAL_PROMPT", "0"));
        if (fetched.timedOut() || fetched.outputTruncated() || fetched.exitCode() != 0) {
            throw new TaskFailure("WORKTREE_REMOTE_FETCH_FAILED",
                    "Unable to refresh remote " + remote + " before Task isolation: " + trim(fetched.output()));
        }
        String remoteHead = optionalOutput(root, List.of("git", "rev-parse", "--verify", upstream + "^{commit}"));
        if (remoteHead == null) return localHead;
        if (ancestor(root, localHead, remoteHead)) return remoteHead;
        if (ancestor(root, remoteHead, localHead)) return localHead;
        throw new TaskFailure("WORKTREE_BASELINE_DIVERGED",
                "The current source branch and " + upstream + " have diverged; reconcile them before creating a Task");
    }

    private List<String> remotes(Path root) {
        String output = optionalOutput(root, List.of("git", "remote"));
        if (output == null) return List.of();
        return output.lines().map(String::strip).filter(value -> !value.isBlank()).toList();
    }

    private String remoteForUpstream(String upstream, List<String> remotes) {
        if (upstream == null) return null;
        return remotes.stream().filter(remote -> upstream.startsWith(remote + "/"))
                .max(java.util.Comparator.comparingInt(String::length)).orElse(null);
    }

    private boolean ancestor(Path root, String older, String newer) {
        ProcessResult result = runner.run(root, List.of("git", "merge-base", "--is-ancestor", older, newer), GIT_TIMEOUT);
        if (result.timedOut() || result.outputTruncated() || (result.exitCode() != 0 && result.exitCode() != 1)) {
            throw new TaskFailure("WORKTREE_BASELINE_COMPARE_FAILED", "Unable to compare local and remote Git baselines");
        }
        return result.exitCode() == 0;
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

    private boolean branchExists(Path root, String branch) {
        ProcessResult local = runner.run(root,
                List.of("git", "show-ref", "--verify", "--quiet", "refs/heads/" + branch), GIT_TIMEOUT);
        if (local.timedOut() || local.outputTruncated() || (local.exitCode() != 0 && local.exitCode() != 1)) {
            throw new TaskFailure("WORKTREE_BRANCH_CHECK_FAILED", "Unable to check isolated branch name availability");
        }
        if (local.exitCode() == 0) return true;
        ProcessResult remote = runner.run(root,
                List.of("git", "branch", "--remotes", "--list", "*/" + branch), GIT_TIMEOUT);
        if (remote.timedOut() || remote.outputTruncated() || remote.exitCode() != 0) {
            throw new TaskFailure("WORKTREE_BRANCH_CHECK_FAILED", "Unable to check remote-tracking branch name availability");
        }
        return !remote.output().isBlank();
    }

    private String branchName(String base, int occurrence) {
        String suffix = occurrence == 1 ? "" : "(第" + occurrence + "次)";
        int suffixBytes = suffix.getBytes(StandardCharsets.UTF_8).length;
        String leaf = validTruncatedLeaf(base, MAX_BRANCH_LEAF_BYTES - suffixBytes);
        return BRANCH_NAMESPACE + leaf + suffix;
    }

    String branchNameForTask(String taskName, String taskId, int occurrence) {
        return branchName(normalizedBranchLeaf(taskName, taskId), occurrence);
    }

    private String validTruncatedLeaf(String value, int maxBytes) {
        String leaf = trimInvalidEnding(truncateUtf8(value, maxBytes));
        if (leaf.equals("@") || leaf.isBlank()) leaf = "task";
        if (leaf.endsWith(".lock")) leaf = leaf.substring(0, leaf.length() - 5) + "-lock";
        leaf = trimInvalidEnding(truncateUtf8(leaf, maxBytes));
        return leaf.equals("@") || leaf.isBlank() ? "task" : leaf;
    }

    private String normalizedBranchLeaf(String taskName, String taskId) {
        String source = taskName == null || taskName.isBlank() ? "task-" + taskId : taskName.trim();
        source = Normalizer.normalize(source, Normalizer.Form.NFKC);
        StringBuilder normalized = new StringBuilder();
        source.codePoints().forEach(codePoint -> {
            if (codePoint <= 0x20 || codePoint == 0x7f || "~^:?*[\\/".indexOf(codePoint) >= 0) {
                normalized.append('-');
            } else {
                normalized.appendCodePoint(codePoint);
            }
        });
        String value = normalized.toString()
                .replace("@{", "-")
                .replaceAll("\\.{2,}", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^[.-]+", "");
        value = trimInvalidEnding(value);
        if (value.equals("@") || value.isBlank()) value = "task-" + taskId;
        if (value.endsWith(".lock")) value += "-branch";
        return value;
    }

    private String truncateUtf8(String value, int maxBytes) {
        StringBuilder result = new StringBuilder();
        int bytes = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            String character = new String(Character.toChars(codePoint));
            int characterBytes = character.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + characterBytes > maxBytes) break;
            result.append(character);
            bytes += characterBytes;
            offset += Character.charCount(codePoint);
        }
        return result.toString();
    }

    private String trimInvalidEnding(String value) {
        int end = value.length();
        while (end > 0) {
            char last = value.charAt(end - 1);
            if (last != '.' && last != '-' && last != '/') break;
            end--;
        }
        return value.substring(0, end);
    }

    private String trim(String value) {
        if (value == null) return "";
        int start = Math.max(0, value.length() - 2000);
        return value.substring(start).strip();
    }
    public record Worktree(Path path, String branch, String baselineCommit, String sourceBranch) { }
    public record RepositoryInspection(boolean pathAvailable, boolean isolatedWorktree, String branch) {
        private static RepositoryInspection direct() { return new RepositoryInspection(true, false, null); }
        private static RepositoryInspection unavailable() { return new RepositoryInspection(false, false, null); }
    }
}
