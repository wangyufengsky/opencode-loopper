package io.opencode.loopper.service;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.TaskState;
import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.runtime.GitWorktreeManager;
import io.opencode.loopper.runtime.OpenCodeClient;
import io.opencode.loopper.runtime.ProcessResult;
import io.opencode.loopper.runtime.SafeProcessRunner;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Human-triggered publication for a successfully verified, isolated Task worktree. */
@Service
public class TaskPublicationService {
    private static final Duration GIT_READ_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration GIT_WRITE_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration AI_TIMEOUT = Duration.ofSeconds(75);
    private static final Pattern COMMIT_MESSAGE = Pattern.compile("^#[0-9]{4}_[^\\r\\n]{1,120}$");
    private static final Pattern PREFIX = Pattern.compile("^#[0-9]{4}_");
    private static final Pattern SCP_REMOTE = Pattern.compile("^(?:[^@/]+@)?([^:/]+):(.+)$");

    private final TaskService tasks;
    private final ProjectService projects;
    private final GitWorktreeManager worktrees;
    private final SafeProcessRunner runner;
    private final OpenCodeClient openCode;
    private final LoopperProperties properties;
    private final LocalSyncConflictService localConflicts;
    private final ConcurrentHashMap<String, ReentrantLock> taskLocks = new ConcurrentHashMap<>();

    public TaskPublicationService(TaskService tasks, ProjectService projects, GitWorktreeManager worktrees,
                                  SafeProcessRunner runner, OpenCodeClient openCode, LoopperProperties properties,
                                  LocalSyncConflictService localConflicts) {
        this.tasks = tasks;
        this.projects = projects;
        this.worktrees = worktrees;
        this.runner = runner;
        this.openCode = openCode;
        this.properties = properties;
        this.localConflicts = localConflicts;
    }

    public PublicationStatus status(String taskId) {
        TaskRow task = tasks.get(taskId);
        if (!TaskState.SUCCEEDED.name().equals(task.state())) {
            return unavailable(task, "任务通过全部验收后才能提交");
        }
        if (GitWorktreeManager.DIRECT_BRANCH.equals(task.branchName())) {
            return unavailable(task, "直接执行任务没有隔离分支，不能由 Loopper 自动提交和推送");
        }
        try {
            return inspect(task);
        } catch (RuntimeException failure) {
            return unavailable(task, safeMessage(failure));
        }
    }

