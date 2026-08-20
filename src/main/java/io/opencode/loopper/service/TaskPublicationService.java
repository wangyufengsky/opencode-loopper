package io.opencode.loopper.service;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.TaskPublicationState;
import io.opencode.loopper.domain.TaskState;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.persistence.TaskPublicationRow;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.runtime.GitWorktreeManager;
import io.opencode.loopper.runtime.OpenCodeClient;
import io.opencode.loopper.runtime.SafeProcessRunner;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Human-triggered publication for a successfully verified Git Task branch. */
@Service
public class TaskPublicationService {
    private static final Duration GIT_WRITE_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration AI_TIMEOUT = Duration.ofSeconds(75);
    private static final Pattern COMMIT_MESSAGE = Pattern.compile("^#[0-9]{4}_[^\\r\\n]{1,120}$");
    private static final int PUBLICATION_LOCK_STRIPES = 64;

    private final TaskService tasks;
    private final ProjectService projects;
    private final GitWorktreeManager worktrees;
    private final SafeProcessRunner runner;
    private final OpenCodeClient openCode;
    private final LoopperProperties properties;
    private final LocalSyncConflictService localConflicts;
    private final LoopperMapper mapper;
    private final LifecycleTransitionService lifecycle;
    private final GitLabMergeRequestClient gitlab;
    private final PublicationGitClient publicationGit;
    private final LocalSourceSynchronizer localSourceSynchronizer;
    private final TaskPublicationTracker publicationTracker;
    private final CommitMessagePromptFactory commitMessages;
    private final ReentrantLock[] taskLocks = lockStripes(PUBLICATION_LOCK_STRIPES);

    public TaskPublicationService(TaskService tasks, ProjectService projects, GitWorktreeManager worktrees,
                                  SafeProcessRunner runner, OpenCodeClient openCode, LoopperProperties properties,
                                  LocalSyncConflictService localConflicts, LoopperMapper mapper,
                                  LifecycleTransitionService lifecycle, GitLabMergeRequestClient gitlab) {
        this.tasks = tasks;
        this.projects = projects;
        this.worktrees = worktrees;
        this.runner = runner;
        this.openCode = openCode;
        this.properties = properties;
        this.localConflicts = localConflicts;
        this.mapper = mapper;
        this.lifecycle = lifecycle;
        this.gitlab = gitlab;
        this.publicationGit = new PublicationGitClient(runner, properties);
        this.localSourceSynchronizer = new LocalSourceSynchronizer(tasks, projects, runner, properties,
                localConflicts, publicationGit);
        this.publicationTracker = new TaskPublicationTracker(tasks, mapper, lifecycle, gitlab, publicationGit);
        this.commitMessages = new CommitMessagePromptFactory(tasks, publicationGit);
    }

    public PublicationStatus status(String taskId) {
        TaskRow task = tasks.get(taskId);
        if (!hasSuccessfulResult(task)) {
            return unavailable(task, "任务通过全部验收后才能提交");
        }
        if (GitWorktreeManager.DIRECT_BRANCH.equals(task.branchName())) {
            return withDelivery(unavailable(task, "直接执行任务没有隔离分支，不能由 Loopper 自动提交和推送"),
                    null, TaskPublicationState.NOT_APPLICABLE);
        }
        try {
            PublicationStatus inspected = withFrozenWorkspace(task, inspect(task));
            TaskPublicationRow row = observe(task, inspected);
            return withDelivery(inspected, row, null);
        } catch (RuntimeException failure) {
            TaskPublicationRow row = mapper.findTaskPublication(task.id()).orElse(null);
            return withDelivery(unavailable(task, safeMessage(failure)), row, null);
        }
    }

