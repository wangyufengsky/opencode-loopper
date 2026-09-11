package io.opencode.loopper.service;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.TaskFailure;
import io.opencode.loopper.runtime.GitEvidenceProcess;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Owns persistent repositories; all source-repository operations are read-only. */
@Service
public class TemplateGitSnapshotService {
    private final ProjectService projects;
    private final GitEvidenceProcess git;
    private final Path dataRoot;

    public TemplateGitSnapshotService(ProjectService projects, GitEvidenceProcess git, LoopperProperties properties) {
        this.projects = projects;
        this.git = git;
        this.dataRoot = properties.getDataDir().toAbsolutePath().normalize();
    }

    public Path workspace(String taskId) {
        if (taskId == null || !taskId.matches("[a-zA-Z0-9-]{1,80}")) {
            throw new IllegalArgumentException("模板任务标识无效");
        }
        return dataRoot.resolve("template-tasks").resolve(taskId);
    }

    public Path prepareDirectory(String taskId) {
        Path directory = workspace(taskId);
        ensureOwned(directory);
        try { return directory.toRealPath(); }
        catch (IOException failure) { throw new TaskFailure("TEMPLATE_SNAPSHOT_PATH_INVALID", "模板执行目录无法解析"); }
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Snapshot freeze(String taskId, String projectId, ProjectBranchService.Branch branch) {
        Path directory = prepareDirectory(taskId);
        git.requireSupported(directory);
        Path repository = directory.resolve("repository.git");
        ensureOwned(directory);
        if (Files.exists(repository, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(repository)) {
            throw new TaskFailure("TEMPLATE_SNAPSHOT_PATH_INVALID", "模板快照目录不能是符号链接");
        }
        if (Files.exists(repository.resolve("HEAD"))) {
            var existing = git.run(repository, Duration.ofSeconds(30), List.of("rev-parse", "--verify", "refs/heads/snapshot^{commit}"));
            if (existing.exitCode() == 0) {
                requireCompleteHistory(repository);
                return new Snapshot(directory, repository, existing.output().trim());
            }
        } else {
            git.read(directory, "init", "--bare", "--template=", "--", repository.toString());
        }
        Path source = Path.of(projects.get(projectId).rootPath());
        String remote = source.toAbsolutePath().toString();
        if (branch.remote() != null) remote = git.read(source, "remote", "get-url", "--", branch.remote()).strip();
        else if ("true".equals(git.read(source, "rev-parse", "--is-shallow-repository").strip())) {
            throw new TaskFailure("TEMPLATE_SHALLOW_SOURCE", "本地分支历史不完整，请先补齐历史或选择远程分支");
        }
        if (remote.startsWith("ext::") || remote.indexOf('\n') >= 0) {
            throw new TaskFailure("TEMPLATE_REMOTE_INVALID", "该远程来源不支持模板任务快照");
        }
        var fetched = git.run(repository, Duration.ofSeconds(60), List.of("fetch", "--no-tags", "--no-write-fetch-head",
                "--", remote, "+" + branch.ref() + ":refs/heads/snapshot"));
        fetched.requireSuccess(List.of("fetch"));
        requireCompleteHistory(repository);
        String head = git.read(repository, "rev-parse", "--verify", "refs/heads/snapshot^{commit}").strip();
        return new Snapshot(directory, repository, head);
    }

    private void requireCompleteHistory(Path repository) {
        if ("true".equals(git.read(repository, "rev-parse", "--is-shallow-repository").strip())) {
            throw new TaskFailure("TEMPLATE_SHALLOW_SOURCE", "所选来源的提交历史不完整，请补齐来源历史后重新发起任务");
        }
    }

    private void ensureOwned(Path directory) {
        try {
            Files.createDirectories(dataRoot);
            Path parent = dataRoot.resolve("template-tasks");
            if (Files.isSymbolicLink(parent) || Files.isSymbolicLink(directory)) throw new IOException("symbolic link");
            Files.createDirectories(directory);
            if (!directory.toRealPath().startsWith(dataRoot.toRealPath())) throw new IOException("outside root");
        } catch (IOException failure) {
            throw new TaskFailure("TEMPLATE_SNAPSHOT_PATH_INVALID", "无法创建模板任务的独立快照目录");
        }
    }

    public record Snapshot(Path directory, Path repository, String head) { }
}
