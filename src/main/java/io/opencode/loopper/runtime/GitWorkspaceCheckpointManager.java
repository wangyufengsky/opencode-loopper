package io.opencode.loopper.runtime;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.TaskFailure;
import io.opencode.loopper.runtime.GitWorktreeManager.DirtyFile;
import io.opencode.loopper.runtime.GitWorktreeManager.DirtyWorkspace;
import io.opencode.loopper.runtime.GitWorktreeManager.WorkspaceCheckpoint;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Owns immutable recovery checkpoint creation, verification, restore, and materialization. */
final class GitWorkspaceCheckpointManager {
    private static final Duration INSPECTION_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration MUTATION_TIMEOUT = Duration.ofMinutes(2);
    private final SafeProcessRunner runner;
    private final LoopperProperties properties;
    private final GitDirtyWorkspaceManager dirtyWorkspaces;

    GitWorkspaceCheckpointManager(SafeProcessRunner runner, LoopperProperties properties,
                                  GitDirtyWorkspaceManager dirtyWorkspaces) {
        this.runner = runner;
        this.properties = properties;
        this.dirtyWorkspaces = dirtyWorkspaces;
    }

    synchronized WorkspaceCheckpoint freeze(Path projectRoot, String taskId, String cycleId,
                                             String expectedBranch) {
        Path index = null;
        try {
            Path root = requireRepositoryRoot(projectRoot);
            DirtyWorkspace before = dirtyWorkspaces.inspect(root);
            if (!expectedBranch.equals(before.branch())) {
                throw new TaskFailure("RECOVERY_CHECKPOINT_BRANCH_MISMATCH",
                        "Registered checkout is not on the expected Task branch");
            }
            String checkpointRef = "refs/loopper/checkpoints/" + taskId + "/" + cycleId;
            WorkspaceCheckpoint recovered = recoverCleanCheckpoint(root, before, checkpointRef, taskId, cycleId);
            if (recovered != null) return recovered;
            index = temporaryIndex("checkpoint-");
            Map<String, String> environment = checkpointEnvironment(index);
            requireSuccess(runner.run(root, List.of("git", "read-tree", "HEAD"), INSPECTION_TIMEOUT, environment),
                    "RECOVERY_CHECKPOINT_CREATE_FAILED", "Unable to initialize the checkpoint index");
            requireSuccess(runner.run(root, List.of("git", "add", "-A", "--", "."), MUTATION_TIMEOUT, environment),
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
            String stashCommit = cleanWithRecoveryStash(root, before, taskId, cycleId);
            if (!dirtyWorkspaces.inspect(root).clean()) {
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
            deleteIndex(index);
        }
    }

    synchronized DirtyWorkspace restore(Path projectRoot, String taskBranch, String sourceBranch,
                                        String baselineCommit, String checkpointRef,
                                        String checkpointCommit, String checkpointTree) {
        Path index = null;
        try {
            Path root = requireRepositoryRoot(projectRoot);
            DirtyWorkspace source = dirtyWorkspaces.inspect(root);
            if (!source.clean()) {
                throw new TaskFailure("RECOVERY_RESTORE_WORKSPACE_DIRTY",
                        "Registered checkout changed while the Task was waiting for a decision");
            }
            restoreTaskBranch(root, source.branch(), taskBranch, sourceBranch, baselineCommit);
            verifyCheckpointRef(root, checkpointRef, checkpointCommit, checkpointTree);
            runRequired(root, List.of("git", "read-tree", "--reset", "-u", checkpointTree),
                    "RECOVERY_CHECKPOINT_RESTORE_FAILED", "Unable to materialize the recovery checkpoint");
            runRequired(root, List.of("git", "reset", "--mixed", "HEAD"),
                    "RECOVERY_CHECKPOINT_RESTORE_FAILED", "Unable to restore checkpoint changes as uncommitted files");
            index = temporaryIndex("verify-");
            String restoredTree = workspaceTree(root, index,
                    "RECOVERY_CHECKPOINT_RESTORE_FAILED", "Unable to verify restored checkpoint");
            if (!checkpointTree.equals(restoredTree)) {
                throw new TaskFailure("RECOVERY_CHECKPOINT_RESTORE_MISMATCH",
                        "Restored workspace does not match the immutable checkpoint tree");
            }
            return dirtyWorkspaces.inspect(root);
        } catch (TaskFailure failure) {
            throw failure;
        } catch (Exception failure) {
            throw new TaskFailure("RECOVERY_CHECKPOINT_RESTORE_FAILED",
                    "Unable to restore the Task checkpoint: " + failure.getMessage());
        } finally {
            deleteIndex(index);
        }
    }

    synchronized boolean matches(Path projectRoot, String expectedBranch, String checkpointRef,
                                 String checkpointCommit, String checkpointTree) {
        Path index = null;
        try {
            Path root = requireRepositoryRoot(projectRoot);
            if (!expectedBranch.equals(dirtyWorkspaces.inspect(root).branch())) return false;
            verifyCheckpointRef(root, checkpointRef, checkpointCommit, checkpointTree);
            index = temporaryIndex("match-");
            return checkpointTree.equals(workspaceTree(root, index, "RECOVERY_CHECKPOINT_RESTORE_FAILED",
                    "Unable to verify checkpoint recovery"));
        } catch (TaskFailure failure) {
            throw failure;
        } catch (Exception failure) {
            throw new TaskFailure("RECOVERY_CHECKPOINT_RESTORE_FAILED",
                    "Unable to verify the restored Task checkpoint: " + failure.getMessage());
        } finally {
            deleteIndex(index);
        }
    }

    synchronized DirtyWorkspace materialize(Path projectRoot, String expectedBranch, String checkpointTree) {
        try {
            Path root = requireRepositoryRoot(projectRoot);
            DirtyWorkspace before = dirtyWorkspaces.inspect(root);
            if (!before.clean() || !expectedBranch.equals(before.branch())) {
                throw new TaskFailure("RECOVERY_SEED_WORKSPACE_MISMATCH",
                        "Derived Task branch is not clean or is not currently checked out");
            }
            requireSuccess(runner.run(root,
                            List.of("git", "cat-file", "-e", checkpointTree + "^{tree}"), INSPECTION_TIMEOUT),
                    "RECOVERY_CHECKPOINT_MISSING", "Inherited checkpoint tree is unavailable");
            runRequired(root, List.of("git", "read-tree", "--reset", "-u", checkpointTree),
                    "RECOVERY_SEED_APPLY_FAILED", "Unable to materialize inherited Task changes");
            runRequired(root, List.of("git", "reset", "--mixed", "HEAD"),
                    "RECOVERY_SEED_APPLY_FAILED", "Unable to expose inherited changes as uncommitted files");
            return dirtyWorkspaces.inspect(root);
        } catch (TaskFailure failure) {
            throw failure;
        } catch (Exception failure) {
            throw new TaskFailure("RECOVERY_SEED_APPLY_FAILED",
                    "Unable to materialize inherited Task changes: " + failure.getMessage());
        }
    }

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
        DirtyWorkspace frozen = new DirtyWorkspace(current.branch(), current.head(),
                sha256(checkpointRef + '\0' + commit + '\0' + tree), checkpointFiles(root, parent, commit));
        return new WorkspaceCheckpoint(frozen, checkpointRef, commit, tree, recoveryStash(root, taskId, cycleId));
    }

    private List<DirtyFile> checkpointFiles(Path root, String baseline, String checkpointCommit) {
        ProcessResult result = runner.run(root,
                List.of("git", "diff", "--name-status", "-z", "--find-renames", baseline, checkpointCommit, "--"),
                INSPECTION_TIMEOUT);
        requireSuccess(result, "RECOVERY_CHECKPOINT_INTEGRITY_MISMATCH",
                "Unable to reconstruct the recovery checkpoint manifest");
        String[] tokens = result.output().split(String.valueOf('\0'), -1);
        List<DirtyFile> files = new ArrayList<>();
        for (int index = 0; index < tokens.length && !tokens[index].isEmpty();) {
            String status = tokens[index++];
            char kind = status.charAt(0);
            if ((kind == 'R' || kind == 'C') && index + 1 < tokens.length) {
                String original = tokens[index++];
                files.add(new DirtyFile(tokens[index++], original, String.valueOf(kind), " ", false));
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

    private String cleanWithRecoveryStash(Path root, DirtyWorkspace before, String taskId, String cycleId) {
        if (before.clean()) return null;
        runRequired(root, List.of("git", "stash", "push", "--include-untracked", "--message",
                        "loopper-recovery:" + taskId + ":" + cycleId),
                "RECOVERY_CHECKPOINT_CLEAN_FAILED", "Unable to clean the Task workspace after checkpointing");
        return requiredOutput(root, List.of("git", "rev-parse", "refs/stash"),
                "RECOVERY_CHECKPOINT_CLEAN_FAILED", "Unable to record the recovery stash");
    }

    private void restoreTaskBranch(Path root, String current, String taskBranch, String sourceBranch,
                                   String baselineCommit) {
        if (taskBranch.equals(current)) return;
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

    private void verifyCheckpointRef(Path root, String ref, String expectedCommit, String expectedTree) {
        String actualCommit = requiredOutput(root, List.of("git", "rev-parse", ref + "^{commit}"),
                "RECOVERY_CHECKPOINT_MISSING", "Recovery checkpoint ref is unavailable");
        String actualTree = requiredOutput(root, List.of("git", "rev-parse", ref + "^{tree}"),
                "RECOVERY_CHECKPOINT_MISSING", "Recovery checkpoint tree is unavailable");
        if (!expectedCommit.equals(actualCommit) || !expectedTree.equals(actualTree)) {
            throw new TaskFailure("RECOVERY_CHECKPOINT_INTEGRITY_MISMATCH",
                    "Recovery checkpoint ref no longer matches its persisted commit and tree");
        }
    }

    private Path temporaryIndex(String prefix) throws java.io.IOException {
        Path indexes = properties.getDataDir().toAbsolutePath().normalize().resolve("recovery-indexes");
        Files.createDirectories(indexes);
        Path index = Files.createTempFile(indexes, prefix, ".index");
        Files.deleteIfExists(index);
        return index;
    }

    private Map<String, String> checkpointEnvironment(Path index) {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("GIT_INDEX_FILE", index.toString());
        environment.put("GIT_AUTHOR_NAME", "OpenCode Loopper");
        environment.put("GIT_AUTHOR_EMAIL", "loopper@localhost.invalid");
        environment.put("GIT_COMMITTER_NAME", "OpenCode Loopper");
        environment.put("GIT_COMMITTER_EMAIL", "loopper@localhost.invalid");
        return environment;
    }

    private String workspaceTree(Path root, Path index, String code, String message) {
        Map<String, String> environment = Map.of("GIT_INDEX_FILE", index.toString());
        requireSuccess(runner.run(root, List.of("git", "read-tree", "HEAD"), INSPECTION_TIMEOUT, environment),
                code, message + " index");
        requireSuccess(runner.run(root, List.of("git", "add", "-A", "--", "."), MUTATION_TIMEOUT, environment),
                code, message + " files");
        return requiredOutput(root, List.of("git", "write-tree"), environment, code, message + " tree");
    }

    private Path requireRepositoryRoot(Path projectRoot) throws java.io.IOException {
        Path root = projectRoot.toRealPath();
        String topLevel = requiredOutput(root, List.of("git", "rev-parse", "--show-toplevel"),
                "SOURCE_BRANCH_REPOSITORY_REQUIRED", "The registered checkout must be a Git repository root");
        if (!Path.of(topLevel).toRealPath().equals(root)) {
            throw new TaskFailure("SOURCE_BRANCH_REPOSITORY_ROOT_REQUIRED",
                    "The registered checkout must be the Git repository root");
        }
        return root;
    }

    private void runRequired(Path root, List<String> command, String code, String message) {
        requireSuccess(runner.run(root, command, MUTATION_TIMEOUT), code, message);
    }

    private String requiredOutput(Path root, List<String> command, String code, String message) {
        return requiredOutput(root, command, Map.of(), code, message);
    }

    private String requiredOutput(Path root, List<String> command, Map<String, String> environment,
                                  String code, String message) {
        ProcessResult result = runner.run(root, command, INSPECTION_TIMEOUT, environment);
        requireSuccess(result, code, message);
        if (result.output().isBlank()) throw new TaskFailure(code, message);
        return result.output().strip();
    }

    private String optionalOutput(Path root, List<String> command) {
        ProcessResult result = runner.run(root, command, INSPECTION_TIMEOUT);
        if (result.timedOut() || result.outputTruncated()) {
            throw new TaskFailure("WORKTREE_GIT_INSPECTION_FAILED",
                    "Git inspection timed out or exceeded its output limit");
        }
        return result.exitCode() == 0 && !result.output().isBlank() ? result.output().strip() : null;
    }

    private void requireSuccess(ProcessResult result, String code, String message) {
        if (result.timedOut()) throw new TaskFailure(code, message + " (timed out)");
        if (result.outputTruncated()) throw new TaskFailure(code, message + " (output exceeded the safety limit)");
        if (result.exitCode() != 0) {
            String detail = trim(result.output());
            throw new TaskFailure(code, detail.isBlank() ? message : message + ": " + detail);
        }
    }

    private void deleteIndex(Path index) {
        if (index == null) return;
        try { Files.deleteIfExists(index); } catch (Exception ignored) { }
        try { Files.deleteIfExists(index.resolveSibling(index.getFileName() + ".lock")); } catch (Exception ignored) { }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private String trim(String value) {
        if (value == null) return "";
        return value.substring(Math.max(0, value.length() - 2_000)).strip();
    }
}