    public CommitSuggestion generateCommitMessage(String taskId) {
        TaskRow task = requirePublishableTask(taskId);
        requireNotMerged(task.id());
        PublicationStatus current = withFrozenWorkspace(task, inspect(task));
        if (!"READY".equals(current.state())) {
            throw new ConflictException("TASK_PUBLICATION_NOT_READY", "当前任务没有等待提交的文件变更");
        }
        if (!openCode.healthy()) {
            throw new ServiceUnavailableException("OPENCODE_UNAVAILABLE", "OpenCode 不可用，无法生成默认提交信息");
        }
        Path workspace = repository(task);
        OpenCodeClient.OpenCodeSession session;
        try {
            session = openCode.createReadOnlySession(workspace,
                    "OpenCode Loopper Commit Message (READ_ONLY)", configuredModel());
            openCode.promptAsync(session, commitMessages.prompt(task, workspace));
        } catch (RuntimeException failure) {
            throw new ServiceUnavailableException("COMMIT_MESSAGE_AI_FAILED", safeMessage(failure));
        }

        long deadline = System.nanoTime() + AI_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                OpenCodeClient.SessionStatus state = openCode.sessionStatus(session);
                if (state.completed()) {
                    String subject = commitMessages.normalizeSubject(openCode.sessionOutput(session));
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
        requireNotMerged(task.id());
        ReentrantLock lock = taskLocks[Math.floorMod(task.id().hashCode(), taskLocks.length)];
        if (!lock.tryLock()) {
            throw new ConflictException("TASK_PUBLICATION_ACTIVE", "当前任务正在提交或推送，请等待本次操作完成");
        }
        try {
            if (TaskState.AWAITING_DECISION.name().equals(task.state())) {
                task = tasks.preparePublicationWorkspace(task.id());
            }
            PublicationStatus before = inspect(task);
            if ("PUSHED".equals(before.state()) || "SYNCED_LOCAL".equals(before.state())
                    || "LOCAL_SYNC_CONFLICT".equals(before.state())) {
                if ("PUSHED".equals(before.state()) || "SYNCED_LOCAL".equals(before.state())) {
                    confirmAwaitingDecision(task, "PUBLICATION_ALREADY_CONFIRMED", before.commitSha());
                    tasks.releaseWorkspaceAfterTaskCommit(task.id());
                }
                return status(task.id());
            }
            Path workspace = repository(task);
            if ("READY".equals(before.state())) {
                String commitMessage = requireCommitMessage(requestedMessage);
                Path taskCheckout = workspace(task);
                runRequired(taskCheckout, List.of("git", "add", "--all"), GIT_WRITE_TIMEOUT,
                        "GIT_STAGE_FAILED", "无法暂存任务变更");
                runRequired(taskCheckout, List.of("git", "commit", "-m", commitMessage), GIT_WRITE_TIMEOUT,
                        "GIT_COMMIT_FAILED", "无法创建任务提交");
            } else if (!"COMMITTED".equals(before.state())) {
                throw new ConflictException("TASK_PUBLICATION_NOT_READY", before.reason() == null
                        ? "当前任务没有可提交或可推送的变更" : before.reason());
            }

            PublicationStatus committed = inspect(task);
            if ("COMMITTED".equals(committed.state())) observe(task, committed);
            if (!"COMMITTED".equals(committed.state()) && !"PUSHED".equals(committed.state())
                    && !"SYNCED_LOCAL".equals(committed.state())
                    && !"LOCAL_SYNC_CONFLICT".equals(committed.state())) {
                throw new ConflictException("GIT_COMMIT_STATE_INVALID", "提交完成后工作区状态不一致，已停止发布");
            }
            if ("PUSHED".equals(committed.state()) || "SYNCED_LOCAL".equals(committed.state())
                    || "LOCAL_SYNC_CONFLICT".equals(committed.state())) return status(task.id());
            if (committed.remoteName() == null) {
                if (registeredCheckout(task, workspace)) {
                    tasks.recordLocalSourceSync(task.id(), committed.commitSha(), "TASK_BRANCH_COMMIT");
                } else {
                    syncLocalSource(task, workspace, committed.commitSha());
                }
                PublicationStatus synced = inspect(task);
                if ("LOCAL_SYNC_CONFLICT".equals(synced.state())) return synced;
                if (!"SYNCED_LOCAL".equals(synced.state())) {
                    throw new ConflictException("LOCAL_SOURCE_SYNC_UNCONFIRMED", "源代码同步完成，但同步证据未能确认");
                }
                observe(task, synced);
                confirmAwaitingDecision(task, "LOCAL_COMMIT", committed.commitSha());
                tasks.releaseWorkspaceAfterTaskCommit(task.id());
                return status(task.id());
            }
            runRequired(workspace,
                    List.of("git", "push", "--set-upstream", committed.remoteName(),
                            "refs/heads/" + committed.branch() + ":refs/heads/" + committed.branch()),
                    GIT_WRITE_TIMEOUT, "GIT_PUSH_FAILED", "提交已创建，但推送失败；可以稍后继续推送");
            PublicationStatus pushed = inspect(task);
            if (!"PUSHED".equals(pushed.state())) {
                throw new ConflictException("GIT_PUSH_STATE_UNCONFIRMED", "Git push 返回成功，但远端跟踪分支尚未与本地提交一致");
            }
            observe(task, pushed);
            confirmAwaitingDecision(task, "REMOTE_PUSH", pushed.commitSha());
            tasks.releaseWorkspaceAfterTaskCommit(task.id());
            return status(task.id());
        } finally {
            lock.unlock();
        }
    }

    private static ReentrantLock[] lockStripes(int size) {
        ReentrantLock[] locks = new ReentrantLock[size];
        java.util.Arrays.setAll(locks, ignored -> new ReentrantLock());
        return locks;
    }

    public MergeRequestDraft mergeRequestDraft(String taskId, String targetBranch, String title, String description) {
        TaskRow task = requirePublishableTask(taskId);
        requireNotMerged(task.id());
        PublicationStatus current = status(taskId);
        if (!("PUSHED".equals(current.deliveryState()) || "MERGE_REQUEST_OPENED".equals(current.deliveryState())
                || "MERGE_REQUEST_CLOSED".equals(current.deliveryState()))) {
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
        PublicationGitClient.RemoteRepository remote = remoteRepository(current.remoteUrl());
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
        recordCreationRequest(task, current, target);
        return new MergeRequestDraft(remote.provider(), current.branch(), target, normalizedTitle, normalizedDescription, url);
    }

    public PublicationStatus reconcile(String taskId) {
        TaskRow task = requirePublishableTask(taskId);
        PublicationStatus inspected;
        TaskPublicationRow snapshot;
        try {
            inspected = inspect(task);
            snapshot = observe(task, inspected);
        } catch (RuntimeException failure) {
            snapshot = mapper.findTaskPublication(task.id()).orElse(null);
            inspected = unavailable(task, safeMessage(failure));
        }
        if (snapshot == null) throw new ConflictException("TASK_PUBLICATION_NOT_STARTED", "任务尚未产生可核对的发布提交");
        if (TaskPublicationState.MERGED.name().equals(snapshot.state())) return withDelivery(inspected, snapshot, null);
        PublicationGitClient.RemoteRepository remote = remoteRepository(snapshot.remoteUrl());
        if (!"GITLAB".equals(remote.provider())) {
            return withDelivery(inspected, recordCheckError(snapshot, "GitHub 合并状态暂未接入自动确认"), null);
        }
        String target = firstNonBlank(snapshot.targetBranch(), inspected.targetBranch());
        if (target == null) return withDelivery(inspected, recordCheckError(snapshot, "无法确定合并请求目标分支"), null);
        if (!gitlab.configuredFor(remote.host())) {
            return withDelivery(inspected, recordCheckError(snapshot, "未配置 GitLab 合并状态查询 Token"), null);
        }
        GitLabMergeRequestClient.Lookup lookup;
        try {
            lookup = gitlab.lookup(remote.host(), remote.projectPath(), snapshot.sourceBranch(), target, snapshot.taskCommitSha());
        } catch (GitLabMergeRequestClient.LookupException failure) {
            TaskPublicationRow latest = requireUnchangedSnapshot(task, snapshot);
            return withDelivery(inspected, recordCheckError(latest, failure.getMessage()), null);
        }
        TaskPublicationRow latest = requireUnchangedSnapshot(task, snapshot);
        if (lookup.mergeRequest() == null) {
            return withDelivery(inspected, recordCheck(latest, target, lookup.checkedAt(), null, latest.mergeRequestState()), null);
        }
        var mr = lookup.mergeRequest();
        TaskPublicationState targetState = switch (mr.state()) {
            case "opened" -> TaskPublicationState.MERGE_REQUEST_OPENED;
            case "closed" -> TaskPublicationState.MERGE_REQUEST_CLOSED;
            case "merged" -> TaskPublicationState.MERGED;
            default -> throw new ConflictException("GITLAB_MERGE_REQUEST_STATE_INVALID", "GitLab 返回了未知合并请求状态");
        };
        TaskPublicationRow updated = transitionPublication(latest, targetState, target, lookup.checkedAt(), mr);
        return withDelivery(inspected, updated, null);
    }

    private TaskPublicationRow observe(TaskRow task, PublicationStatus status) {
        return publicationTracker.observe(task, status);
    }

    private TaskPublicationRow createPublication(TaskRow task, PublicationStatus status, TaskPublicationState initial) {
        return publicationTracker.create(task, status, initial);
    }

    private TaskPublicationRow transitionPublication(TaskPublicationRow row, TaskPublicationState target,
                                                     String targetBranch, String checkedAt,
                                                     GitLabMergeRequestClient.MergeRequest mr) {
        return publicationTracker.transition(row, target, targetBranch, checkedAt, mr);
    }

    private void recordCreationRequest(TaskRow task, PublicationStatus status, String targetBranch) {
        publicationTracker.recordCreationRequest(task, status, targetBranch);
    }

    private TaskPublicationRow recordCheckError(TaskPublicationRow row, String message) {
        return publicationTracker.recordCheckError(row, message);
    }

    private TaskPublicationRow recordCheck(TaskPublicationRow row, String targetBranch, String checkedAt,
                                           String error, String mergeRequestState) {
        return publicationTracker.recordCheck(row, targetBranch, checkedAt, error, mergeRequestState);
    }

    private TaskPublicationRow requireUnchangedSnapshot(TaskRow task, TaskPublicationRow snapshot) {
        return publicationTracker.requireUnchangedSnapshot(task, snapshot);
    }

    private PublicationStatus withDelivery(PublicationStatus base, TaskPublicationRow row,
                                           TaskPublicationState fallback) {
        return publicationTracker.withDelivery(base, row, fallback);
    }

    private void requireNotMerged(String taskId) {
        publicationTracker.requireNotMerged(taskId);
    }

    private ConflictException mergedConflict() {
        return publicationTracker.mergedConflict();
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second != null && !second.isBlank() ? second : null;
    }

    private PublicationStatus inspect(TaskRow task) {
        Path workspace = repository(task);
        String branch = task.branchName();
        String taskRef = "refs/heads/" + branch;
        String head = requiredOutput(workspace, List.of("git", "rev-parse", "--verify", taskRef + "^{commit}"),
                "GIT_TASK_BRANCH_UNAVAILABLE");
        String currentBranch = optionalOutput(workspace, List.of("git", "branch", "--show-current"));
        boolean taskCheckedOut = branch.equals(currentBranch);
        String status = taskCheckedOut ? requiredOutputAllowEmpty(workspace,
                List.of("git", "status", "--porcelain=v1", "--untracked-files=all"), "GIT_STATUS_FAILED") : "";
        boolean hasChanges = taskCheckedOut && !status.isBlank();
        boolean committed = task.baselineCommit() != null && !head.equals(task.baselineCommit());
        String commitMessage = committed ? optionalOutput(workspace,
                List.of("git", "log", "-1", "--pretty=%s", taskRef)) : null;
        String remoteName = preferredRemote(workspace);
        if (remoteName == null) {
            boolean synced = !hasChanges && committed && tasks.hasLocalSourceSync(task.id(), head);
            LocalSyncConflictService.SessionView conflict = synced ? null : localConflicts.active(task.id());
            String state = hasChanges ? "READY" : synced ? "SYNCED_LOCAL"
                    : conflict != null ? "LOCAL_SYNC_CONFLICT" : committed ? "COMMITTED"
                    : taskCheckedOut ? "NO_CHANGES" : "UNAVAILABLE";
            String reason = "NO_CHANGES".equals(state) ? "任务没有产生可提交的文件变更"
                    : "SYNCED_LOCAL".equals(state) ? "任务提交已保留在原项目目录的本地任务分支"
                    : "LOCAL_SYNC_CONFLICT".equals(state) ? conflict.errorMessage()
                    : "UNAVAILABLE".equals(state) ? "项目已离开任务分支，但该任务分支尚无可发布提交" : null;
            if ("COMMITTED".equals(state) && (commitMessage == null || !COMMIT_MESSAGE.matcher(commitMessage).matches())) {
                state = "UNAVAILABLE";
                reason = "任务分支已有不符合 #四位数字_AI说明 格式的本地提交，请先在仓库中处理";
            }
            return new PublicationStatus(state, !"NO_CHANGES".equals(state) && !"UNAVAILABLE".equals(state), reason,
                    branch, null, null, head, commitMessage, null, List.of(), "UNKNOWN", null, hasChanges,
                    conflict == null ? null : conflict.id(), conflict == null ? 0 : conflict.conflictCount(),
                    conflict == null ? 0 : conflict.resolvedCount(), null, false, null, null, false, null, null);
        }
        String remoteUrl = requiredOutput(workspace, List.of("git", "remote", "get-url", remoteName), "GIT_REMOTE_UNAVAILABLE");
        String upstream = optionalOutput(workspace,
                List.of("git", "for-each-ref", "--format=%(upstream:short)", taskRef));
        String upstreamHead = upstream == null ? null : optionalOutput(workspace, List.of("git", "rev-parse", upstream));
        boolean pushed = !hasChanges && head.equals(upstreamHead);
        String state = hasChanges ? "READY" : pushed ? "PUSHED" : committed ? "COMMITTED"
                : taskCheckedOut ? "NO_CHANGES" : "UNAVAILABLE";
        String reason = "NO_CHANGES".equals(state) ? "任务没有产生可提交的文件变更"
                : "UNAVAILABLE".equals(state) ? "项目已离开任务分支，但该任务分支尚无可发布提交" : null;
        if ("COMMITTED".equals(state) && (commitMessage == null || !COMMIT_MESSAGE.matcher(commitMessage).matches())) {
            state = "UNAVAILABLE";
            reason = "任务分支已有不符合 #四位数字_AI说明 格式的本地提交，请先在仓库中处理";
        }
        List<String> targets = targetBranches(workspace, remoteName, branch);
        String target = preferredTarget(workspace, task, remoteName, targets);
        String provider = remoteRepository(remoteUrl).provider();
        return new PublicationStatus(state, !"NO_CHANGES".equals(state) && !"UNAVAILABLE".equals(state), reason, branch, remoteName,
                remoteUrl, head, commitMessage, target, targets, provider, upstream, hasChanges, null, 0, 0,
                null, false, null, null, false, null, null);
    }

    private TaskRow requirePublishableTask(String taskId) {
        TaskRow task = tasks.get(taskId);
        if (!hasSuccessfulResult(task)) {
            throw new ConflictException("TASK_NOT_SUCCEEDED", "任务通过全部验收后才能提交并发布");
        }
        if (GitWorktreeManager.DIRECT_BRANCH.equals(task.branchName())) {
            throw new ConflictException("DIRECT_TASK_PUBLICATION_UNSUPPORTED", "直接执行任务没有隔离分支，请在项目仓库中手工处理提交");
        }
        return task;
    }

    private boolean hasSuccessfulResult(TaskRow task) {
        if (TaskState.SUCCEEDED.name().equals(task.state())) return true;
        if (!TaskState.AWAITING_DECISION.name().equals(task.state())
                && !TaskState.COMPLETED.name().equals(task.state())) return false;
        var cycle = tasks.latestExecutionCycle(task.id());
        return cycle != null && "SUCCEEDED".equals(cycle.state());
    }

    private void confirmAwaitingDecision(TaskRow task, String confirmation, String commitSha) {
        if (TaskState.AWAITING_DECISION.name().equals(task.state())) {
            tasks.confirmPublishedResult(task.id(), confirmation, commitSha);
        }
    }

    private PublicationStatus withFrozenWorkspace(TaskRow task, PublicationStatus inspected) {
        if (!TaskState.AWAITING_DECISION.name().equals(task.state()) || !"UNAVAILABLE".equals(inspected.state())) {
            return inspected;
        }
        var checkpoint = tasks.latestWorkspaceCheckpoint(task.id());
        if (checkpoint == null || !"READY".equals(checkpoint.state())) return inspected;
        if (checkpoint.manifestJson() == null || "[]".equals(checkpoint.manifestJson().strip())) return inspected;
        return new PublicationStatus("READY", true, "任务改动已安全冻结，提交时会按 FIFO 恢复任务分支",
                inspected.branch(), inspected.remoteName(), inspected.remoteUrl(), inspected.commitSha(),
                inspected.commitMessage(), inspected.targetBranch(), inspected.targetBranches(), inspected.provider(),
                inspected.upstream(), true, inspected.conflictSessionId(), inspected.conflictCount(),
                inspected.resolvedCount(), inspected.deliveryState(), inspected.deliveryFinal(),
                inspected.creationRequestedAt(), inspected.mergeRequest(), inspected.reconciliationAvailable(),
                inspected.lastCheckError(), inspected.lastCheckedAt());
    }

    private void syncLocalSource(TaskRow task, Path workspace, String commitSha) {
        localSourceSynchronizer.sync(task, workspace, commitSha);
    }

    private Path workspace(TaskRow task) {
        if (task.worktreePath() == null || task.worktreePath().isBlank()) {
            throw new ConflictException("TASK_WORKTREE_MISSING", "任务执行目录不存在");
        }
        Path workspace = Path.of(task.worktreePath());
        ProjectRow project = projects.get(task.projectId());
        worktrees.requireExecutionWorkspace(workspace, Path.of(project.rootPath()),
                task.branchName(), task.baselineCommit());
        return workspace;
    }

    /** Returns the repository containing the Task ref without requiring that ref to be checked out. */
    private Path repository(TaskRow task) {
        if (task.worktreePath() == null || task.worktreePath().isBlank()) {
            throw new ConflictException("TASK_WORKTREE_MISSING", "任务执行目录不存在");
        }
        Path workspace = Path.of(task.worktreePath());
        requireExactRepository(workspace);
        ProjectRow project = projects.get(task.projectId());
        if (!registeredCheckout(task, workspace)) {
            worktrees.requireExecutionWorkspace(workspace, Path.of(project.rootPath()),
                    task.branchName(), task.baselineCommit());
        }
        return workspace;
    }

    private boolean registeredCheckout(TaskRow task, Path workspace) {
        try {
            return workspace.toRealPath().equals(Path.of(projects.get(task.projectId()).rootPath()).toRealPath());
        } catch (Exception failure) {
            throw new ConflictException("TASK_REPOSITORY_UNAVAILABLE", "无法确认任务 Git 仓库边界");
        }
    }

    private void requireExactRepository(Path workspace) {
        publicationGit.requireExactRepository(workspace);
    }

    private List<String> targetBranches(Path workspace, String remoteName, String sourceBranch) {
        return publicationGit.targetBranches(workspace, remoteName, sourceBranch);
    }

    private String preferredTarget(Path workspace, TaskRow task, String remoteName, List<String> branches) {
        Path projectRoot = Path.of(projects.get(task.projectId()).rootPath());
        return publicationGit.preferredTarget(workspace, projectRoot, task.sourceBranch(), remoteName, branches);
    }

    private String preferredRemote(Path workspace) {
        return publicationGit.preferredRemote(workspace);
    }

    private String requireCommitMessage(String value) {
        String normalized = value == null ? "" : value.strip();
        if (!COMMIT_MESSAGE.matcher(normalized).matches()) {
            throw new BadRequestException("COMMIT_MESSAGE_INVALID", "提交信息必须是 #加4位数字、下划线和提交说明，例如 #3032_修复任务发布流程");
        }
        return normalized;
    }

    private String normalizedBranch(String value) {
        return publicationGit.normalizedBranch(value);
    }

    private String singleLine(String value, int max, String code, String message) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank() || normalized.length() > max || normalized.indexOf('\n') >= 0 || normalized.indexOf('\r') >= 0) {
            throw new BadRequestException(code, message);
        }
        return normalized;
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
        return publicationGit.required(directory, argv, code);
    }

