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
    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(30);
    private final SafeProcessRunner runner;
    private final LoopperProperties properties;

    public GitWorktreeManager(SafeProcessRunner runner, LoopperProperties properties) {
        this.runner = runner;
        this.properties = properties;
    }

    public Worktree create(Path projectRoot, String taskId) {
        try {
            Path root = projectRoot.toRealPath();
            ProcessResult repository = runner.run(root, List.of("git", "rev-parse", "--is-inside-work-tree"), GIT_TIMEOUT);
            if (repository.exitCode() != 0 || !"true".equals(repository.output().trim())) {
                throw new TaskFailure("INVALID_GIT_REPOSITORY", "Task execution requires a Git working tree with a valid HEAD");
            }
            ProcessResult head = runner.run(root, List.of("git", "rev-parse", "HEAD"), GIT_TIMEOUT);
            if (head.exitCode() != 0) throw new TaskFailure("GIT_HEAD_UNAVAILABLE", "Cannot resolve the project's Git HEAD");
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

    private String trim(String value) { return value == null ? "" : value.substring(0, Math.min(value.length(), 2000)); }
    public record Worktree(Path path, String branch, String baselineCommit) { }
}
