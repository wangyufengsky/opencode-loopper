package io.opencode.loopper.service;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.LifecycleMachineType;
import io.opencode.loopper.domain.LifecycleScopeType;
import io.opencode.loopper.domain.TaskPublicationState;
import io.opencode.loopper.domain.TaskState;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.persistence.TaskPublicationRow;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Human-triggered publication for a successfully verified Git Task branch. */
@Service
public class TaskPublicationService {
    private static final Duration GIT_READ_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration GIT_WRITE_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration AI_TIMEOUT = Duration.ofSeconds(75);
    private static final Pattern COMMIT_MESSAGE = Pattern.compile("^#[0-9]{4}_[^\\r\\n]{1,120}$");
    private static final Pattern PREFIX = Pattern.compile("^#[0-9]{4}_");
    private static final Pattern SCP_REMOTE = Pattern.compile("^(?:[^@/]+@)?([^:/]+):(.+)$");
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
        RemoteRepository remote = remoteRepository(snapshot.remoteUrl());
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
        TaskPublicationState observed = switch (status.state()) {
            case "COMMITTED" -> TaskPublicationState.COMMITTED;
            case "PUSHED" -> TaskPublicationState.PUSHED;
            case "SYNCED_LOCAL" -> TaskPublicationState.LOCAL_COMPLETED;
            default -> null;
        };
        TaskPublicationRow existing = mapper.findTaskPublication(task.id()).orElse(null);
        if (observed == null) return existing;
        if (existing == null) return createPublication(task, status, observed);
        TaskPublicationState current = TaskPublicationState.valueOf(existing.state());
        if (current == TaskPublicationState.MERGED || current == TaskPublicationState.LOCAL_COMPLETED) return existing;
        existing = refreshRemoteMetadata(existing, status);
        if (publicationRank(observed) <= publicationRank(current)) return existing;
        return transitionPublication(existing, observed, firstNonBlank(existing.targetBranch(), status.targetBranch()),
                existing.lastCheckedAt(), null);
    }

    private TaskPublicationRow refreshRemoteMetadata(TaskPublicationRow row, PublicationStatus status) {
        String remoteName = firstNonBlank(status.remoteName(), row.remoteName());
        String remoteUrl = firstNonBlank(status.remoteUrl(), row.remoteUrl());
        String provider = firstNonBlank(status.provider(), row.provider());
        String sourceBranch = firstNonBlank(status.branch(), row.sourceBranch());
        if (java.util.Objects.equals(remoteName, row.remoteName())
                && java.util.Objects.equals(remoteUrl, row.remoteUrl())
                && java.util.Objects.equals(provider, row.provider())
                && java.util.Objects.equals(sourceBranch, row.sourceBranch())) {
            return row;
        }
        String now = Instant.now().toString();
        TaskPublicationRow updated = new TaskPublicationRow(row.taskId(), row.state(), remoteName, remoteUrl,
                provider, sourceBranch, row.targetBranch(), row.taskCommitSha(), row.commitMessage(), row.creationRequestedAt(),
                row.mergeRequestIid(), row.mergeRequestUrl(), row.mergeRequestState(), row.mergeRequestHeadSha(),
                row.mergeCommitSha(), row.mergeRequestOpenedAt(), row.mergedAt(), row.lastCheckedAt(), row.lastCheckError(),
                row.createdAt(), now, row.version());
        lifecycle.mutateWithoutTransition(() -> mapper.updateTaskPublication(updated),
                () -> new ConflictException("TASK_PUBLICATION_CONFLICT", "任务发布状态已变化，请刷新后重试"));
        return mapper.findTaskPublication(row.taskId()).orElseThrow();
    }

    private TaskPublicationRow createPublication(TaskRow task, PublicationStatus status, TaskPublicationState initial) {
        String now = Instant.now().toString();
        TaskPublicationRow row = new TaskPublicationRow(task.id(), initial.name(), status.remoteName(), status.remoteUrl(),
                status.provider(), status.branch(), status.targetBranch(), status.commitSha(), status.commitMessage(),
                null, null, null, null, null, null, null, null, null, null, now, now, 0);
        lifecycle.create(publicationSubject(task.id()), initial.name(), java.util.Map.of("provider", status.provider()),
                () -> mapper.insertTaskPublication(row), () -> new ConflictException("TASK_PUBLICATION_CONFLICT", "任务发布状态已被并发创建"));
        return mapper.findTaskPublication(task.id()).orElseThrow();
    }

    private TaskPublicationRow transitionPublication(TaskPublicationRow row, TaskPublicationState target,
                                                     String targetBranch, String checkedAt,
                                                     GitLabMergeRequestClient.MergeRequest mr) {
        TaskPublicationState current = TaskPublicationState.valueOf(row.state());
        if (current == TaskPublicationState.MERGED) return row;
        String now = Instant.now().toString();
        TaskPublicationRow updated = new TaskPublicationRow(row.taskId(), target.name(), row.remoteName(), row.remoteUrl(),
                row.provider(), row.sourceBranch(), targetBranch, row.taskCommitSha(), row.commitMessage(), row.creationRequestedAt(),
                mr == null ? row.mergeRequestIid() : Long.valueOf(mr.iid()), mr == null ? row.mergeRequestUrl() : mr.webUrl(),
                mr == null ? row.mergeRequestState() : mr.state(), mr == null ? row.mergeRequestHeadSha() : mr.headSha(),
                mr == null ? row.mergeCommitSha() : mr.mergeCommitSha(),
                mr == null ? row.mergeRequestOpenedAt() : firstNonBlank(row.mergeRequestOpenedAt(), mr.openedAt()),
                target == TaskPublicationState.MERGED && mr != null ? firstNonBlank(mr.mergedAt(), now) : row.mergedAt(),
                checkedAt, null, row.createdAt(), now, row.version());
        if (current == target) {
            lifecycle.mutateWithoutTransition(() -> mapper.updateTaskPublication(updated),
                    () -> new ConflictException("TASK_PUBLICATION_CONFLICT", "任务发布状态已变化，请刷新后重试"));
            return mapper.findTaskPublication(row.taskId()).orElseThrow();
        }
        lifecycle.transition(publicationSubject(row.taskId()), row.state(), target.name(), null,
                java.util.Map.of("provider", row.provider()), () -> mapper.updateTaskPublication(updated),
                () -> new ConflictException("TASK_PUBLICATION_CONFLICT", "任务发布状态已变化，请刷新后重试"));
        return mapper.findTaskPublication(row.taskId()).orElseThrow();
    }

    private void recordCreationRequest(TaskRow task, PublicationStatus status, String targetBranch) {
        TaskPublicationRow row = mapper.findTaskPublication(task.id()).orElseGet(() -> createPublication(task, status, TaskPublicationState.PUSHED));
        if (TaskPublicationState.MERGED.name().equals(row.state())) throw mergedConflict();
        String now = Instant.now().toString();
        TaskPublicationRow updated = new TaskPublicationRow(row.taskId(), row.state(), row.remoteName(), row.remoteUrl(),
                row.provider(), row.sourceBranch(), targetBranch, row.taskCommitSha(), row.commitMessage(), now,
                row.mergeRequestIid(), row.mergeRequestUrl(), row.mergeRequestState(), row.mergeRequestHeadSha(),
                row.mergeCommitSha(), row.mergeRequestOpenedAt(), row.mergedAt(), row.lastCheckedAt(), null,
                row.createdAt(), now, row.version());
        lifecycle.mutateWithoutTransition(() -> mapper.updateTaskPublication(updated),
                () -> new ConflictException("TASK_PUBLICATION_CONFLICT", "任务发布状态已变化，请刷新后重试"));
    }

    private TaskPublicationRow recordCheckError(TaskPublicationRow row, String message) {
        return recordCheck(row, row.targetBranch(), Instant.now().toString(), safeMessage(new RuntimeException(message)), row.mergeRequestState());
    }

    private TaskPublicationRow recordCheck(TaskPublicationRow row, String targetBranch, String checkedAt,
                                           String error, String mergeRequestState) {
        String now = Instant.now().toString();
        TaskPublicationRow updated = new TaskPublicationRow(row.taskId(), row.state(), row.remoteName(), row.remoteUrl(),
                row.provider(), row.sourceBranch(), targetBranch, row.taskCommitSha(), row.commitMessage(), row.creationRequestedAt(),
                row.mergeRequestIid(), row.mergeRequestUrl(), mergeRequestState, row.mergeRequestHeadSha(), row.mergeCommitSha(),
                row.mergeRequestOpenedAt(), row.mergedAt(), checkedAt, error, row.createdAt(), now, row.version());
        lifecycle.mutateWithoutTransition(() -> mapper.updateTaskPublication(updated),
                () -> new ConflictException("TASK_PUBLICATION_CONFLICT", "任务发布状态已变化，请刷新后重试"));
        return mapper.findTaskPublication(row.taskId()).orElseThrow();
    }

    private TaskPublicationRow requireUnchangedSnapshot(TaskRow taskSnapshot, TaskPublicationRow snapshot) {
        TaskRow latestTask = tasks.get(taskSnapshot.id());
        if (latestTask.version() != taskSnapshot.version()
                || !java.util.Objects.equals(latestTask.state(), taskSnapshot.state())
                || !hasSuccessfulResult(latestTask)
                || !java.util.Objects.equals(latestTask.branchName(), taskSnapshot.branchName())) {
            throw new ConflictException("TASK_PUBLICATION_CONFLICT", "查询期间任务状态已变化，请重试");
        }
        TaskPublicationRow latest = mapper.findTaskPublication(snapshot.taskId())
                .orElseThrow(() -> new ConflictException("TASK_PUBLICATION_CONFLICT", "任务发布记录已被删除"));
        if (latest.version() != snapshot.version() || !java.util.Objects.equals(latest.taskCommitSha(), snapshot.taskCommitSha())) {
            throw new ConflictException("TASK_PUBLICATION_CONFLICT", "查询期间任务发布状态已变化，请重试");
        }
        return latest;
    }

    private PublicationStatus withDelivery(PublicationStatus base, TaskPublicationRow row, TaskPublicationState fallback) {
        TaskPublicationState delivery = row == null ? (fallback == null ? TaskPublicationState.NOT_STARTED : fallback)
                : TaskPublicationState.valueOf(row.state());
        String remoteUrl = firstNonBlank(row == null ? null : row.remoteUrl(), base.remoteUrl());
        RemoteRepository remote = remoteRepository(remoteUrl);
        boolean reconcile = row != null && "GITLAB".equals(row.provider()) && gitlab.configuredFor(remote.host())
                && row.taskCommitSha() != null && !row.taskCommitSha().isBlank();
        MergeRequestStatus mr = row == null || row.mergeRequestIid() == null ? null
                : new MergeRequestStatus(row.provider(), row.mergeRequestIid(), row.mergeRequestUrl(), row.mergeRequestState(),
                row.sourceBranch(), row.targetBranch(), row.mergeRequestHeadSha(), row.mergeCommitSha(),
                row.mergeRequestOpenedAt(), row.mergedAt(), row.lastCheckedAt());
        String operational = switch (delivery) {
            case COMMITTED, PUSHED, MERGE_REQUEST_OPENED, MERGE_REQUEST_CLOSED, MERGED -> delivery.name();
            case LOCAL_COMPLETED -> "SYNCED_LOCAL";
            default -> base.state();
        };
        boolean available = delivery != TaskPublicationState.NOT_STARTED && delivery != TaskPublicationState.NOT_APPLICABLE
                ? true : base.available();
        return new PublicationStatus(operational, available, base.reason(),
                firstNonBlank(row == null ? null : row.sourceBranch(), base.branch()),
                firstNonBlank(row == null ? null : row.remoteName(), base.remoteName()), remoteUrl,
                firstNonBlank(row == null ? null : row.taskCommitSha(), base.commitSha()),
                firstNonBlank(row == null ? null : row.commitMessage(), base.commitMessage()),
                firstNonBlank(row == null ? null : row.targetBranch(), base.targetBranch()), base.targetBranches(),
                firstNonBlank(row == null ? null : row.provider(), base.provider()), base.upstream(), base.hasChanges(), base.conflictSessionId(), base.conflictCount(),
                base.resolvedCount(), delivery.name(), delivery.terminal(), row == null ? null : row.creationRequestedAt(),
                mr, reconcile, row == null ? null : row.lastCheckError(), row == null ? null : row.lastCheckedAt());
    }

    private void requireNotMerged(String taskId) {
        if (mapper.findTaskPublication(taskId).map(TaskPublicationRow::state).filter(TaskPublicationState.MERGED.name()::equals).isPresent()) {
            throw mergedConflict();
        }
    }

    private ConflictException mergedConflict() {
        return new ConflictException("TASK_PUBLICATION_MERGED", "任务合并请求已经合并，原任务发布状态不可再改变");
    }

    private LifecycleTransitionService.Subject publicationSubject(String taskId) {
        return new LifecycleTransitionService.Subject(LifecycleMachineType.TASK_PUBLICATION, taskId,
                LifecycleScopeType.TASK, taskId);
    }

    private int publicationRank(TaskPublicationState state) {
        return switch (state) {
            case NOT_STARTED, NOT_APPLICABLE -> 0;
            case COMMITTED -> 1;
            case PUSHED -> 2;
            case MERGE_REQUEST_OPENED, MERGE_REQUEST_CLOSED -> 3;
            case MERGED, LOCAL_COMPLETED -> 4;
        };
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
        if (task.sourceBranch() != null && branches.contains(task.sourceBranch())) return task.sourceBranch();
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
        var checkpoint = tasks.latestWorkspaceCheckpoint(task.id());
        if (TaskState.AWAITING_DECISION.name().equals(task.state())
                && checkpoint != null && "READY".equals(checkpoint.state())) {
            String stat = requiredOutputAllowEmpty(workspace,
                    List.of("git", "diff", "--stat", "--no-ext-diff", checkpoint.baselineCommit(),
                            checkpoint.checkpointTree(), "--"),
                    "GIT_DIFF_SUMMARY_FAILED");
            String status = requiredOutputAllowEmpty(workspace,
                    List.of("git", "diff", "--name-status", "--no-ext-diff", checkpoint.baselineCommit(),
                            checkpoint.checkpointTree(), "--"),
                    "GIT_STATUS_FAILED");
            String evidence = (stat.isBlank() ? "无文件统计" : stat) + "\n" + status;
            return evidence.substring(0, Math.min(evidence.length(), 5000));
        }
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
                null, null, null, List.of(), "UNKNOWN", null, false, null, 0, 0,
                null, false, null, null, false, null, null);
    }

    private RemoteRepository remoteRepository(String raw) {
        if (raw == null || raw.isBlank()) return new RemoteRepository("UNKNOWN", null, null, null);
        String host;
        String path;
        String scheme = null;
        try {
            if (raw.contains("://")) {
                URI uri = URI.create(raw);
                host = uri.getHost();
                path = uri.getPath();
                if ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) {
                    scheme = uri.getScheme().toLowerCase(Locale.ROOT);
                }
            } else {
                Matcher matcher = SCP_REMOTE.matcher(raw);
                if (!matcher.matches()) return new RemoteRepository("UNKNOWN", null, null, null);
                host = matcher.group(1);
                path = matcher.group(2);
            }
        } catch (RuntimeException invalid) {
            return new RemoteRepository("UNKNOWN", null, null, null);
        }
        if (host == null || host.isBlank() || path == null || path.isBlank()) return new RemoteRepository("UNKNOWN", null, null, null);
        path = path.replaceFirst("^/+", "").replaceFirst("\\.git/?$", "");
        if (path.isBlank()) return new RemoteRepository("UNKNOWN", null, null, null);
        String lowerHost = host.toLowerCase(Locale.ROOT);
        String configuredGitLabHost = properties.getPublication().getGitlab().getHost();
        String provider = configuredGitLabHost != null && lowerHost.equalsIgnoreCase(configuredGitLabHost.strip())
                ? "GITLAB" : lowerHost.contains("github") ? "GITHUB" : lowerHost.contains("gitlab") ? "GITLAB" : "UNKNOWN";
        if (configuredHttpWebHost(lowerHost)) scheme = "http";
        else if (scheme == null) scheme = "https";
        return new RemoteRepository(provider, scheme + "://" + host + "/" + path, lowerHost, path);
    }

    private boolean configuredHttpWebHost(String lowerHost) {
        return properties.getPublication().getHttpWebHosts().stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.strip().toLowerCase(Locale.ROOT))
                .anyMatch(lowerHost::equals);
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

    private record RemoteRepository(String provider, String webBase, String host, String projectPath) { }

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
