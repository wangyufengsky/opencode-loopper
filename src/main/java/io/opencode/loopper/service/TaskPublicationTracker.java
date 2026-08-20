package io.opencode.loopper.service;

import io.opencode.loopper.domain.LifecycleMachineType;
import io.opencode.loopper.domain.LifecycleScopeType;
import io.opencode.loopper.domain.TaskPublicationState;
import io.opencode.loopper.domain.TaskState;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.TaskPublicationRow;
import io.opencode.loopper.persistence.TaskRow;
import java.time.Instant;
import java.util.Objects;

/** Persists and projects the independent Task publication lifecycle. */
final class TaskPublicationTracker {
    private final TaskService tasks;
    private final LoopperMapper mapper;
    private final LifecycleTransitionService lifecycle;
    private final GitLabMergeRequestClient gitlab;
    private final PublicationGitClient git;

    TaskPublicationTracker(TaskService tasks, LoopperMapper mapper, LifecycleTransitionService lifecycle,
                           GitLabMergeRequestClient gitlab, PublicationGitClient git) {
        this.tasks = tasks;
        this.mapper = mapper;
        this.lifecycle = lifecycle;
        this.gitlab = gitlab;
        this.git = git;
    }

    TaskPublicationRow observe(TaskRow task, TaskPublicationService.PublicationStatus status) {
        TaskPublicationState observed = switch (status.state()) {
            case "COMMITTED" -> TaskPublicationState.COMMITTED;
            case "PUSHED" -> TaskPublicationState.PUSHED;
            case "SYNCED_LOCAL" -> TaskPublicationState.LOCAL_COMPLETED;
            default -> null;
        };
        TaskPublicationRow existing = mapper.findTaskPublication(task.id()).orElse(null);
        if (observed == null) return existing;
        if (existing == null) return create(task, status, observed);
        TaskPublicationState current = TaskPublicationState.valueOf(existing.state());
        if (current == TaskPublicationState.MERGED || current == TaskPublicationState.LOCAL_COMPLETED) return existing;
        existing = refreshRemoteMetadata(existing, status);
        if (rank(observed) <= rank(current)) return existing;
        return transition(existing, observed, firstNonBlank(existing.targetBranch(), status.targetBranch()),
                existing.lastCheckedAt(), null);
    }

    TaskPublicationRow create(TaskRow task, TaskPublicationService.PublicationStatus status,
                              TaskPublicationState initial) {
        String now = Instant.now().toString();
        TaskPublicationRow row = new TaskPublicationRow(task.id(), initial.name(), status.remoteName(),
                status.remoteUrl(), status.provider(), status.branch(), status.targetBranch(), status.commitSha(),
                status.commitMessage(), null, null, null, null, null, null, null, null, null, null, now, now, 0);
        lifecycle.create(subject(task.id()), initial.name(), java.util.Map.of("provider", status.provider()),
                () -> mapper.insertTaskPublication(row),
                () -> new ConflictException("TASK_PUBLICATION_CONFLICT", "任务发布状态已被并发创建"));
        return mapper.findTaskPublication(task.id()).orElseThrow();
    }

    TaskPublicationRow transition(TaskPublicationRow row, TaskPublicationState target, String targetBranch,
                                  String checkedAt, GitLabMergeRequestClient.MergeRequest mr) {
        TaskPublicationState current = TaskPublicationState.valueOf(row.state());
        if (current == TaskPublicationState.MERGED) return row;
        String now = Instant.now().toString();
        TaskPublicationRow updated = new TaskPublicationRow(row.taskId(), target.name(), row.remoteName(),
                row.remoteUrl(), row.provider(), row.sourceBranch(), targetBranch, row.taskCommitSha(),
                row.commitMessage(), row.creationRequestedAt(),
                mr == null ? row.mergeRequestIid() : Long.valueOf(mr.iid()),
                mr == null ? row.mergeRequestUrl() : mr.webUrl(),
                mr == null ? row.mergeRequestState() : mr.state(),
                mr == null ? row.mergeRequestHeadSha() : mr.headSha(),
                mr == null ? row.mergeCommitSha() : mr.mergeCommitSha(),
                mr == null ? row.mergeRequestOpenedAt() : firstNonBlank(row.mergeRequestOpenedAt(), mr.openedAt()),
                target == TaskPublicationState.MERGED && mr != null ? firstNonBlank(mr.mergedAt(), now) : row.mergedAt(),
                checkedAt, null, row.createdAt(), now, row.version());
        if (current == target) {
            mutate(updated);
        } else {
            lifecycle.transition(subject(row.taskId()), row.state(), target.name(), null,
                    java.util.Map.of("provider", row.provider()), () -> mapper.updateTaskPublication(updated),
                    this::conflict);
        }
        return mapper.findTaskPublication(row.taskId()).orElseThrow();
    }

