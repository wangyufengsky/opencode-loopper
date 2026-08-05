package io.opencode.loopper.runtime;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.TaskFailure;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class GitWorktreeManager {
    public static final String DIRECT_BRANCH = "DIRECT";
    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(30);
    private final SafeProcessRunner runner;
    private final LoopperProperties properties;
    private final DirectWorkspaceBaselineManager directBaselines;

    public GitWorktreeManager(SafeProcessRunner runner, LoopperProperties properties,
                              DirectWorkspaceBaselineManager directBaselines) {
        this.runner = runner;
        this.properties = properties;
        this.directBaselines = directBaselines;
    }

    public Worktree create(Path projectRoot, String taskId) {
        try {
            Path root = projectRoot.toRealPath();
            ProcessResult repository = runner.run(root, List.of("git", "rev-parse", "--is-inside-work-tree"), GIT_TIMEOUT);
            if (repository.timedOut() || repository.outputTruncated() || repository.exitCode() != 0
                    || !"true".equals(repository.output().trim())) {
                return direct(root, taskId);
            }
            ProcessResult topLevel = runner.run(root, List.of("git", "rev-parse", "--show-toplevel"), GIT_TIMEOUT);
            if (topLevel.timedOut() || topLevel.outputTruncated() || topLevel.exitCode() != 0 || topLevel.output().isBlank()) {
                return direct(root, taskId);
            }
            Path repositoryRoot;
            try { repositoryRoot = Path.of(topLevel.output().trim()).toRealPath(); }
            catch (Exception invalidTopLevel) { return direct(root, taskId); }
            if (!repositoryRoot.equals(root)) return direct(root, taskId);
            ProcessResult head = runner.run(root, List.of("git", "rev-parse", "HEAD"), GIT_TIMEOUT);
            if (head.timedOut() || head.outputTruncated() || head.exitCode() != 0) return direct(root, taskId);
            Path base = properties.getDataDir().toAbsolutePath().normalize().resolve("worktrees");
            Files.createDirectories(base);
            Path worktree = base.resolve(taskId).normalize();
            if (!worktree.getParent().equals(base)) throw new TaskFailure("WORKTREE_PATH_INVALID", "Task worktree escaped its managed directory");
            if (Files.exists(worktree)) throw new TaskFailure("WORKTREE_ALREADY_EXISTS", "A managed worktree already exists for task " + taskId);
            String branch = "loopper/" + taskId;
            ProcessResult added = runner.run(root,
                    List.of("git", "worktree", "add", "-b", branch, worktree.toString(), head.output().trim()), GIT_TIMEOUT);
            if (added.exitCode() != 0) throw new TaskFailure("WORKTREE_CREATE_FAILED", trim(added.output()));
            Path resolved = worktree.toRealPath();
            if (!resolved.startsWith(base.toRealPath())) {
                throw new TaskFailure("WORKTREE_ESCAPE", "Created worktree did not remain inside the managed worktree directory");
            }
            return new Worktree(resolved, branch, head.output().trim());
        } catch (TaskFailure e) {
            throw e;
        } catch (Exception e) {
            throw new TaskFailure("WORKTREE_CREATE_FAILED", "Unable to create isolated worktree: " + e.getMessage());
        }
    }

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
        if (!DIRECT_BRANCH.equals(branch)) {
            requireManaged(executionPath);
            return;
        }
        try {
            Path expected = projectRoot.toRealPath();
            Path actual = executionPath.toRealPath();
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

    private Worktree direct(Path root, String taskId) {
        return new Worktree(root, DIRECT_BRANCH, directBaselines.capture(root, taskId));
    }

    private String trim(String value) { return value == null ? "" : value.substring(0, Math.min(value.length(), 2000)); }
    public record Worktree(Path path, String branch, String baselineCommit) { }
    public record RepositoryInspection(boolean pathAvailable, boolean isolatedWorktree, String branch) {
        private static RepositoryInspection direct() { return new RepositoryInspection(true, false, null); }
        private static RepositoryInspection unavailable() { return new RepositoryInspection(false, false, null); }
    }
}
