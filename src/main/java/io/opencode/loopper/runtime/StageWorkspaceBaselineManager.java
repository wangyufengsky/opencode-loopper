package io.opencode.loopper.runtime;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.TaskFailure;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Captures immutable per-Stage workspace trees in a Loopper-managed bare Git
 * repository. Every Stage has a private index while the task shares objects,
 * so predecessor files become the next Stage's baseline without touching the
 * registered project's own repository or index.
 */
@Component
public class StageWorkspaceBaselineManager {
    public static final String PREFIX = "stage:";
    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(30);
    private static final String ID_PATTERN = "[A-Za-z0-9-]{1,80}";
    private final SafeProcessRunner runner;
    private final LoopperProperties properties;

    public StageWorkspaceBaselineManager(SafeProcessRunner runner, LoopperProperties properties) {
        this.runner = runner;
        this.properties = properties;
    }

    public synchronized String capture(Path projectRoot, String taskId, String stageId) {
        requireId(taskId, "task");
        requireId(stageId, "Stage");
        try {
            Path root = projectRoot.toRealPath();
            Path base = managedBase(true);
            Path repository = managedRepository(base, taskId);
            ensureRepository(root, repository);
            Path indexes = repository.resolve("indexes").normalize();
            Files.createDirectories(indexes);
            Path index = indexes.resolve(stageId + ".index").normalize();
            if (!index.getParent().equals(indexes)) {
                throw new TaskFailure("STAGE_WORKSPACE_BASELINE_PATH_INVALID",
                        "Stage workspace baseline index escaped its managed directory");
            }

            for (int attempt = 0; attempt < 2; attempt++) {
                Files.deleteIfExists(index);
                Files.deleteIfExists(index.resolveSibling(index.getFileName() + ".lock"));
                requireSuccess(root, git(root, repository, index, addArguments(root, base.getParent())), index,
                        "STAGE_WORKSPACE_BASELINE_CREATE_FAILED",
                        "Unable to index the Stage workspace baseline");
                ProcessResult tree = runner.run(root, git(root, repository, index, List.of("write-tree")),
                        GIT_TIMEOUT, indexEnvironment(index));
                if (tree.timedOut() || tree.outputTruncated() || tree.exitCode() != 0
                        || !tree.output().trim().matches("[0-9a-fA-F]{40,64}")) {
                    throw new TaskFailure("STAGE_WORKSPACE_BASELINE_CREATE_FAILED",
                            "Unable to finalize the Stage workspace baseline: " + trim(tree.output()));
                }
                String treeId = tree.output().trim();
                DiffResult stability = diff(root, repository, index, treeId, GIT_TIMEOUT);
                requireDiffSuccess(stability.tracked(), "tracked");
                requireDiffSuccess(stability.untracked(), "untracked");
                if (stability.tracked().output().isEmpty() && stability.untracked().output().isEmpty()) {
                    return PREFIX + taskId + ":" + stageId + ":" + treeId;
                }
            }
            throw new TaskFailure("STAGE_WORKSPACE_BASELINE_UNSTABLE",
                    "Workspace changed while the Stage baseline was captured twice");
        } catch (TaskFailure failure) {
            throw failure;
        } catch (Exception exception) {
            throw new TaskFailure("STAGE_WORKSPACE_BASELINE_CREATE_FAILED",
                    "Unable to capture the Stage workspace baseline: " + exception.getMessage());
        }
    }

    public DiffResult diff(Path projectRoot, String marker, Duration timeout) {
        Baseline baseline = requireAvailableBaseline(marker);
        try {
            return diff(projectRoot.toRealPath(), baseline.repository(), baseline.index(), baseline.tree(), timeout);
        } catch (TaskFailure failure) {
            throw failure;
        } catch (Exception exception) {
            throw new TaskFailure("STAGE_WORKSPACE_BASELINE_UNAVAILABLE",
                    "Stage workspace baseline cannot be used: " + exception.getMessage());
        }
    }

    public ProcessResult patch(Path projectRoot, String marker, String path, Duration timeout) {
        Baseline baseline = requireAvailableBaseline(marker);
        try {
            Path root = projectRoot.toRealPath();
            return runner.run(root, git(root, baseline.repository(), baseline.index(), List.of(
                    "--literal-pathspecs", "diff", "--no-ext-diff", "--no-textconv", "--no-color",
                    "--unified=80", baseline.tree(), "--", path)), timeout, indexEnvironment(baseline.index()));
        } catch (TaskFailure failure) {
            throw failure;
        } catch (Exception exception) {
            throw new TaskFailure("STAGE_WORKSPACE_BASELINE_UNAVAILABLE",
                    "Stage workspace baseline cannot be used: " + exception.getMessage());
        }
    }