    void recordCreationRequest(TaskRow task, TaskPublicationService.PublicationStatus status, String targetBranch) {
        TaskPublicationRow row = mapper.findTaskPublication(task.id())
                .orElseGet(() -> create(task, status, TaskPublicationState.PUSHED));
        if (TaskPublicationState.MERGED.name().equals(row.state())) throw mergedConflict();
        String now = Instant.now().toString();
        mutate(new TaskPublicationRow(row.taskId(), row.state(), row.remoteName(), row.remoteUrl(), row.provider(),
                row.sourceBranch(), targetBranch, row.taskCommitSha(), row.commitMessage(), now,
                row.mergeRequestIid(), row.mergeRequestUrl(), row.mergeRequestState(), row.mergeRequestHeadSha(),
                row.mergeCommitSha(), row.mergeRequestOpenedAt(), row.mergedAt(), row.lastCheckedAt(), null,
                row.createdAt(), now, row.version()));
    }

    TaskPublicationRow recordCheckError(TaskPublicationRow row, String message) {
        return recordCheck(row, row.targetBranch(), Instant.now().toString(),
                git.safeMessage(new RuntimeException(message)), row.mergeRequestState());
    }

    TaskPublicationRow recordCheck(TaskPublicationRow row, String targetBranch, String checkedAt,
                                   String error, String mergeRequestState) {
        String now = Instant.now().toString();
        TaskPublicationRow updated = new TaskPublicationRow(row.taskId(), row.state(), row.remoteName(),
                row.remoteUrl(), row.provider(), row.sourceBranch(), targetBranch, row.taskCommitSha(),
                row.commitMessage(), row.creationRequestedAt(), row.mergeRequestIid(), row.mergeRequestUrl(),
                mergeRequestState, row.mergeRequestHeadSha(), row.mergeCommitSha(), row.mergeRequestOpenedAt(),
                row.mergedAt(), checkedAt, error, row.createdAt(), now, row.version());
        mutate(updated);
        return mapper.findTaskPublication(row.taskId()).orElseThrow();
    }

    TaskPublicationRow requireUnchangedSnapshot(TaskRow taskSnapshot, TaskPublicationRow snapshot) {
        TaskRow latestTask = tasks.get(taskSnapshot.id());
        if (latestTask.version() != taskSnapshot.version()
                || !Objects.equals(latestTask.state(), taskSnapshot.state()) || !successful(latestTask)
                || !Objects.equals(latestTask.branchName(), taskSnapshot.branchName())) {
            throw conflict();
        }
        TaskPublicationRow latest = mapper.findTaskPublication(snapshot.taskId())
                .orElseThrow(() -> new ConflictException("TASK_PUBLICATION_CONFLICT", "任务发布记录已被删除"));
        if (latest.version() != snapshot.version()
                || !Objects.equals(latest.taskCommitSha(), snapshot.taskCommitSha())) throw conflict();
        return latest;
    }

