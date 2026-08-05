package io.opencode.loopper.runtime;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.TaskFailure;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Captures a Git-compatible baseline without turning the registered project into
 * a repository. The private index and object database live under Loopper's data
 * directory; OpenCode still edits the registered project directory directly.
 */
@Component
public class DirectWorkspaceBaselineManager {
    public static final String PREFIX = "direct:";
    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(30);
    private final SafeProcessRunner runner;
    private final LoopperProperties properties;

    public DirectWorkspaceBaselineManager(SafeProcessRunner runner, LoopperProperties properties) {
        this.runner = runner;
        this.properties = properties;
    }

    public String capture(Path projectRoot, String taskId) {
        try {
            Path root = projectRoot.toRealPath();
            Path base = properties.getDataDir().toAbsolutePath().normalize().resolve("direct-baselines");
            Files.createDirectories(base);
            Path repository = base.resolve(taskId).normalize();
            if (!repository.getParent().equals(base)) {
                throw new TaskFailure("DIRECT_BASELINE_PATH_INVALID", "Direct-execution baseline escaped its managed directory");
            }
            if (Files.exists(repository)) {
                throw new TaskFailure("DIRECT_BASELINE_ALREADY_EXISTS", "A direct-execution baseline already exists for task " + taskId);
            }
            requireSuccess(root, List.of("git", "init", "--quiet", repository.toString()),
                    "DIRECT_BASELINE_CREATE_FAILED", "Unable to initialize the direct-execution baseline");
            Path gitDir = repository.resolve(".git").toRealPath();
            java.util.ArrayList<String> add = new java.util.ArrayList<>(List.of(
                    "add", "-A", "--", ".", ":(exclude).git", ":(exclude).git/**"));
            if (base.startsWith(root)) {
                String managedData = root.relativize(base).toString().replace('\\', '/');
                add.add(":(exclude)" + managedData);
                add.add(":(exclude)" + managedData + "/**");
            }
            requireSuccess(root, git(root, gitDir, add.toArray(String[]::new)),
                    "DIRECT_BASELINE_CREATE_FAILED", "Unable to index the direct-execution baseline");
            ProcessResult tree = runner.run(root, git(root, gitDir, "write-tree"), GIT_TIMEOUT);
            if (tree.timedOut() || tree.outputTruncated() || tree.exitCode() != 0 || !tree.output().trim().matches("[0-9a-fA-F]{40,64}")) {
                throw new TaskFailure("DIRECT_BASELINE_CREATE_FAILED", "Unable to finalize the direct-execution baseline: " + trim(tree.output()));
            }
            return PREFIX + taskId + ":" + tree.output().trim();
        } catch (TaskFailure failure) {
            throw failure;
        } catch (Exception exception) {
            throw new TaskFailure("DIRECT_BASELINE_CREATE_FAILED", "Unable to capture the direct-execution baseline: " + exception.getMessage());
        }
    }

    public DiffResult diff(Path projectRoot, String marker, Duration timeout) {
        Baseline baseline = requireAvailableBaseline(marker);
        try {
            Path root = projectRoot.toRealPath();
            ProcessResult tracked = runner.run(root, git(root, baseline.gitDir(), "diff", "--name-status", baseline.tree()), timeout);
            ProcessResult untracked = runner.run(root, git(root, baseline.gitDir(), "ls-files", "--others", "--exclude-standard"), timeout);
            return new DiffResult(tracked, untracked);
        } catch (TaskFailure failure) {
            throw failure;
        } catch (Exception exception) {
            throw new TaskFailure("DIRECT_BASELINE_UNAVAILABLE", "Direct-execution baseline cannot be used: " + exception.getMessage());
        }
    }

    public ProcessResult patch(Path projectRoot, String marker, String path, Duration timeout) {
        Baseline baseline = requireAvailableBaseline(marker);
        try {
            Path root = projectRoot.toRealPath();
            return runner.run(root, git(root, baseline.gitDir(), "--literal-pathspecs", "diff", "--no-ext-diff",
                    "--no-textconv", "--no-color", "--unified=80", baseline.tree(), "--", path), timeout);
        } catch (TaskFailure failure) {
            throw failure;
        } catch (Exception exception) {
            throw new TaskFailure("DIRECT_BASELINE_UNAVAILABLE", "Direct-execution baseline cannot be used: " + exception.getMessage());
        }
    }

    public void requireAvailable(String marker) {
        requireAvailableBaseline(marker);
    }

    public boolean isDirect(String marker) {
        return marker != null && marker.startsWith(PREFIX);
    }

    private Baseline requireAvailableBaseline(String marker) {
        if (!isDirect(marker)) {
            throw new TaskFailure("DIRECT_BASELINE_INVALID", "Task does not reference a direct-execution baseline");
        }
        String[] fields = marker.split(":", -1);
        if (fields.length != 3 || !fields[1].matches("[A-Za-z0-9-]{1,80}") || !fields[2].matches("[0-9a-fA-F]{40,64}")) {
            throw new TaskFailure("DIRECT_BASELINE_INVALID", "Direct-execution baseline reference is invalid");
        }
        try {
            Path base = properties.getDataDir().toAbsolutePath().normalize().resolve("direct-baselines").toRealPath();
            Path repository = base.resolve(fields[1]).normalize();
            if (!repository.getParent().equals(base)) {
                throw new TaskFailure("DIRECT_BASELINE_PATH_INVALID", "Direct-execution baseline escaped its managed directory");
            }
            Path gitDir = repository.resolve(".git").toRealPath();
            if (!gitDir.startsWith(base) || !Files.isDirectory(gitDir.resolve("objects"))) {
                throw new TaskFailure("DIRECT_BASELINE_UNAVAILABLE", "Direct-execution baseline is not available");
            }
            return new Baseline(gitDir, fields[2]);
        } catch (TaskFailure failure) {
            throw failure;
        } catch (Exception exception) {
            throw new TaskFailure("DIRECT_BASELINE_UNAVAILABLE", "Direct-execution baseline is not available: " + exception.getMessage());
        }
    }

    private List<String> git(Path root, Path gitDir, String... arguments) {
        java.util.ArrayList<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.add("--git-dir=" + gitDir);
        command.add("--work-tree=" + root);
        command.addAll(List.of(arguments));
        return List.copyOf(command);
    }

    private void requireSuccess(Path directory, List<String> command, String code, String message) {
        ProcessResult result = runner.run(directory, command, GIT_TIMEOUT);
        if (result.timedOut() || result.outputTruncated() || result.exitCode() != 0) {
            throw new TaskFailure(code, message + ": " + trim(result.output()));
        }
    }

    private String trim(String value) {
        return value == null ? "" : value.substring(0, Math.min(value.length(), 2000));
    }

    private record Baseline(Path gitDir, String tree) { }
    public record DiffResult(ProcessResult tracked, ProcessResult untracked) { }
}
