package io.opencode.loopper.service;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.runtime.ProcessResult;
import io.opencode.loopper.runtime.SafeProcessRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Applies a verified Task commit back to the registered checkout or opens a conflict session. */
final class LocalSourceSynchronizer {
    private static final Duration WRITE_TIMEOUT = Duration.ofMinutes(2);
    private final TaskService tasks;
    private final ProjectService projects;
    private final SafeProcessRunner runner;
    private final LoopperProperties properties;
    private final LocalSyncConflictService conflicts;
    private final PublicationGitClient git;

    LocalSourceSynchronizer(TaskService tasks, ProjectService projects, SafeProcessRunner runner,
                            LoopperProperties properties, LocalSyncConflictService conflicts,
                            PublicationGitClient git) {
        this.tasks = tasks;
        this.projects = projects;
        this.runner = runner;
        this.properties = properties;
        this.conflicts = conflicts;
        this.git = git;
    }

    void sync(TaskRow task, Path workspace, String commitSha) {
        if (task.baselineCommit() == null || task.baselineCommit().isBlank()) {
            throw new ConflictException("TASK_BASELINE_MISSING",
                    "任务缺少创建分支时的基线提交，无法安全同步源代码");
        }
        ProjectRow project = projects.get(task.projectId());
        Path source = Path.of(project.rootPath());
        git.requireExactRepository(source);
        String sourceHead = git.required(source, List.of("git", "rev-parse", "HEAD"),
                "SOURCE_GIT_HEAD_UNAVAILABLE");
        if (commitSha.equals(sourceHead)) {
            tasks.recordLocalSourceSync(task.id(), commitSha, "ALREADY_PRESENT");
            return;
        }
        String sourceStatus = git.allowEmpty(source,
                List.of("git", "status", "--porcelain=v1", "--untracked-files=all"),
                "SOURCE_GIT_STATUS_FAILED");
        if (task.baselineCommit().equals(sourceHead) && sourceStatus.isBlank()) {
            git.run(source, List.of("git", "merge", "--ff-only", commitSha), WRITE_TIMEOUT,
                    "LOCAL_SOURCE_FAST_FORWARD_FAILED", "无法将任务提交快进到源项目目录");
            String mergedHead = git.required(source, List.of("git", "rev-parse", "HEAD"),
                    "SOURCE_GIT_HEAD_UNAVAILABLE");
            if (!commitSha.equals(mergedHead)) {
                throw new ConflictException("LOCAL_SOURCE_FAST_FORWARD_UNCONFIRMED",
                        "源项目快进完成后提交状态不一致");
            }
            tasks.recordLocalSourceSync(task.id(), commitSha, "FAST_FORWARD");
            return;
        }
        if (task.baselineCommit().equals(sourceHead)) {
            requireNoStagedTaskPaths(task, workspace, source, commitSha);
            try {
                applyTaskPatch(task, workspace, source, commitSha);
                tasks.recordLocalSourceSync(task.id(), commitSha, "WORKTREE_OVERLAY");
                return;
            } catch (ConflictException conflict) {
                if (!"LOCAL_SOURCE_CONFLICT".equals(conflict.code())) throw conflict;
            }
        }
        conflicts.createOrRefresh(task.id());
    }

    private void requireNoStagedTaskPaths(TaskRow task, Path workspace, Path source, String commitSha) {
        String taskPaths = git.allowEmpty(workspace,
                List.of("git", "diff", "--name-only", "-z", task.baselineCommit(), commitSha, "--"),
                "LOCAL_SOURCE_PATH_CHECK_FAILED");
        String stagedPaths = git.allowEmpty(source,
                List.of("git", "diff", "--cached", "--name-only", "-z", "--"),
                "SOURCE_GIT_INDEX_CHECK_FAILED");
        Set<String> taskSet = new HashSet<>(List.of(taskPaths.split("\u0000", -1)));
        boolean overlaps = List.of(stagedPaths.split("\u0000", -1)).stream()
                .filter(path -> !path.isBlank()).anyMatch(taskSet::contains);
        if (overlaps) {
            throw new ConflictException("LOCAL_SOURCE_TASK_PATH_STAGED",
                    "源项目中任务涉及路径已暂存；请取消暂存或提交后再同步");
        }
    }

    private void applyTaskPatch(TaskRow task, Path workspace, Path source, String commitSha) {
        Path patch = null;
        try {
            Path directory = properties.getDataDir().toAbsolutePath().normalize().resolve("publication-patches");
            Files.createDirectories(directory);
            patch = Files.createTempFile(directory, "task-" + task.id() + "-", ".patch");
            git.run(workspace, List.of("git", "diff", "--binary", "--full-index", "--output=" + patch,
                            task.baselineCommit(), commitSha, "--"), WRITE_TIMEOUT,
                    "LOCAL_SOURCE_PATCH_CREATE_FAILED", "无法生成任务同步补丁");
            ProcessResult check = runner.run(source,
                    List.of("git", "apply", "--check", "--binary", "--whitespace=nowarn", patch.toString()),
                    WRITE_TIMEOUT);
            if (check.timedOut() || check.outputTruncated()) {
                throw new ConflictException("LOCAL_SOURCE_CONFLICT", "任务补丁冲突检查失败；未修改源项目");
            }
            if (check.exitCode() != 0) {
                ProcessResult reverse = runner.run(source, List.of("git", "apply", "--check", "--reverse",
                        "--binary", "--whitespace=nowarn", patch.toString()), WRITE_TIMEOUT);
                if (!reverse.timedOut() && !reverse.outputTruncated() && reverse.exitCode() == 0) return;
                String detail = git.scrub(check.output());
                throw new ConflictException("LOCAL_SOURCE_CONFLICT",
                        "源项目现有改动与任务变更冲突；未修改源项目，请处理冲突后重试"
                                + (detail.isBlank() ? "" : "：" + detail));
            }
            git.run(source, List.of("git", "apply", "--binary", "--whitespace=nowarn", patch.toString()),
                    WRITE_TIMEOUT, "LOCAL_SOURCE_APPLY_FAILED", "任务补丁检查通过，但写入源项目失败");
        } catch (ConflictException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new ConflictException("LOCAL_SOURCE_SYNC_FAILED",
                    "无法同步到源项目目录：" + git.safeMessage(failure));
        } finally {
            if (patch != null) {
                try { Files.deleteIfExists(patch); } catch (Exception ignored) { }
            }
        }
    }
}
