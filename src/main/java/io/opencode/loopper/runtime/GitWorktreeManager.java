package io.opencode.loopper.runtime;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.TaskFailure;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class GitWorktreeManager {
    public static final String DIRECT_BRANCH = "DIRECT";
    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration GIT_MUTATION_TIMEOUT = Duration.ofMinutes(2);
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
            if (!inspectDirtyWorkspace(root).clean()) {
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

    /**
     * Freezes every tracked, deleted and untracked change into an immutable private ref, then
     * creates a named stash so the registered checkout can safely return to its source branch.
     * The Task branch itself is never moved and the private ref is outside normal push refspecs.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public synchronized WorkspaceCheckpoint freezeWorkspace(Path projectRoot, String taskId, String cycleId,
                                                             String expectedBranch) {
        Path index = null;
        try {
            Path root = requireRepositoryRoot(projectRoot);
            DirtyWorkspace before = inspectDirtyWorkspace(root);
            if (!expectedBranch.equals(before.branch())) {
                throw new TaskFailure("RECOVERY_CHECKPOINT_BRANCH_MISMATCH",
                        "Registered checkout is not on the expected Task branch");
            }
            String checkpointRef = "refs/loopper/checkpoints/" + taskId + "/" + cycleId;
            WorkspaceCheckpoint recovered = recoverCleanCheckpoint(root, before, checkpointRef, taskId, cycleId);
            if (recovered != null) return recovered;
            Path indexes = properties.getDataDir().toAbsolutePath().normalize().resolve("recovery-indexes");
            Files.createDirectories(indexes);
            index = Files.createTempFile(indexes, "checkpoint-", ".index");
            Files.deleteIfExists(index);
            Map<String, String> environment = new LinkedHashMap<>();
            environment.put("GIT_INDEX_FILE", index.toString());
            environment.put("GIT_AUTHOR_NAME", "OpenCode Loopper");
            environment.put("GIT_AUTHOR_EMAIL", "loopper@localhost.invalid");
            environment.put("GIT_COMMITTER_NAME", "OpenCode Loopper");
            environment.put("GIT_COMMITTER_EMAIL", "loopper@localhost.invalid");
            requireSuccess(runner.run(root, List.of("git", "read-tree", "HEAD"), GIT_TIMEOUT, environment),
                    "RECOVERY_CHECKPOINT_CREATE_FAILED", "Unable to initialize the checkpoint index");
            requireSuccess(runner.run(root, List.of("git", "add", "-A", "--", "."), GIT_MUTATION_TIMEOUT, environment),
                    "RECOVERY_CHECKPOINT_CREATE_FAILED", "Unable to index the Task workspace");
            String tree = requiredOutput(root, List.of("git", "write-tree"), environment,
                    "RECOVERY_CHECKPOINT_CREATE_FAILED", "Unable to write the checkpoint tree");
            String head = requiredOutput(root, List.of("git", "rev-parse", "HEAD"),
                    "RECOVERY_CHECKPOINT_CREATE_FAILED", "Unable to resolve the Task branch HEAD");
            String commit = requiredOutput(root,
                    List.of("git", "commit-tree", tree, "-p", head, "-m", "Loopper private recovery checkpoint"),
                    environment, "RECOVERY_CHECKPOINT_CREATE_FAILED", "Unable to create the checkpoint commit");
            runRequired(root, List.of("git", "update-ref", checkpointRef, commit),
                    "RECOVERY_CHECKPOINT_CREATE_FAILED", "Unable to persist the private checkpoint ref");

            String stashCommit = null;
            if (!before.clean()) {
                runRequired(root, List.of("git", "stash", "push", "--include-untracked", "--message",
                                "loopper-recovery:" + taskId + ":" + cycleId),
                        "RECOVERY_CHECKPOINT_CLEAN_FAILED", "Unable to clean the Task workspace after checkpointing");
                stashCommit = requiredOutput(root, List.of("git", "rev-parse", "refs/stash"),
                        "RECOVERY_CHECKPOINT_CLEAN_FAILED", "Unable to record the recovery stash");
            }
            DirtyWorkspace after = inspectDirtyWorkspace(root);
            if (!after.clean()) {
                throw new TaskFailure("RECOVERY_CHECKPOINT_CLEAN_UNCONFIRMED",
                        "The Task workspace is still dirty after checkpointing");
            }
            return new WorkspaceCheckpoint(before, checkpointRef, commit, tree, stashCommit);
        } catch (TaskFailure failure) {
            throw failure;
        } catch (Exception failure) {
            throw new TaskFailure("RECOVERY_CHECKPOINT_CREATE_FAILED",
                    "Unable to freeze the Task workspace: " + failure.getMessage());
        } finally {
            if (index != null) {
                try { Files.deleteIfExists(index); } catch (Exception ignored) { }
                try { Files.deleteIfExists(index.resolveSibling(index.getFileName() + ".lock")); } catch (Exception ignored) { }
            }
        }
    }

    /** Completes the database side of a freeze that crashed after its ref and stash were durable. */
    private WorkspaceCheckpoint recoverCleanCheckpoint(Path root, DirtyWorkspace current, String checkpointRef,
                                                        String taskId, String cycleId) {
        if (!current.clean()) return null;
        String commit = optionalOutput(root, List.of("git", "rev-parse", "--verify", checkpointRef + "^{commit}"));
        if (commit == null) return null;
        String tree = requiredOutput(root, List.of("git", "rev-parse", checkpointRef + "^{tree}"),
                "RECOVERY_CHECKPOINT_MISSING", "Recovery checkpoint tree is unavailable");
        String parent = requiredOutput(root, List.of("git", "rev-parse", commit + "^"),
                "RECOVERY_CHECKPOINT_INTEGRITY_MISMATCH", "Recovery checkpoint parent is unavailable");
        if (!current.head().equals(parent)) {
            throw new TaskFailure("RECOVERY_CHECKPOINT_INTEGRITY_MISMATCH",
                    "Existing recovery checkpoint was created from a different Task branch HEAD");
        }
        List<DirtyFile> files = checkpointFiles(root, parent, commit);
        DirtyWorkspace frozen = new DirtyWorkspace(current.branch(), current.head(),
                sha256(checkpointRef + '\0' + commit + '\0' + tree), files);
        String stash = recoveryStash(root, taskId, cycleId);
        return new WorkspaceCheckpoint(frozen, checkpointRef, commit, tree, stash);
    }

    private List<DirtyFile> checkpointFiles(Path root, String baseline, String checkpointCommit) {
        ProcessResult result = runner.run(root,
                List.of("git", "diff", "--name-status", "-z", "--find-renames", baseline, checkpointCommit, "--"),
                GIT_TIMEOUT);
        requireSuccess(result, "RECOVERY_CHECKPOINT_INTEGRITY_MISMATCH",
                "Unable to reconstruct the recovery checkpoint manifest");
        String[] tokens = result.output().split(String.valueOf('\0'), -1);
        List<DirtyFile> files = new ArrayList<>();
        for (int index = 0; index < tokens.length && !tokens[index].isEmpty();) {
            String status = tokens[index++];
            char kind = status.charAt(0);
            if ((kind == 'R' || kind == 'C') && index + 1 < tokens.length) {
                String original = tokens[index++];
                String path = tokens[index++];
                files.add(new DirtyFile(path, original, String.valueOf(kind), " ", false));
            } else if (index < tokens.length) {
                files.add(new DirtyFile(tokens[index++], null, String.valueOf(kind), " ", false));
            }
        }
        return List.copyOf(files);
    }

    private String recoveryStash(Path root, String taskId, String cycleId) {
        String output = optionalOutput(root, List.of("git", "stash", "list", "--format=%H%x09%gs"));
        if (output == null) return null;
        String marker = "loopper-recovery:" + taskId + ":" + cycleId;
        return output.lines().map(String::strip).filter(line -> line.endsWith(marker))
                .map(line -> line.split("\\t", 2)[0]).findFirst().orElse(null);
    }

    /** Restores a verified private checkpoint as uncommitted changes on the unchanged Task branch. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public synchronized DirtyWorkspace restoreWorkspaceCheckpoint(Path projectRoot, String taskBranch,
                                                                  String sourceBranch, String baselineCommit,
                                                                  String checkpointRef, String checkpointCommit,
                                                                  String checkpointTree) {
        Path index = null;
        try {
            Path root = requireRepositoryRoot(projectRoot);
            DirtyWorkspace source = inspectDirtyWorkspace(root);
            if (!source.clean()) {
                throw new TaskFailure("RECOVERY_RESTORE_WORKSPACE_DIRTY",
                        "Registered checkout changed while the Task was waiting for a decision");
            }
            String current = source.branch();
            if (!taskBranch.equals(current)) {
                if (sourceBranch != null && !sourceBranch.isBlank() && !sourceBranch.equals(current)) {
                    throw new TaskFailure("RECOVERY_RESTORE_SOURCE_BRANCH_MISMATCH",
                            "Registered checkout is no longer on the recorded source branch");
                }
                String taskHead = requiredOutput(root, List.of("git", "rev-parse", "refs/heads/" + taskBranch),
                        "RECOVERY_RESTORE_TASK_BRANCH_MISSING", "Task branch is unavailable");
                if (!baselineCommit.equals(taskHead)) {
                    throw new TaskFailure("RECOVERY_RESTORE_TASK_BRANCH_MOVED",
                            "Task branch moved after the workspace checkpoint was frozen");
                }
                runRequired(root, List.of("git", "-c", "core.longpaths=true", "switch", taskBranch),
                        "RECOVERY_RESTORE_SWITCH_FAILED", "Unable to switch back to the Task branch");
            }
            String actualCommit = requiredOutput(root, List.of("git", "rev-parse", checkpointRef + "^{commit}"),
                    "RECOVERY_CHECKPOINT_MISSING", "Recovery checkpoint ref is unavailable");
            String actualTree = requiredOutput(root, List.of("git", "rev-parse", checkpointRef + "^{tree}"),
                    "RECOVERY_CHECKPOINT_MISSING", "Recovery checkpoint tree is unavailable");
            if (!checkpointCommit.equals(actualCommit) || !checkpointTree.equals(actualTree)) {
                throw new TaskFailure("RECOVERY_CHECKPOINT_INTEGRITY_MISMATCH",
                        "Recovery checkpoint ref no longer matches its persisted commit and tree");
            }
            runRequired(root, List.of("git", "read-tree", "--reset", "-u", checkpointTree),
                    "RECOVERY_CHECKPOINT_RESTORE_FAILED", "Unable to materialize the recovery checkpoint");
            runRequired(root, List.of("git", "reset", "--mixed", "HEAD"),
                    "RECOVERY_CHECKPOINT_RESTORE_FAILED", "Unable to restore checkpoint changes as uncommitted files");

            Path indexes = properties.getDataDir().toAbsolutePath().normalize().resolve("recovery-indexes");
            Files.createDirectories(indexes);
            index = Files.createTempFile(indexes, "verify-", ".index");
            Files.deleteIfExists(index);
            Map<String, String> environment = Map.of("GIT_INDEX_FILE", index.toString());
            requireSuccess(runner.run(root, List.of("git", "read-tree", "HEAD"), GIT_TIMEOUT, environment),
                    "RECOVERY_CHECKPOINT_RESTORE_FAILED", "Unable to initialize checkpoint verification");
            requireSuccess(runner.run(root, List.of("git", "add", "-A", "--", "."), GIT_MUTATION_TIMEOUT, environment),
                    "RECOVERY_CHECKPOINT_RESTORE_FAILED", "Unable to verify restored checkpoint files");
            String restoredTree = requiredOutput(root, List.of("git", "write-tree"), environment,
                    "RECOVERY_CHECKPOINT_RESTORE_FAILED", "Unable to verify restored checkpoint tree");
            if (!checkpointTree.equals(restoredTree)) {
                throw new TaskFailure("RECOVERY_CHECKPOINT_RESTORE_MISMATCH",
                        "Restored workspace does not match the immutable checkpoint tree");
            }
            return inspectDirtyWorkspace(root);
        } catch (TaskFailure failure) {
            throw failure;
        } catch (Exception failure) {
            throw new TaskFailure("RECOVERY_CHECKPOINT_RESTORE_FAILED",
                    "Unable to restore the Task checkpoint: " + failure.getMessage());
        } finally {
            if (index != null) {
                try { Files.deleteIfExists(index); } catch (Exception ignored) { }
                try { Files.deleteIfExists(index.resolveSibling(index.getFileName() + ".lock")); } catch (Exception ignored) { }
            }
        }
    }

    /**
     * Proves that the current named-branch workspace materializes exactly the persisted checkpoint tree.
     * This is used only to finish a RESTORING saga after a process crash; it performs no workspace write.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public synchronized boolean workspaceMatchesCheckpointTree(Path projectRoot, String expectedBranch,
                                                               String checkpointRef, String checkpointCommit,
                                                               String checkpointTree) {
        Path index = null;
        try {
            Path root = requireRepositoryRoot(projectRoot);
            DirtyWorkspace current = inspectDirtyWorkspace(root);
            if (!expectedBranch.equals(current.branch())) return false;
            String actualCommit = requiredOutput(root, List.of("git", "rev-parse", checkpointRef + "^{commit}"),
                    "RECOVERY_CHECKPOINT_MISSING", "Recovery checkpoint ref is unavailable");
            String actualTree = requiredOutput(root, List.of("git", "rev-parse", checkpointRef + "^{tree}"),
                    "RECOVERY_CHECKPOINT_MISSING", "Recovery checkpoint tree is unavailable");
            if (!checkpointCommit.equals(actualCommit) || !checkpointTree.equals(actualTree)) {
                throw new TaskFailure("RECOVERY_CHECKPOINT_INTEGRITY_MISMATCH",
                        "Recovery checkpoint ref no longer matches its persisted commit and tree");
            }
            Path indexes = properties.getDataDir().toAbsolutePath().normalize().resolve("recovery-indexes");
            Files.createDirectories(indexes);
            index = Files.createTempFile(indexes, "match-", ".index");
            Files.deleteIfExists(index);
            Map<String, String> environment = Map.of("GIT_INDEX_FILE", index.toString());
            requireSuccess(runner.run(root, List.of("git", "read-tree", "HEAD"), GIT_TIMEOUT, environment),
                    "RECOVERY_CHECKPOINT_RESTORE_FAILED", "Unable to initialize checkpoint recovery verification");
            requireSuccess(runner.run(root, List.of("git", "add", "-A", "--", "."), GIT_MUTATION_TIMEOUT, environment),
                    "RECOVERY_CHECKPOINT_RESTORE_FAILED", "Unable to verify checkpoint recovery files");
            String workspaceTree = requiredOutput(root, List.of("git", "write-tree"), environment,
                    "RECOVERY_CHECKPOINT_RESTORE_FAILED", "Unable to verify checkpoint recovery tree");
            return checkpointTree.equals(workspaceTree);
        } catch (TaskFailure failure) {
            throw failure;
        } catch (Exception failure) {
            throw new TaskFailure("RECOVERY_CHECKPOINT_RESTORE_FAILED",
                    "Unable to verify the restored Task checkpoint: " + failure.getMessage());
        } finally {
            if (index != null) {
                try { Files.deleteIfExists(index); } catch (Exception ignored) { }
                try { Files.deleteIfExists(index.resolveSibling(index.getFileName() + ".lock")); } catch (Exception ignored) { }
            }
        }
    }

    /** Applies an already verified parent checkpoint tree to a newly created child Task branch. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public synchronized DirtyWorkspace materializeCheckpointTree(Path projectRoot, String expectedBranch,
                                                                 String checkpointTree) {
        try {
            Path root = requireRepositoryRoot(projectRoot);
            DirtyWorkspace before = inspectDirtyWorkspace(root);
            if (!before.clean() || !expectedBranch.equals(before.branch())) {
                throw new TaskFailure("RECOVERY_SEED_WORKSPACE_MISMATCH",
                        "Derived Task branch is not clean or is not currently checked out");
            }
            requireSuccess(runner.run(root, List.of("git", "cat-file", "-e", checkpointTree + "^{tree}"), GIT_TIMEOUT),
                    "RECOVERY_CHECKPOINT_MISSING", "Inherited checkpoint tree is unavailable");
            runRequired(root, List.of("git", "read-tree", "--reset", "-u", checkpointTree),
                    "RECOVERY_SEED_APPLY_FAILED", "Unable to materialize inherited Task changes");
            runRequired(root, List.of("git", "reset", "--mixed", "HEAD"),
                    "RECOVERY_SEED_APPLY_FAILED", "Unable to expose inherited changes as uncommitted files");
            return inspectDirtyWorkspace(root);
        } catch (TaskFailure failure) {
            throw failure;
        } catch (Exception failure) {
            throw new TaskFailure("RECOVERY_SEED_APPLY_FAILED",
                    "Unable to materialize inherited Task changes: " + failure.getMessage());
        }
    }

    /** Returns a path-safe, NUL-delimited snapshot of every tracked or untracked source-checkout change. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public DirtyWorkspace inspectDirtyWorkspace(Path projectRoot) {
        try {
            Path root = requireRepositoryRoot(projectRoot);
            String branch = requiredOutput(root, List.of("git", "symbolic-ref", "--quiet", "--short", "HEAD"),
                    "SOURCE_BRANCH_UNAVAILABLE", "The registered checkout must use a named branch");
            String head = requiredOutput(root, List.of("git", "rev-parse", "HEAD"),
                    "SOURCE_BRANCH_REPOSITORY_REQUIRED", "The registered checkout must have a valid HEAD");
            ProcessResult result = runner.run(root,
                    List.of("git", "status", "--porcelain=v1", "-z", "--untracked-files=all"), GIT_TIMEOUT);
            requireSuccess(result, "SOURCE_BRANCH_STATUS_FAILED",
                    "Unable to list uncommitted files in the registered source checkout");
            List<DirtyFile> files = parseDirtyFiles(result.output());
            StringBuilder fingerprint = new StringBuilder(branch).append('\0').append(head).append('\0');
            for (DirtyFile file : files) {
                fingerprint.append(file.indexStatus()).append(file.workTreeStatus()).append('\0')
                        .append(file.path()).append('\0').append(file.originalPath() == null ? "" : file.originalPath()).append('\0');
                for (String path : mutationPaths(file)) {
                    fingerprint.append(path).append('\0')
                            .append(fileFingerprint(root, path)).append('\0')
                            .append(indexFingerprint(root, path)).append('\0');
                }
            }
            return new DirtyWorkspace(branch, head, sha256(fingerprint.toString()), files);
        } catch (TaskFailure failure) {
            throw failure;
        } catch (Exception failure) {
            throw new TaskFailure("SOURCE_BRANCH_STATUS_FAILED",
                    "Unable to inspect the registered source checkout: " + failure.getMessage());
        }
    }

    /** Applies the user's complete per-file decision against an unchanged Git snapshot. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public synchronized DirtyWorkspace resolveDirtyWorkspace(Path projectRoot, String expectedSnapshot,
                                                              List<DirtyFileResolution> resolutions,
                                                              String commitMessage) {
        Path root = requireRepositoryRoot(projectRoot);
        DirtyWorkspace before = inspectDirtyWorkspace(root);
        if (expectedSnapshot == null || !expectedSnapshot.equals(before.snapshotId())) {
            throw new TaskFailure("SOURCE_BRANCH_WORKSPACE_CHANGED",
                    "The uncommitted file list changed after the dialog was opened; refresh it before applying actions");
        }
        Map<String, DirtyFile> current = new LinkedHashMap<>();
        before.files().forEach(file -> current.put(file.path(), file));
        Map<String, DirtyFileAction> selected = new LinkedHashMap<>();
        if (resolutions != null) {
            for (DirtyFileResolution resolution : resolutions) {
                if (resolution == null || resolution.path() == null || resolution.action() == null
                        || selected.putIfAbsent(resolution.path(), resolution.action()) != null) {
                    throw new TaskFailure("SOURCE_BRANCH_WORKSPACE_RESOLUTION_INVALID",
                            "Each dirty file requires exactly one valid action");
                }
            }
        }
        if (!current.keySet().equals(selected.keySet())) {
            throw new TaskFailure("SOURCE_BRANCH_WORKSPACE_RESOLUTION_INCOMPLETE",
                    "Choose commit, stash, or remove for every currently dirty file");
        }

        List<DirtyFile> commit = selectedFiles(current, selected, DirtyFileAction.COMMIT);
        List<DirtyFile> stash = selectedFiles(current, selected, DirtyFileAction.STASH);
        List<DirtyFile> remove = selectedFiles(current, selected, DirtyFileAction.REMOVE);
        if (!commit.isEmpty()) commitDirtyFiles(root, commit, commitMessage);
        if (!stash.isEmpty()) stashDirtyFiles(root, stash);
        for (DirtyFile file : remove) removeDirtyFile(root, file);
        return inspectDirtyWorkspace(root);
    }

    private Path requireRepositoryRoot(Path projectRoot) {
        try {
            Path root = projectRoot.toRealPath();
            String topLevel = requiredOutput(root, List.of("git", "rev-parse", "--show-toplevel"),
                    "SOURCE_BRANCH_REPOSITORY_REQUIRED", "The registered checkout must be a Git repository root");
            if (!Path.of(topLevel).toRealPath().equals(root)) {
                throw new TaskFailure("SOURCE_BRANCH_REPOSITORY_ROOT_REQUIRED",
                        "The registered checkout must be the Git repository root");
            }
            return root;
        } catch (TaskFailure failure) {
            throw failure;
        } catch (Exception failure) {
            throw new TaskFailure("SOURCE_BRANCH_REPOSITORY_REQUIRED",
                    "Unable to resolve the registered Git repository root: " + failure.getMessage());
        }
    }

    private List<DirtyFile> parseDirtyFiles(String output) {
        List<DirtyFile> files = new ArrayList<>();
        String[] records = output.split("\u0000", -1);
        for (int index = 0; index < records.length; index++) {
            String record = records[index];
            if (record.isEmpty()) continue;
            if (record.length() < 4 || record.charAt(2) != ' ') {
                throw new TaskFailure("SOURCE_BRANCH_STATUS_INVALID", "Git returned an unreadable dirty-file record");
            }
            String indexStatus = String.valueOf(record.charAt(0));
            String workTreeStatus = String.valueOf(record.charAt(1));
            String path = record.substring(3);
            String originalPath = null;
            if ("RC".contains(indexStatus) || "RC".contains(workTreeStatus)) {
                if (++index >= records.length || records[index].isEmpty()) {
                    throw new TaskFailure("SOURCE_BRANCH_STATUS_INVALID", "Git returned an incomplete rename record");
                }
                originalPath = records[index];
            }
            files.add(new DirtyFile(path, originalPath, indexStatus, workTreeStatus,
                    "?".equals(indexStatus) && "?".equals(workTreeStatus)));
        }
        return List.copyOf(files);
    }

    private List<DirtyFile> selectedFiles(Map<String, DirtyFile> current, Map<String, DirtyFileAction> selected,
                                          DirtyFileAction action) {
        return current.values().stream().filter(file -> action == selected.get(file.path())).toList();
    }

    private void commitDirtyFiles(Path root, List<DirtyFile> files, String message) {
        String normalized = message == null ? "" : message.strip();
        if (normalized.isEmpty() || normalized.length() > 160 || normalized.contains("\n") || normalized.contains("\r")) {
            throw new TaskFailure("SOURCE_BRANCH_COMMIT_MESSAGE_INVALID",
                    "A single-line commit message of 1 to 160 characters is required for files marked commit");
        }
        List<String> paths = allMutationPaths(files);
        runRequired(root, command("git", "add", "--", paths), "SOURCE_BRANCH_COMMIT_STAGE_FAILED",
                "Unable to stage the selected source files");
        List<String> commit = new ArrayList<>(List.of("git", "commit", "--only", "-m", normalized, "--"));
        commit.addAll(paths);
        runRequired(root, commit, "SOURCE_BRANCH_COMMIT_FAILED", "Unable to commit the selected source files");
    }

    private void stashDirtyFiles(Path root, List<DirtyFile> files) {
        List<String> command = new ArrayList<>(List.of("git", "stash", "push", "--include-untracked", "--message",
                "Loopper pre-task cleanup " + Instant.now(), "--"));
        command.addAll(allMutationPaths(files));
        runRequired(root, command, "SOURCE_BRANCH_STASH_FAILED", "Unable to stash the selected source files");
    }

    private void removeDirtyFile(Path root, DirtyFile file) {
        List<String> paths = mutationPaths(file);
        if (file.untracked() || (file.originalPath() == null && "A".equals(file.indexStatus()))) {
            List<String> reset = new ArrayList<>(List.of("git", "reset", "--quiet", "HEAD", "--"));
            reset.addAll(paths);
            runRequired(root, reset, "SOURCE_BRANCH_REMOVE_FAILED", "Unable to unstage the selected added file");
            for (String path : paths) deleteContainedFile(root, path);
            return;
        }
        List<String> restore = new ArrayList<>(List.of("git", "restore", "--source=HEAD", "--staged", "--worktree", "--"));
        restore.addAll(paths);
        runRequired(root, restore, "SOURCE_BRANCH_REMOVE_FAILED", "Unable to discard the selected tracked-file changes");
    }

    private void deleteContainedFile(Path root, String path) {
        Path target = root.resolve(path).normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            throw new TaskFailure("SOURCE_BRANCH_PATH_ESCAPE", "Dirty-file action escaped the registered repository");
        }
        try {
            if (Files.isDirectory(target)) {
                throw new TaskFailure("SOURCE_BRANCH_REMOVE_DIRECTORY_DENIED",
                        "Loopper will not recursively remove an untracked directory");
            }
            Files.deleteIfExists(target);
        } catch (TaskFailure failure) {
            throw failure;
        } catch (Exception failure) {
            throw new TaskFailure("SOURCE_BRANCH_REMOVE_FAILED",
                    "Unable to remove selected file " + path + ": " + failure.getMessage());
        }
    }

    private List<String> mutationPaths(DirtyFile file) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        paths.add(file.path());
        if (file.originalPath() != null
                && ("R".equals(file.indexStatus()) || "R".equals(file.workTreeStatus()))) {
            paths.add(file.originalPath());
        }
        return List.copyOf(paths);
    }

    private List<String> allMutationPaths(List<DirtyFile> files) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        files.forEach(file -> paths.addAll(mutationPaths(file)));
        return List.copyOf(paths);
    }

    private List<String> command(String first, String second, String third, List<String> paths) {
        List<String> command = new ArrayList<>(List.of(first, second, third));
        command.addAll(paths);
        return command;
    }

    private String fileFingerprint(Path root, String path) {
        ProcessResult hash = runner.run(root, List.of("git", "hash-object", "--no-filters", "--", path), GIT_TIMEOUT);
        if (hash.timedOut() || hash.outputTruncated()) {
            throw new TaskFailure("SOURCE_BRANCH_STATUS_FAILED", "Unable to fingerprint dirty file " + path);
        }
        return hash.exitCode() == 0 ? hash.output().strip() : "<missing>";
    }

    private String indexFingerprint(Path root, String path) {
        ProcessResult index = runner.run(root, List.of("git", "ls-files", "--stage", "-z", "--", path), GIT_TIMEOUT);
        requireSuccess(index, "SOURCE_BRANCH_STATUS_FAILED", "Unable to fingerprint the Git index for " + path);
        return index.output();
    }

    private void runRequired(Path root, List<String> command, String code, String message) {
        ProcessResult result = runner.run(root, command, GIT_MUTATION_TIMEOUT);
        requireSuccess(result, code, message);
    }

    private String requiredOutput(Path root, List<String> command, String code, String message) {
        ProcessResult result = runner.run(root, command, GIT_TIMEOUT);
        requireSuccess(result, code, message);
        if (result.output().isBlank()) throw new TaskFailure(code, message);
        return result.output().strip();
    }

    private String requiredOutput(Path root, List<String> command, Map<String, String> environment,
                                  String code, String message) {
        ProcessResult result = runner.run(root, command, GIT_TIMEOUT, environment);
        requireSuccess(result, code, message);
        if (result.output().isBlank()) throw new TaskFailure(code, message);
        return result.output().strip();
    }

    private void requireSuccess(ProcessResult result, String code, String message) {
        if (result.timedOut()) throw new TaskFailure(code, message + " (timed out)");
        if (result.outputTruncated()) throw new TaskFailure(code, message + " (output exceeded the safety limit)");
        if (result.exitCode() != 0) {
            String detail = trim(result.output());
            throw new TaskFailure(code, detail.isBlank() ? message : message + ": " + detail);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
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
    public record WorkspaceCheckpoint(DirtyWorkspace workspace, String checkpointRef, String checkpointCommit,
                                      String checkpointTree, String stashCommit) { }
    public enum DirtyFileAction { COMMIT, STASH, REMOVE }
    public record DirtyFile(String path, String originalPath, String indexStatus, String workTreeStatus,
                            boolean untracked) { }
    public record DirtyWorkspace(String branch, String head, String snapshotId, List<DirtyFile> files) {
        public boolean clean() { return files == null || files.isEmpty(); }
    }
    public record DirtyFileResolution(String path, DirtyFileAction action) { }
    public record RepositoryInspection(boolean pathAvailable, boolean isolatedWorktree, String branch) {
        private static RepositoryInspection direct() { return new RepositoryInspection(true, false, null); }
        private static RepositoryInspection unavailable() { return new RepositoryInspection(false, false, null); }
    }
}