    private String requiredOutputAllowEmpty(Path directory, List<String> argv, String code) {
        return publicationGit.allowEmpty(directory, argv, code);
    }

    private String optionalOutput(Path directory, List<String> argv) {
        return publicationGit.optional(directory, argv);
    }

    private void runRequired(Path directory, List<String> argv, Duration timeout, String code, String message) {
        publicationGit.run(directory, argv, timeout, code, message);
    }

    private PublicationStatus unavailable(TaskRow task, String reason) {
        return new PublicationStatus("UNAVAILABLE", false, reason, task.branchName(), null, null,
                null, null, null, List.of(), "UNKNOWN", null, false, null, 0, 0,
                null, false, null, null, false, null, null);
    }

    private PublicationGitClient.RemoteRepository remoteRepository(String raw) {
        return publicationGit.remoteRepository(raw);
    }

    private String query(String key, String value) {
        return publicationGit.query(key, value);
    }

    private String pathSegment(String value) {
        return publicationGit.pathSegment(value);
    }

    private String scrub(String value) {
        return publicationGit.scrub(value);
    }

    private String safeMessage(Throwable failure) {
        return publicationGit.safeMessage(failure);
    }

    public record PublicationStatus(String state, boolean available, String reason, String branch,
                                    String remoteName, String remoteUrl, String commitSha, String commitMessage,
                                    String targetBranch, List<String> targetBranches, String provider,
                                    String upstream, boolean hasChanges, String conflictSessionId,
                                    int conflictCount, int resolvedCount, String deliveryState, boolean deliveryFinal,
                                    String creationRequestedAt, MergeRequestStatus mergeRequest,
                                    boolean reconciliationAvailable, String lastCheckError, String lastCheckedAt) { }
    public record MergeRequestStatus(String provider, long iid, String url, String state, String sourceBranch,
                                     String targetBranch, String headSha, String mergeCommitSha,
                                     String openedAt, String mergedAt, String checkedAt) { }
    public record CommitSuggestion(String subject, boolean aiGenerated) { }
    public record MergeRequestDraft(String provider, String sourceBranch, String targetBranch, String title,
                                    String description, String creationUrl) { }
}