    public void requireAvailable(String marker) {
        requireAvailableBaseline(marker);
    }

    public boolean isStage(String marker) {
        return marker != null && marker.startsWith(PREFIX);
    }

    public String stageId(String marker) {
        return parse(marker).stageId();
    }

    /** Removes only validated task directories that no longer have a persisted Task. */
    public int cleanupOrphans(Set<String> liveTaskIds) {
        Path configured = properties.getDataDir().toAbsolutePath().normalize().resolve("stage-baselines");
        if (!Files.exists(configured, LinkOption.NOFOLLOW_LINKS)) return 0;
        try {
            Path base = configured.toRealPath();
            int removed = 0;
            try (var children = Files.newDirectoryStream(base)) {
                for (Path child : children) {
                    String taskId = child.getFileName().toString();
                    if (!taskId.matches(ID_PATTERN) || liveTaskIds.contains(taskId)
                            || !Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) continue;
                    Path repository = child.toAbsolutePath().normalize();
                    if (!repository.getParent().equals(base)) continue;
                    deleteManagedTree(repository);
                    removed++;
                }
            }
            return removed;
        } catch (IOException exception) {
            throw new TaskFailure("STAGE_WORKSPACE_BASELINE_CLEANUP_FAILED",
                    "Unable to inspect Stage workspace baseline storage: " + exception.getMessage());
        }
    }

    private void ensureRepository(Path root, Path repository) {
        if (!Files.exists(repository, LinkOption.NOFOLLOW_LINKS)) {
            requireSuccess(root, List.of("git", "init", "--bare", "--quiet", repository.toString()),
                    "STAGE_WORKSPACE_BASELINE_CREATE_FAILED",
                    "Unable to initialize Stage workspace baseline storage");
        }
        if (!Files.isDirectory(repository.resolve("objects"), LinkOption.NOFOLLOW_LINKS)) {
            throw new TaskFailure("STAGE_WORKSPACE_BASELINE_UNAVAILABLE",
                    "Stage workspace baseline object storage is unavailable");
        }
    }

    private List<String> addArguments(Path root, Path base) {
        ArrayList<String> arguments = new ArrayList<>(List.of(
                "add", "-A", "--", ".", ":(exclude).git", ":(exclude).git/**"));
        if (base.startsWith(root)) {
            String managedData = root.relativize(base).toString().replace('\\', '/');
            arguments.add(":(exclude)" + managedData);
            arguments.add(":(exclude)" + managedData + "/**");
        }
        return List.copyOf(arguments);
    }

    private DiffResult diff(Path root, Path repository, Path index, String tree, Duration timeout) {
        ProcessResult tracked = runner.run(root, git(root, repository, index,
                List.of("diff", "--name-status", "-z", tree)), timeout, indexEnvironment(index));
        ProcessResult untracked = runner.run(root, git(root, repository, index,
                untrackedArguments(root, repository.getParent().getParent())), timeout,
                indexEnvironment(index));
        return new DiffResult(tracked, untracked);
    }

    private List<String> untrackedArguments(Path root, Path dataDirectory) {
        ArrayList<String> arguments = new ArrayList<>(List.of(
                "ls-files", "-z", "--others", "--exclude-standard", "--", ".",
                ":(exclude).git", ":(exclude).git/**"));
        if (dataDirectory.startsWith(root)) {
            String managedData = root.relativize(dataDirectory).toString().replace('\\', '/');
            arguments.add(":(exclude)" + managedData);
            arguments.add(":(exclude)" + managedData + "/**");
        }
        return List.copyOf(arguments);
    }