    TaskPublicationService.PublicationStatus withDelivery(TaskPublicationService.PublicationStatus base,
                                                          TaskPublicationRow row,
                                                          TaskPublicationState fallback) {
        TaskPublicationState delivery = row == null
                ? (fallback == null ? TaskPublicationState.NOT_STARTED : fallback)
                : TaskPublicationState.valueOf(row.state());
        String remoteUrl = firstNonBlank(row == null ? null : row.remoteUrl(), base.remoteUrl());
        PublicationGitClient.RemoteRepository remote = git.remoteRepository(remoteUrl);
        boolean reconcile = row != null && "GITLAB".equals(row.provider()) && gitlab.configuredFor(remote.host())
                && row.taskCommitSha() != null && !row.taskCommitSha().isBlank();
        TaskPublicationService.MergeRequestStatus mr = row == null || row.mergeRequestIid() == null ? null
                : new TaskPublicationService.MergeRequestStatus(row.provider(), row.mergeRequestIid(),
                row.mergeRequestUrl(), row.mergeRequestState(), row.sourceBranch(), row.targetBranch(),
                row.mergeRequestHeadSha(), row.mergeCommitSha(), row.mergeRequestOpenedAt(), row.mergedAt(),
                row.lastCheckedAt());
        String operational = switch (delivery) {
            case COMMITTED, PUSHED, MERGE_REQUEST_OPENED, MERGE_REQUEST_CLOSED, MERGED -> delivery.name();
            case LOCAL_COMPLETED -> "SYNCED_LOCAL";
            default -> base.state();
        };
        boolean available = delivery != TaskPublicationState.NOT_STARTED
                && delivery != TaskPublicationState.NOT_APPLICABLE || base.available();
        return new TaskPublicationService.PublicationStatus(operational, available, base.reason(),
                firstNonBlank(row == null ? null : row.sourceBranch(), base.branch()),
                firstNonBlank(row == null ? null : row.remoteName(), base.remoteName()), remoteUrl,
                firstNonBlank(row == null ? null : row.taskCommitSha(), base.commitSha()),
                firstNonBlank(row == null ? null : row.commitMessage(), base.commitMessage()),
                firstNonBlank(row == null ? null : row.targetBranch(), base.targetBranch()), base.targetBranches(),
                firstNonBlank(row == null ? null : row.provider(), base.provider()), base.upstream(),
                base.hasChanges(), base.conflictSessionId(), base.conflictCount(), base.resolvedCount(),
                delivery.name(), delivery.terminal(), row == null ? null : row.creationRequestedAt(), mr, reconcile,
                row == null ? null : row.lastCheckError(), row == null ? null : row.lastCheckedAt());
    }

    void requireNotMerged(String taskId) {
        if (mapper.findTaskPublication(taskId).map(TaskPublicationRow::state)
                .filter(TaskPublicationState.MERGED.name()::equals).isPresent()) throw mergedConflict();
    }

    ConflictException mergedConflict() {
        return new ConflictException("TASK_PUBLICATION_MERGED", "任务合并请求已经合并，原任务发布状态不可再改变");
    }

    private TaskPublicationRow refreshRemoteMetadata(TaskPublicationRow row,
                                                      TaskPublicationService.PublicationStatus status) {
        String remoteName = firstNonBlank(status.remoteName(), row.remoteName());
        String remoteUrl = firstNonBlank(status.remoteUrl(), row.remoteUrl());
        String provider = firstNonBlank(status.provider(), row.provider());
        String sourceBranch = firstNonBlank(status.branch(), row.sourceBranch());
        if (Objects.equals(remoteName, row.remoteName()) && Objects.equals(remoteUrl, row.remoteUrl())
                && Objects.equals(provider, row.provider()) && Objects.equals(sourceBranch, row.sourceBranch())) {
            return row;
        }
        String now = Instant.now().toString();
        mutate(new TaskPublicationRow(row.taskId(), row.state(), remoteName, remoteUrl, provider, sourceBranch,
                row.targetBranch(), row.taskCommitSha(), row.commitMessage(), row.creationRequestedAt(),
                row.mergeRequestIid(), row.mergeRequestUrl(), row.mergeRequestState(), row.mergeRequestHeadSha(),
                row.mergeCommitSha(), row.mergeRequestOpenedAt(), row.mergedAt(), row.lastCheckedAt(),
                row.lastCheckError(), row.createdAt(), now, row.version()));
        return mapper.findTaskPublication(row.taskId()).orElseThrow();
    }

    private boolean successful(TaskRow task) {
        if (TaskState.SUCCEEDED.name().equals(task.state())) return true;
        if (!TaskState.AWAITING_DECISION.name().equals(task.state())
                && !TaskState.COMPLETED.name().equals(task.state())) return false;
        var cycle = tasks.latestExecutionCycle(task.id());
        return cycle != null && "SUCCEEDED".equals(cycle.state());
    }

    private void mutate(TaskPublicationRow updated) {
        lifecycle.mutateWithoutTransition(() -> mapper.updateTaskPublication(updated), this::conflict);
    }

    private LifecycleTransitionService.Subject subject(String taskId) {
        return new LifecycleTransitionService.Subject(LifecycleMachineType.TASK_PUBLICATION, taskId,
                LifecycleScopeType.TASK, taskId);
    }

    private int rank(TaskPublicationState state) {
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

    private ConflictException conflict() {
        return new ConflictException("TASK_PUBLICATION_CONFLICT", "任务发布状态已变化，请刷新后重试");
    }
}
