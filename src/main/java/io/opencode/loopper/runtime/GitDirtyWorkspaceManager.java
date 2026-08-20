package io.opencode.loopper.runtime;

import io.opencode.loopper.domain.TaskFailure;
import io.opencode.loopper.runtime.GitWorktreeManager.DirtyFile;
import io.opencode.loopper.runtime.GitWorktreeManager.DirtyFileAction;
import io.opencode.loopper.runtime.GitWorktreeManager.DirtyFileResolution;
import io.opencode.loopper.runtime.GitWorktreeManager.DirtyWorkspace;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Owns dirty-checkout snapshots and the explicit commit/stash/remove resolution policy. */
final class GitDirtyWorkspaceManager {
    private static final Duration INSPECTION_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration MUTATION_TIMEOUT = Duration.ofMinutes(2);
    private final SafeProcessRunner runner;

    GitDirtyWorkspaceManager(SafeProcessRunner runner) {
        this.runner = runner;
    }

    DirtyWorkspace inspect(Path projectRoot) {
        try {
            Path root = requireRepositoryRoot(projectRoot);
            String branch = requiredOutput(root, List.of("git", "symbolic-ref", "--quiet", "--short", "HEAD"),
                    "SOURCE_BRANCH_UNAVAILABLE", "The registered checkout must use a named branch");
            String head = requiredOutput(root, List.of("git", "rev-parse", "HEAD"),
                    "SOURCE_BRANCH_REPOSITORY_REQUIRED", "The registered checkout must have a valid HEAD");
            ProcessResult result = runner.run(root,
                    List.of("git", "status", "--porcelain=v1", "-z", "--untracked-files=all"), INSPECTION_TIMEOUT);
            requireSuccess(result, "SOURCE_BRANCH_STATUS_FAILED",
                    "Unable to list uncommitted files in the registered source checkout");
            List<DirtyFile> files = parseDirtyFiles(result.output());
            StringBuilder fingerprint = new StringBuilder(branch).append('\0').append(head).append('\0');
            for (DirtyFile file : files) {
                fingerprint.append(file.indexStatus()).append(file.workTreeStatus()).append('\0')
                        .append(file.path()).append('\0')
                        .append(file.originalPath() == null ? "" : file.originalPath()).append('\0');
                for (String path : mutationPaths(file)) {
                    fingerprint.append(path).append('\0').append(fileFingerprint(root, path)).append('\0')
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

    synchronized DirtyWorkspace resolve(Path projectRoot, String expectedSnapshot,
                                        List<DirtyFileResolution> resolutions, String commitMessage) {
        Path root = requireRepositoryRoot(projectRoot);
        DirtyWorkspace before = inspect(root);
        if (expectedSnapshot == null || !expectedSnapshot.equals(before.snapshotId())) {
            throw new TaskFailure("SOURCE_BRANCH_WORKSPACE_CHANGED",
                    "The uncommitted file list changed after the dialog was opened; refresh it before applying actions");
        }
        Map<String, DirtyFile> current = new LinkedHashMap<>();
        before.files().forEach(file -> current.put(file.path(), file));
        Map<String, DirtyFileAction> selected = selections(resolutions);
        if (!current.keySet().equals(selected.keySet())) {
            throw new TaskFailure("SOURCE_BRANCH_WORKSPACE_RESOLUTION_INCOMPLETE",
                    "Choose commit, stash, or remove for every currently dirty file");
        }
        List<DirtyFile> commit = selectedFiles(current, selected, DirtyFileAction.COMMIT);
        List<DirtyFile> stash = selectedFiles(current, selected, DirtyFileAction.STASH);
        List<DirtyFile> remove = selectedFiles(current, selected, DirtyFileAction.REMOVE);
        if (!commit.isEmpty()) commitDirtyFiles(root, commit, commitMessage);
        if (!stash.isEmpty()) stashDirtyFiles(root, stash);
        remove.forEach(file -> removeDirtyFile(root, file));
        return inspect(root);
    }

    private Map<String, DirtyFileAction> selections(List<DirtyFileResolution> resolutions) {
        Map<String, DirtyFileAction> selected = new LinkedHashMap<>();
        if (resolutions == null) return selected;
        for (DirtyFileResolution resolution : resolutions) {
            if (resolution == null || resolution.path() == null || resolution.action() == null
                    || selected.putIfAbsent(resolution.path(), resolution.action()) != null) {
                throw new TaskFailure("SOURCE_BRANCH_WORKSPACE_RESOLUTION_INVALID",
                        "Each dirty file requires exactly one valid action");
            }
        }
        return selected;
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
        if (normalized.isEmpty() || normalized.length() > 160 || normalized.contains("\n")
                || normalized.contains("\r")) {
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
            paths.forEach(path -> deleteContainedFile(root, path));
            return;
        }
        List<String> restore = new ArrayList<>(List.of(
                "git", "restore", "--source=HEAD", "--staged", "--worktree", "--"));
        restore.addAll(paths);
        runRequired(root, restore, "SOURCE_BRANCH_REMOVE_FAILED",
                "Unable to discard the selected tracked-file changes");
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
        ProcessResult hash = runner.run(root,
                List.of("git", "hash-object", "--no-filters", "--", path), INSPECTION_TIMEOUT);
        if (hash.timedOut() || hash.outputTruncated()) {
            throw new TaskFailure("SOURCE_BRANCH_STATUS_FAILED", "Unable to fingerprint dirty file " + path);
        }
        return hash.exitCode() == 0 ? hash.output().strip() : "<missing>";
    }

    private String indexFingerprint(Path root, String path) {
        ProcessResult index = runner.run(root,
                List.of("git", "ls-files", "--stage", "-z", "--", path), INSPECTION_TIMEOUT);
        requireSuccess(index, "SOURCE_BRANCH_STATUS_FAILED", "Unable to fingerprint the Git index for " + path);
        return index.output();
    }

    private void runRequired(Path root, List<String> command, String code, String message) {
        requireSuccess(runner.run(root, command, MUTATION_TIMEOUT), code, message);
    }

    private String requiredOutput(Path root, List<String> command, String code, String message) {
        ProcessResult result = runner.run(root, command, INSPECTION_TIMEOUT);
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

    private String trim(String value) {
        if (value == null) return "";
        return value.substring(Math.max(0, value.length() - 2_000)).strip();
    }
}