    private Baseline requireAvailableBaseline(String marker) {
        Marker parsed = parse(marker);
        try {
            Path base = managedBase(false);
            Path repository = managedRepository(base, parsed.taskId()).toRealPath();
            if (!repository.startsWith(base) || !Files.isDirectory(repository.resolve("objects"))) {
                throw new TaskFailure("STAGE_WORKSPACE_BASELINE_UNAVAILABLE",
                        "Stage workspace baseline object storage is unavailable");
            }
            Path indexes = repository.resolve("indexes").toRealPath();
            Path index = indexes.resolve(parsed.stageId() + ".index").normalize();
            if (!index.getParent().equals(indexes) || !Files.isRegularFile(index, LinkOption.NOFOLLOW_LINKS)) {
                throw new TaskFailure("STAGE_WORKSPACE_BASELINE_UNAVAILABLE",
                        "Stage workspace baseline index is unavailable");
            }
            ProcessResult tree = runner.run(repository,
                    List.of("git", "--git-dir=" + repository, "cat-file", "-e", parsed.tree() + "^{tree}"),
                    GIT_TIMEOUT);
            if (tree.timedOut() || tree.outputTruncated() || tree.exitCode() != 0) {
                throw new TaskFailure("STAGE_WORKSPACE_BASELINE_UNAVAILABLE",
                        "Stage workspace baseline tree is unavailable: " + trim(tree.output()));
            }
            ProcessResult indexedTree = runner.run(repository,
                    List.of("git", "--git-dir=" + repository, "write-tree"), GIT_TIMEOUT,
                    indexEnvironment(index));
            if (indexedTree.timedOut() || indexedTree.outputTruncated() || indexedTree.exitCode() != 0
                    || !parsed.tree().equalsIgnoreCase(indexedTree.output().trim())) {
                throw new TaskFailure("STAGE_WORKSPACE_BASELINE_UNAVAILABLE",
                        "Stage workspace baseline index no longer matches its immutable tree");
            }
            return new Baseline(repository, index, parsed.tree());
        } catch (TaskFailure failure) {
            throw failure;
        } catch (Exception exception) {
            throw new TaskFailure("STAGE_WORKSPACE_BASELINE_UNAVAILABLE",
                    "Stage workspace baseline is unavailable: " + exception.getMessage());
        }
    }

    private Marker parse(String marker) {
        if (!isStage(marker)) {
            throw new TaskFailure("STAGE_WORKSPACE_BASELINE_INVALID",
                    "Verifier does not reference a Stage workspace baseline");
        }
        String[] fields = marker.split(":", -1);
        if (fields.length != 4 || !fields[1].matches(ID_PATTERN) || !fields[2].matches(ID_PATTERN)
                || !fields[3].matches("[0-9a-fA-F]{40,64}")) {
            throw new TaskFailure("STAGE_WORKSPACE_BASELINE_INVALID",
                    "Stage workspace baseline reference is invalid");
        }
        return new Marker(fields[1], fields[2], fields[3]);
    }

    private Path managedBase(boolean create) throws IOException {
        Path base = properties.getDataDir().toAbsolutePath().normalize().resolve("stage-baselines");
        if (create) Files.createDirectories(base);
        return base.toRealPath();
    }

    private Path managedRepository(Path base, String taskId) {
        Path repository = base.resolve(taskId).normalize();
        if (!repository.getParent().equals(base)) {
            throw new TaskFailure("STAGE_WORKSPACE_BASELINE_PATH_INVALID",
                    "Stage workspace baseline escaped its managed directory");
        }
        return repository;
    }

    private List<String> git(Path root, Path repository, Path index, List<String> arguments) {
        ArrayList<String> command = new ArrayList<>();
        command.add("git");
        command.add("--git-dir=" + repository);
        command.add("--work-tree=" + root);
        command.addAll(arguments);
        return List.copyOf(command);
    }

    private Map<String, String> indexEnvironment(Path index) {
        return Map.of("GIT_INDEX_FILE", index.toString());
    }

    private void requireSuccess(Path directory, List<String> command, String code, String message) {
        ProcessResult result = runner.run(directory, command, GIT_TIMEOUT);
        if (result.timedOut() || result.outputTruncated() || result.exitCode() != 0) {
            throw new TaskFailure(code, message + ": " + trim(result.output()));
        }
    }

    private void requireSuccess(Path directory, List<String> command, Path index, String code, String message) {
        ProcessResult result = runner.run(directory, command, GIT_TIMEOUT, indexEnvironment(index));
        if (result.timedOut() || result.outputTruncated() || result.exitCode() != 0) {
            throw new TaskFailure(code, message + ": " + trim(result.output()));
        }
    }

    private void requireDiffSuccess(ProcessResult result, String kind) {
        if (result.timedOut() || result.outputTruncated() || result.exitCode() != 0) {
            throw new TaskFailure("STAGE_WORKSPACE_BASELINE_CREATE_FAILED",
                    "Unable to verify the " + kind + " Stage baseline snapshot: " + trim(result.output()));
        }
    }

    private void requireId(String id, String label) {
        if (id == null || !id.matches(ID_PATTERN)) {
            throw new TaskFailure("STAGE_WORKSPACE_BASELINE_PATH_INVALID",
                    label + " id is not safe for Stage workspace baseline storage");
        }
    }

    private void deleteManagedTree(Path repository) throws IOException {
        try (var paths = Files.walk(repository)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    path.toFile().setWritable(true);
                }
                Files.deleteIfExists(path);
            }
        }
    }

    private String trim(String value) {
        return value == null ? "" : value.substring(0, Math.min(value.length(), 2_000));
    }

    private record Marker(String taskId, String stageId, String tree) { }
    private record Baseline(Path repository, Path index, String tree) { }
    public record DiffResult(ProcessResult tracked, ProcessResult untracked) { }
}