    public CommitSuggestion generateCommitMessage(String taskId) {
        TaskRow task = requirePublishableTask(taskId);
        PublicationStatus current = inspect(task);
        if (!"READY".equals(current.state())) {
            throw new ConflictException("TASK_PUBLICATION_NOT_READY", "当前任务没有等待提交的文件变更");
        }
        if (!openCode.healthy()) {
            throw new ServiceUnavailableException("OPENCODE_UNAVAILABLE", "OpenCode 不可用，无法生成默认提交信息");
        }
        Path workspace = workspace(task);
        OpenCodeClient.OpenCodeSession session;
        try {
            session = openCode.createReadOnlySession(workspace,
                    "OpenCode Loopper Commit Message (READ_ONLY)", configuredModel());
            openCode.promptAsync(session, commitPrompt(task, workspace));
        } catch (RuntimeException failure) {
            throw new ServiceUnavailableException("COMMIT_MESSAGE_AI_FAILED", safeMessage(failure));
        }

        long deadline = System.nanoTime() + AI_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                OpenCodeClient.SessionStatus state = openCode.sessionStatus(session);
                if (state.completed()) {
                    String subject = normalizeAiSubject(openCode.sessionOutput(session));
                    return new CommitSuggestion(subject, true);
                }
                if (state.failed()) {
                    throw new ServiceUnavailableException("COMMIT_MESSAGE_AI_FAILED",
                            state.detail() == null || state.detail().isBlank() ? "AI 提交信息生成会话失败" : state.detail());
                }
                TimeUnit.MILLISECONDS.sleep(250);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                abortQuietly(session);
                throw new ServiceUnavailableException("COMMIT_MESSAGE_AI_INTERRUPTED", "AI 提交信息生成被中断");
            } catch (ServiceUnavailableException failure) {
                throw failure;
            } catch (RuntimeException failure) {
                abortQuietly(session);
                throw new ServiceUnavailableException("COMMIT_MESSAGE_AI_FAILED", safeMessage(failure));
            }
        }
        abortQuietly(session);
        throw new ServiceUnavailableException("COMMIT_MESSAGE_AI_TIMEOUT", "AI 提交信息生成超时，请重试或手工填写");
    }

    public PublicationStatus commitAndPush(String taskId, String requestedMessage) {
        TaskRow task = requirePublishableTask(taskId);
        ReentrantLock lock = taskLocks.computeIfAbsent(task.id(), ignored -> new ReentrantLock());
        if (!lock.tryLock()) {
            throw new ConflictException("TASK_PUBLICATION_ACTIVE", "当前任务正在提交或推送，请等待本次操作完成");
        }
        try {
            PublicationStatus before = inspect(task);
            if ("PUSHED".equals(before.state()) || "SYNCED_LOCAL".equals(before.state())
                    || "LOCAL_SYNC_CONFLICT".equals(before.state())) return before;
            Path workspace = workspace(task);
            if ("READY".equals(before.state())) {
                String commitMessage = requireCommitMessage(requestedMessage);
                runRequired(workspace, List.of("git", "add", "--all"), GIT_WRITE_TIMEOUT,
                        "GIT_STAGE_FAILED", "无法暂存任务变更");
                runRequired(workspace, List.of("git", "commit", "-m", commitMessage), GIT_WRITE_TIMEOUT,
                        "GIT_COMMIT_FAILED", "无法创建任务提交");
            } else if (!"COMMITTED".equals(before.state())) {
                throw new ConflictException("TASK_PUBLICATION_NOT_READY", before.reason() == null
                        ? "当前任务没有可提交或可推送的变更" : before.reason());
            }

            PublicationStatus committed = inspect(task);
            if (!"COMMITTED".equals(committed.state()) && !"PUSHED".equals(committed.state())
                    && !"SYNCED_LOCAL".equals(committed.state())
                    && !"LOCAL_SYNC_CONFLICT".equals(committed.state())) {
                throw new ConflictException("GIT_COMMIT_STATE_INVALID", "提交完成后工作区状态不一致，已停止发布");
            }
            if ("PUSHED".equals(committed.state()) || "SYNCED_LOCAL".equals(committed.state())
                    || "LOCAL_SYNC_CONFLICT".equals(committed.state())) return committed;
            if (committed.remoteName() == null) {
                syncLocalSource(task, workspace, committed.commitSha());
                PublicationStatus synced = inspect(task);
                if ("LOCAL_SYNC_CONFLICT".equals(synced.state())) return synced;
                if (!"SYNCED_LOCAL".equals(synced.state())) {
                    throw new ConflictException("LOCAL_SOURCE_SYNC_UNCONFIRMED", "源代码同步完成，但同步证据未能确认");
                }
                return synced;
            }
            runRequired(workspace,
                    List.of("git", "push", "--set-upstream", committed.remoteName(),
                            "HEAD:refs/heads/" + committed.branch()),
                    GIT_WRITE_TIMEOUT, "GIT_PUSH_FAILED", "提交已创建，但推送失败；可以稍后继续推送");
            PublicationStatus pushed = inspect(task);
            if (!"PUSHED".equals(pushed.state())) {
                throw new ConflictException("GIT_PUSH_STATE_UNCONFIRMED", "Git push 返回成功，但远端跟踪分支尚未与本地提交一致");
            }
            return pushed;
        } finally {
            lock.unlock();
            if (!lock.hasQueuedThreads()) taskLocks.remove(task.id(), lock);
        }
    }

    public MergeRequestDraft mergeRequestDraft(String taskId, String targetBranch, String title, String description) {
        TaskRow task = requirePublishableTask(taskId);
        PublicationStatus current = inspect(task);
        if (!"PUSHED".equals(current.state())) {
            throw new ConflictException("TASK_BRANCH_NOT_PUSHED", "任务分支推送成功后才能创建合并请求");
        }
        String target = normalizedBranch(targetBranch);
        if (target.equals(current.branch())) {
            throw new BadRequestException("MERGE_TARGET_INVALID", "目标分支不能与任务分支相同");
        }
        if (!current.targetBranches().contains(target)) {
            throw new BadRequestException("MERGE_TARGET_UNKNOWN", "目标分支不在当前本地远端分支列表中，请先 fetch 后重试");
        }
        String normalizedTitle = singleLine(title, 160, "MERGE_TITLE_INVALID", "请输入 1 到 160 个字符的合并请求标题");
        String normalizedDescription = description == null ? "" : description.strip();
        if (normalizedDescription.length() > 8000) {
            throw new BadRequestException("MERGE_DESCRIPTION_INVALID", "合并请求说明不能超过 8000 个字符");
        }
        RemoteRepository remote = remoteRepository(current.remoteUrl());
        if (remote.webBase() == null || "UNKNOWN".equals(remote.provider())) {
            throw new BadRequestException("MERGE_REQUEST_PROVIDER_UNSUPPORTED", "当前远端地址无法生成 GitLab 或 GitHub 合并请求入口");
        }
        String url = switch (remote.provider()) {
            case "GITLAB" -> remote.webBase() + "/-/merge_requests/new?"
                    + query("merge_request[source_branch]", current.branch()) + "&"
                    + query("merge_request[target_branch]", target) + "&"
                    + query("merge_request[title]", normalizedTitle) + "&"
                    + query("merge_request[description]", normalizedDescription);
            case "GITHUB" -> remote.webBase() + "/compare/" + pathSegment(target) + "..." + pathSegment(current.branch())
                    + "?expand=1&" + query("title", normalizedTitle) + "&" + query("body", normalizedDescription);
            default -> throw new BadRequestException("MERGE_REQUEST_PROVIDER_UNSUPPORTED", "当前远端托管平台暂不支持合并请求入口");
        };
        return new MergeRequestDraft(remote.provider(), current.branch(), target, normalizedTitle, normalizedDescription, url);
    }

    private PublicationStatus inspect(TaskRow task) {
        Path workspace = workspace(task);
        requireExactRepository(workspace);
        String branch = requiredOutput(workspace, List.of("git", "branch", "--show-current"), "GIT_BRANCH_UNAVAILABLE");
        if (!task.branchName().equals(branch)) {
            throw new ConflictException("TASK_BRANCH_MISMATCH", "任务执行目录当前分支与记录不一致，已停止发布");
        }
        String head = requiredOutput(workspace, List.of("git", "rev-parse", "HEAD"), "GIT_HEAD_UNAVAILABLE");
        String status = requiredOutputAllowEmpty(workspace,
                List.of("git", "status", "--porcelain=v1", "--untracked-files=all"), "GIT_STATUS_FAILED");
        boolean hasChanges = !status.isBlank();
        boolean committed = task.baselineCommit() != null && !head.equals(task.baselineCommit());
        String commitMessage = committed ? optionalOutput(workspace, List.of("git", "log", "-1", "--pretty=%s")) : null;
        String remoteName = preferredRemote(workspace);
        if (remoteName == null) {
            boolean synced = !hasChanges && committed && tasks.hasLocalSourceSync(task.id(), head);
            LocalSyncConflictService.SessionView conflict = synced ? null : localConflicts.active(task.id());
            String state = hasChanges ? "READY" : synced ? "SYNCED_LOCAL"
                    : conflict != null ? "LOCAL_SYNC_CONFLICT" : committed ? "COMMITTED" : "NO_CHANGES";
            String reason = "NO_CHANGES".equals(state) ? "任务没有产生可提交的文件变更"
                    : "SYNCED_LOCAL".equals(state) ? "任务提交已同步到源项目目录"
                    : "LOCAL_SYNC_CONFLICT".equals(state) ? conflict.errorMessage() : null;
            if ("COMMITTED".equals(state) && (commitMessage == null || !COMMIT_MESSAGE.matcher(commitMessage).matches())) {
                state = "UNAVAILABLE";
                reason = "任务分支已有不符合 #四位数字_AI说明 格式的本地提交，请先在仓库中处理";
            }
            return new PublicationStatus(state, !"NO_CHANGES".equals(state) && !"UNAVAILABLE".equals(state), reason,
                    branch, null, null, head, commitMessage, null, List.of(), "UNKNOWN", null, hasChanges,
                    conflict == null ? null : conflict.id(), conflict == null ? 0 : conflict.conflictCount(),
                    conflict == null ? 0 : conflict.resolvedCount());
        }
        String remoteUrl = requiredOutput(workspace, List.of("git", "remote", "get-url", remoteName), "GIT_REMOTE_UNAVAILABLE");
        String upstream = optionalOutput(workspace,
                List.of("git", "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{upstream}"));
        String upstreamHead = upstream == null ? null : optionalOutput(workspace, List.of("git", "rev-parse", upstream));
        boolean pushed = !hasChanges && head.equals(upstreamHead);
        String state = hasChanges ? "READY" : pushed ? "PUSHED" : committed ? "COMMITTED" : "NO_CHANGES";
        String reason = "NO_CHANGES".equals(state) ? "任务没有产生可提交的文件变更" : null;
        if ("COMMITTED".equals(state) && (commitMessage == null || !COMMIT_MESSAGE.matcher(commitMessage).matches())) {
            state = "UNAVAILABLE";
            reason = "任务分支已有不符合 #四位数字_AI说明 格式的本地提交，请先在仓库中处理";
        }
        List<String> targets = targetBranches(workspace, remoteName, branch);
        String target = preferredTarget(workspace, task, remoteName, targets);
        String provider = remoteRepository(remoteUrl).provider();
        return new PublicationStatus(state, !"NO_CHANGES".equals(state) && !"UNAVAILABLE".equals(state), reason, branch, remoteName,
                remoteUrl, head, commitMessage, target, targets, provider, upstream, hasChanges, null, 0, 0);
    }

    private TaskRow requirePublishableTask(String taskId) {
        TaskRow task = tasks.get(taskId);
        if (!TaskState.SUCCEEDED.name().equals(task.state())) {
            throw new ConflictException("TASK_NOT_SUCCEEDED", "任务通过全部验收后才能提交并发布");
        }
        if (GitWorktreeManager.DIRECT_BRANCH.equals(task.branchName())) {
            throw new ConflictException("DIRECT_TASK_PUBLICATION_UNSUPPORTED", "直接执行任务没有隔离分支，请在项目仓库中手工处理提交");
        }
        return task;
    }

    private void syncLocalSource(TaskRow task, Path workspace, String commitSha) {
        if (task.baselineCommit() == null || task.baselineCommit().isBlank()) {
            throw new ConflictException("TASK_BASELINE_MISSING", "任务缺少创建分支时的基线提交，无法安全同步源代码");
        }
        ProjectRow project = projects.get(task.projectId());
        Path source = Path.of(project.rootPath());
        requireExactRepository(source);
        String sourceHead = requiredOutput(source, List.of("git", "rev-parse", "HEAD"), "SOURCE_GIT_HEAD_UNAVAILABLE");
        if (commitSha.equals(sourceHead)) {
            tasks.recordLocalSourceSync(task.id(), commitSha, "ALREADY_PRESENT");
            return;
        }
        String sourceStatus = requiredOutputAllowEmpty(source,
                List.of("git", "status", "--porcelain=v1", "--untracked-files=all"), "SOURCE_GIT_STATUS_FAILED");
        if (task.baselineCommit().equals(sourceHead) && sourceStatus.isBlank()) {
            runRequired(source, List.of("git", "merge", "--ff-only", commitSha), GIT_WRITE_TIMEOUT,
                    "LOCAL_SOURCE_FAST_FORWARD_FAILED", "无法将任务提交快进到源项目目录");
            String mergedHead = requiredOutput(source, List.of("git", "rev-parse", "HEAD"), "SOURCE_GIT_HEAD_UNAVAILABLE");
            if (!commitSha.equals(mergedHead)) {
                throw new ConflictException("LOCAL_SOURCE_FAST_FORWARD_UNCONFIRMED", "源项目快进完成后提交状态不一致");
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
        localConflicts.createOrRefresh(task.id());
    }

    private void requireNoStagedTaskPaths(TaskRow task, Path workspace, Path source, String commitSha) {
        String taskPaths = requiredOutputAllowEmpty(workspace,
                List.of("git", "diff", "--name-only", "-z", task.baselineCommit(), commitSha, "--"),
                "LOCAL_SOURCE_PATH_CHECK_FAILED");
        String stagedPaths = requiredOutputAllowEmpty(source,
                List.of("git", "diff", "--cached", "--name-only", "-z", "--"),
                "SOURCE_GIT_INDEX_CHECK_FAILED");
        Set<String> taskSet = new java.util.HashSet<>(List.of(taskPaths.split("\u0000", -1)));
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
            runRequired(workspace,
                    List.of("git", "diff", "--binary", "--full-index", "--output=" + patch,
                            task.baselineCommit(), commitSha, "--"),
                    GIT_WRITE_TIMEOUT, "LOCAL_SOURCE_PATCH_CREATE_FAILED", "无法生成任务同步补丁");
            ProcessResult check = runner.run(source,
                    List.of("git", "apply", "--check", "--binary", "--whitespace=nowarn", patch.toString()),
                    GIT_WRITE_TIMEOUT);
            if (check.timedOut() || check.outputTruncated()) {
                throw new ConflictException("LOCAL_SOURCE_CONFLICT", "任务补丁冲突检查失败；未修改源项目");
            }
            if (check.exitCode() != 0) {
                ProcessResult alreadyApplied = runner.run(source,
                        List.of("git", "apply", "--check", "--reverse", "--binary", "--whitespace=nowarn", patch.toString()),
                        GIT_WRITE_TIMEOUT);
                if (!alreadyApplied.timedOut() && !alreadyApplied.outputTruncated() && alreadyApplied.exitCode() == 0) return;
                String detail = scrub(check.output());
                throw new ConflictException("LOCAL_SOURCE_CONFLICT",
                        "源项目现有改动与任务变更冲突；未修改源项目，请处理冲突后重试"
                                + (detail.isBlank() ? "" : "：" + detail));
            }
            runRequired(source, List.of("git", "apply", "--binary", "--whitespace=nowarn", patch.toString()),
                    GIT_WRITE_TIMEOUT, "LOCAL_SOURCE_APPLY_FAILED", "任务补丁检查通过，但写入源项目失败");
        } catch (ConflictException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new ConflictException("LOCAL_SOURCE_SYNC_FAILED", "无法同步到源项目目录：" + safeMessage(failure));
        } finally {
            if (patch != null) {
                try { Files.deleteIfExists(patch); } catch (Exception ignored) { }
            }
        }
    }

    private Path workspace(TaskRow task) {
        if (task.worktreePath() == null || task.worktreePath().isBlank()) {
            throw new ConflictException("TASK_WORKTREE_MISSING", "任务执行目录不存在");
        }
        Path workspace = Path.of(task.worktreePath());
        worktrees.requireManaged(workspace);
        return workspace;
    }

    private void requireExactRepository(Path workspace) {
        String top = requiredOutput(workspace, List.of("git", "rev-parse", "--show-toplevel"), "GIT_REPOSITORY_UNAVAILABLE");
        try {
            if (!Path.of(top).toRealPath().equals(workspace.toRealPath())) {
                throw new ConflictException("TASK_REPOSITORY_MISMATCH", "任务执行目录不是当前 Git 仓库根目录");
            }
        } catch (ConflictException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new ConflictException("TASK_REPOSITORY_UNAVAILABLE", "无法确认任务 Git 仓库边界");
        }
    }

    private List<String> targetBranches(Path workspace, String remoteName, String sourceBranch) {
        String output = requiredOutputAllowEmpty(workspace,
                List.of("git", "for-each-ref", "--format=%(refname:short)", "refs/remotes/" + remoteName + "/"),
                "GIT_REMOTE_BRANCHES_FAILED");
        Set<String> branches = new LinkedHashSet<>();
        String prefix = remoteName + "/";
        for (String line : output.lines().toList()) {
            String value = line.strip();
            if (!value.startsWith(prefix)) continue;
            value = value.substring(prefix.length());
            if (value.isBlank() || value.equals("HEAD") || value.equals(sourceBranch)) continue;
            branches.add(value);
        }
        List<String> result = new ArrayList<>(branches);
        result.sort(Comparator.comparingInt(this::branchPriority).thenComparing(String::compareTo));
        return List.copyOf(result);
    }

    private String preferredTarget(Path workspace, TaskRow task, String remoteName, List<String> branches) {
        if (branches.isEmpty()) return null;
        ProjectRow project = projects.get(task.projectId());
        String projectBranch = optionalOutput(Path.of(project.rootPath()), List.of("git", "branch", "--show-current"));
        if (projectBranch != null && branches.contains(projectBranch)) return projectBranch;
        String remoteHead = optionalOutput(workspace,
                List.of("git", "symbolic-ref", "--short", "refs/remotes/" + remoteName + "/HEAD"));
        if (remoteHead != null && remoteHead.startsWith(remoteName + "/")) {
            String candidate = remoteHead.substring(remoteName.length() + 1);
            if (branches.contains(candidate)) return candidate;
        }
        return branches.getFirst();
    }

    private int branchPriority(String branch) {
        return switch (branch) {
            case "main" -> 0;
            case "master" -> 1;
            case "develop" -> 2;
            default -> 10;
        };
    }

    private String preferredRemote(Path workspace) {
        String output = requiredOutputAllowEmpty(workspace, List.of("git", "remote"), "GIT_REMOTE_UNAVAILABLE");
        List<String> remotes = output.lines().map(String::strip).filter(value -> !value.isBlank()).toList();
        if (remotes.contains("origin")) return "origin";
        if (remotes.isEmpty()) return null;
        if (remotes.size() == 1) return remotes.getFirst();
        throw new ConflictException("GIT_REMOTE_AMBIGUOUS", "仓库存在多个 Git remote 且没有 origin，无法确定发布目标");
    }

    private String requireCommitMessage(String value) {
        String normalized = value == null ? "" : value.strip();
        if (!COMMIT_MESSAGE.matcher(normalized).matches()) {
            throw new BadRequestException("COMMIT_MESSAGE_INVALID", "提交信息必须是 #加4位数字、下划线和提交说明，例如 #3032_修复任务发布流程");
        }
        return normalized;
    }

    private String normalizedBranch(String value) {
        String branch = singleLine(value, 250, "MERGE_TARGET_INVALID", "请选择有效的目标分支");
        ProcessResult result = runner.run(Path.of("."), List.of("git", "check-ref-format", "--branch", branch), GIT_READ_TIMEOUT);
        if (result.timedOut() || result.outputTruncated() || result.exitCode() != 0) {
            throw new BadRequestException("MERGE_TARGET_INVALID", "请选择有效的目标分支");
        }
        return branch;
    }

    private String singleLine(String value, int max, String code, String message) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank() || normalized.length() > max || normalized.indexOf('\n') >= 0 || normalized.indexOf('\r') >= 0) {
            throw new BadRequestException(code, message);
        }
        return normalized;
    }

    private String commitPrompt(TaskRow task, Path workspace) {
        return """
                你只负责生成一条 Git commit subject，不执行命令、不修改文件、不输出 Markdown。
                根据下面由 Loopper 确定性读取的实际 Git 变更摘要和任务目标，生成简洁、具体的中文提交说明。
                不要包含工单号、#、下划线、引号、换行、Markdown 或 conventional commit 前缀；控制在 50 个汉字以内。
                只返回提交说明本身。

                任务标题：%s
                任务目标：%s
                实际 Git 变更摘要：
                %s
                """.formatted(task.title(), taskGoal(task), publicationEvidence(task, workspace));
    }

    private String publicationEvidence(TaskRow task, Path workspace) {
        String stat = requiredOutputAllowEmpty(workspace,
                List.of("git", "diff", "--stat", "--no-ext-diff", task.baselineCommit(), "--"),
                "GIT_DIFF_SUMMARY_FAILED");
        String status = requiredOutputAllowEmpty(workspace,
                List.of("git", "status", "--short", "--untracked-files=all"),
                "GIT_STATUS_FAILED");
        String evidence = (stat.isBlank() ? "无已跟踪文件统计" : stat) + "\n" + status;
        return evidence.substring(0, Math.min(evidence.length(), 5000));
    }

    private String taskGoal(TaskRow task) {
        try {
            return tasks.goal(task.id());
        } catch (RuntimeException ignored) {
            return task.title();
        }
    }

    private String normalizeAiSubject(String output) {
        if (output == null) throw new ServiceUnavailableException("COMMIT_MESSAGE_AI_EMPTY", "AI 没有返回提交信息");
        String value = output.strip();
        if (value.startsWith("```") && value.endsWith("```")) {
            value = value.substring(3, value.length() - 3).strip();
            if (value.startsWith("text")) value = value.substring(4).strip();
        }
        value = value.lines().map(String::strip).filter(line -> !line.isBlank()).findFirst().orElse("");
        value = PREFIX.matcher(value).replaceFirst("").replace("`", "").replace("\"", "").strip();
        if (value.length() > 120) value = value.substring(0, 120).strip();
        if (value.isBlank()) throw new ServiceUnavailableException("COMMIT_MESSAGE_AI_EMPTY", "AI 没有返回可用的提交信息");
        return value;
    }

    private OpenCodeClient.OpenCodeModel configuredModel() {
        String configured = properties.getOpenCode().getModel();
        if (configured == null) return null;
        int separator = configured.indexOf('/');
        if (separator <= 0 || separator >= configured.length() - 1) return null;
        return new OpenCodeClient.OpenCodeModel(configured.substring(0, separator), configured.substring(separator + 1), null);
    }

    private void abortQuietly(OpenCodeClient.OpenCodeSession session) {
        try { openCode.abort(session); } catch (RuntimeException ignored) { }
    }

    private String requiredOutput(Path directory, List<String> argv, String code) {
        String output = requiredOutputAllowEmpty(directory, argv, code);
        if (output.isBlank()) throw new ConflictException(code, "Git 命令没有返回所需信息");
        return output.strip();
    }

    private String requiredOutputAllowEmpty(Path directory, List<String> argv, String code) {
        ProcessResult result = runner.run(directory, argv, GIT_READ_TIMEOUT);
        if (result.timedOut()) throw new ConflictException(code, "Git 状态检查超时");
        if (result.outputTruncated()) throw new ConflictException(code, "Git 状态输出过大，已停止操作");
        if (result.exitCode() != 0) throw new ConflictException(code, scrub(result.output()));
        return result.output() == null ? "" : result.output().stripTrailing();
    }

    private String optionalOutput(Path directory, List<String> argv) {
        try {
            ProcessResult result = runner.run(directory, argv, GIT_READ_TIMEOUT);
            if (result.timedOut() || result.outputTruncated() || result.exitCode() != 0 || result.output() == null || result.output().isBlank()) return null;
            return result.output().strip();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private void runRequired(Path directory, List<String> argv, Duration timeout, String code, String message) {
        ProcessResult result = runner.run(directory, argv, timeout);
        if (result.timedOut()) throw new ConflictException(code, message + "：命令超时");
        if (result.outputTruncated()) throw new ConflictException(code, message + "：命令输出过大");
        if (result.exitCode() != 0) {
            String detail = scrub(result.output());
            throw new ConflictException(code, detail.isBlank() ? message : message + "：" + detail);
        }
    }

    private PublicationStatus unavailable(TaskRow task, String reason) {
        return new PublicationStatus("UNAVAILABLE", false, reason, task.branchName(), null, null,
                null, null, null, List.of(), "UNKNOWN", null, false, null, 0, 0);
    }

    private RemoteRepository remoteRepository(String raw) {
        if (raw == null || raw.isBlank()) return new RemoteRepository("UNKNOWN", null);
        String host;
        String path;
        String scheme = "https";
        try {
            if (raw.contains("://")) {
                URI uri = URI.create(raw);
                host = uri.getHost();
                path = uri.getPath();
                if ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) scheme = uri.getScheme();
            } else {
                Matcher matcher = SCP_REMOTE.matcher(raw);
                if (!matcher.matches()) return new RemoteRepository("UNKNOWN", null);
                host = matcher.group(1);
                path = matcher.group(2);
            }
        } catch (RuntimeException invalid) {
            return new RemoteRepository("UNKNOWN", null);
        }
        if (host == null || host.isBlank() || path == null || path.isBlank()) return new RemoteRepository("UNKNOWN", null);
        path = path.replaceFirst("^/+", "").replaceFirst("\\.git/?$", "");
        if (path.isBlank()) return new RemoteRepository("UNKNOWN", null);
        String lowerHost = host.toLowerCase(Locale.ROOT);
        String provider = lowerHost.contains("github") ? "GITHUB" : lowerHost.contains("gitlab") ? "GITLAB" : "UNKNOWN";
        return new RemoteRepository(provider, scheme + "://" + host + "/" + path);
    }

    private String query(String key, String value) {
        return URLEncoder.encode(key, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String pathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String scrub(String value) {
        if (value == null) return "";
        String scrubbed = value.replaceAll("(?i)(https?://)[^/@\\s]+@", "$1***@").strip();
        return scrubbed.substring(0, Math.min(scrubbed.length(), 1200));
    }

    private String safeMessage(Throwable failure) {
        String message = failure == null || failure.getMessage() == null ? "任务发布状态不可用" : failure.getMessage();
        return scrub(message);
    }

    private record RemoteRepository(String provider, String webBase) { }

    public record PublicationStatus(String state, boolean available, String reason, String branch,
                                    String remoteName, String remoteUrl, String commitSha, String commitMessage,
                                    String targetBranch, List<String> targetBranches, String provider,
                                    String upstream, boolean hasChanges, String conflictSessionId,
                                    int conflictCount, int resolvedCount) { }
    public record CommitSuggestion(String subject, boolean aiGenerated) { }
    public record MergeRequestDraft(String provider, String sourceBranch, String targetBranch, String title,
                                    String description, String creationUrl) { }
}
