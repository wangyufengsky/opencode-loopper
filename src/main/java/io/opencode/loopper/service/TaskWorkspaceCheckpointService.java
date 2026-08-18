package io.opencode.loopper.service;

import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.domain.LifecycleMachineType;
import io.opencode.loopper.domain.LifecycleScopeType;
import io.opencode.loopper.domain.TaskFailure;
import io.opencode.loopper.domain.WorkspaceCheckpointState;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.persistence.TaskExecutionCycleRow;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.persistence.TaskWorkspaceCheckpointRow;
import io.opencode.loopper.runtime.DirectWorkspaceLeaseCoordinator;
import io.opencode.loopper.runtime.GitWorktreeManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Durable Git checkpoint saga used while a Task waits for its user's disposition. */
@Service
public class TaskWorkspaceCheckpointService {
    private final LoopperMapper mapper;
    private final ProjectService projects;
    private final GitWorktreeManager worktrees;
    private final LifecycleTransitionService lifecycle;
    private final ObjectMapper json;

    public TaskWorkspaceCheckpointService(LoopperMapper mapper, ProjectService projects,
                                          GitWorktreeManager worktrees, LifecycleTransitionService lifecycle,
                                          ObjectMapper json) {
        this.mapper = mapper;
        this.projects = projects;
        this.worktrees = worktrees;
        this.lifecycle = lifecycle;
        this.json = json;
    }

    public TaskWorkspaceCheckpointRow freeze(TaskRow task, TaskExecutionCycleRow cycle) {
        TaskWorkspaceCheckpointRow existing = mapper.findTaskWorkspaceCheckpointForCycle(cycle.id()).orElse(null);
        if (existing != null && WorkspaceCheckpointState.READY.name().equals(existing.state())) return existing;
        ProjectRow project = projects.get(task.projectId());
        Path root = Path.of(project.rootPath());
        DirectWorkspaceLeaseCoordinator.WorkspaceIdentity identity = DirectWorkspaceLeaseCoordinator.identify(root);
        String now = Instant.now().toString();
        TaskWorkspaceCheckpointRow row = existing;
        if (row == null) {
            String emptyManifest = "[]";
            row = new TaskWorkspaceCheckpointRow(UUID.randomUUID().toString(), task.id(), cycle.id(),
                    WorkspaceCheckpointState.CAPTURING.name(), null, identity.canonicalRoot(), identity.rootFingerprint(),
                    task.branchName() == null ? GitWorktreeManager.DIRECT_BRANCH : task.branchName(), task.sourceBranch(),
                    task.baselineCommit(), null, null, null, emptyManifest, sha256(emptyManifest), null,
                    null, null, now, now, 0);
            TaskWorkspaceCheckpointRow created = row;
            lifecycle.create(subject(created), created.state(), Map.of("cycleId", cycle.id()),
                    () -> mapper.insertTaskWorkspaceCheckpoint(created),
                    () -> new ConflictException("RECOVERY_CHECKPOINT_CREATE_CONFLICT", "Workspace checkpoint was created concurrently"));
        }
        if (GitWorktreeManager.DIRECT_BRANCH.equals(task.branchName())) {
            return block(row, "RECOVERY_DIRECT_CHECKPOINT_UNSUPPORTED",
                    "Direct workspace cannot be cleared and shared without overwriting user files");
        }
        try {
            GitWorktreeManager.WorkspaceCheckpoint frozen = worktrees.freezeWorkspace(root, task.id(), cycle.id(), task.branchName());
            String manifest = json.writeValueAsString(frozen.workspace().files());
            TaskWorkspaceCheckpointRow ready = new TaskWorkspaceCheckpointRow(row.id(), row.taskId(), row.cycleId(),
                    WorkspaceCheckpointState.READY.name(), frozen.workspace().snapshotId(), row.canonicalRoot(),
                    row.rootFingerprint(), row.branchName(), row.sourceBranch(), row.baselineCommit(),
                    frozen.checkpointRef(), frozen.checkpointCommit(), frozen.checkpointTree(), manifest, sha256(manifest),
                    frozen.stashCommit(), null, null, row.createdAt(), Instant.now().toString(), row.version());
            update(row, ready, LifecycleEvent.COMPLETE, Map.of("checkpointTree", frozen.checkpointTree(),
                    "changedFiles", frozen.workspace().files().size()));
            return mapper.findTaskWorkspaceCheckpoint(row.id()).orElse(ready);
        } catch (TaskFailure failure) {
            return block(mapper.findTaskWorkspaceCheckpoint(row.id()).orElse(row), failure.code(), failure.getMessage());
        } catch (Exception failure) {
            return block(mapper.findTaskWorkspaceCheckpoint(row.id()).orElse(row),
                    "RECOVERY_CHECKPOINT_CREATE_FAILED", failure.getMessage());
        }
    }

    public TaskWorkspaceCheckpointRow restore(TaskRow task, TaskWorkspaceCheckpointRow checkpoint) {
        boolean resuming = WorkspaceCheckpointState.RESTORING.name().equals(checkpoint.state());
        if (!WorkspaceCheckpointState.READY.name().equals(checkpoint.state()) && !resuming) {
            throw new ConflictException("RECOVERY_CHECKPOINT_NOT_READY", "Task checkpoint is not safe to restore");
        }
        ProjectRow project = projects.get(task.projectId());
        DirectWorkspaceLeaseCoordinator.WorkspaceIdentity current =
                DirectWorkspaceLeaseCoordinator.identify(Path.of(project.rootPath()));
        if (!checkpoint.canonicalRoot().equals(current.canonicalRoot())
                || !checkpoint.rootFingerprint().equals(current.rootFingerprint())) {
            throw new ConflictException("RECOVERY_WORKSPACE_FINGERPRINT_MISMATCH",
                    "Registered workspace identity changed while the Task was waiting");
        }
        TaskWorkspaceCheckpointRow restoring = checkpoint;
        if (!resuming) {
            restoring = copyState(checkpoint, WorkspaceCheckpointState.RESTORING, null, null);
            update(checkpoint, restoring, LifecycleEvent.RESTORE, Map.of());
            restoring = mapper.findTaskWorkspaceCheckpoint(checkpoint.id()).orElse(restoring);
        }
        try {
            Path root = Path.of(project.rootPath());
            if (!worktrees.workspaceMatchesCheckpointTree(root, checkpoint.branchName(), checkpoint.checkpointRef(),
                    checkpoint.checkpointCommit(), checkpoint.checkpointTree())) {
                worktrees.restoreWorkspaceCheckpoint(root, checkpoint.branchName(),
                        checkpoint.sourceBranch(), checkpoint.baselineCommit(), checkpoint.checkpointRef(),
                        checkpoint.checkpointCommit(), checkpoint.checkpointTree());
            }
            TaskWorkspaceCheckpointRow currentRestoring = mapper.findTaskWorkspaceCheckpoint(checkpoint.id()).orElse(restoring);
            TaskWorkspaceCheckpointRow restored = copyState(currentRestoring,
                    WorkspaceCheckpointState.RESTORED, null, null);
            update(currentRestoring, restored, LifecycleEvent.COMPLETE, Map.of("checkpointTree", checkpoint.checkpointTree()));
            return mapper.findTaskWorkspaceCheckpoint(checkpoint.id()).orElse(restored);
        } catch (TaskFailure failure) {
            TaskWorkspaceCheckpointRow currentRow = mapper.findTaskWorkspaceCheckpoint(checkpoint.id()).orElse(restoring);
            block(currentRow, failure.code(), failure.getMessage());
            throw new ConflictException(failure.code(), failure.getMessage());
        }
    }

    /** Resumes Git checkpoint sagas whose durable filesystem step outlived the application process. */
    public List<TaskWorkspaceCheckpointRow> recoverIncomplete() {
        return mapper.listIncompleteTaskWorkspaceCheckpoints().stream().map(row -> {
            TaskRow task = mapper.findTask(row.taskId()).orElse(null);
            if (task == null) return block(row, "RECOVERY_CHECKPOINT_TASK_MISSING", "Checkpoint Task no longer exists");
            try {
                if (WorkspaceCheckpointState.CAPTURING.name().equals(row.state())) {
                    TaskExecutionCycleRow cycle = mapper.findTaskExecutionCycle(row.cycleId()).orElse(null);
                    return cycle == null
                            ? block(row, "RECOVERY_CHECKPOINT_CYCLE_MISSING", "Checkpoint execution cycle no longer exists")
                            : freeze(task, cycle);
                }
                return restore(task, row);
            } catch (RuntimeException failure) {
                TaskWorkspaceCheckpointRow current = mapper.findTaskWorkspaceCheckpoint(row.id()).orElse(row);
                if (WorkspaceCheckpointState.BLOCKED.name().equals(current.state())) return current;
                return block(current, "RECOVERY_CHECKPOINT_RESUME_FAILED", failure.getMessage());
            }
        }).toList();
    }

    public TaskWorkspaceCheckpointRow latest(String taskId) {
        return mapper.latestTaskWorkspaceCheckpoint(taskId).orElse(null);
    }

    private TaskWorkspaceCheckpointRow block(TaskWorkspaceCheckpointRow row, String code, String message) {
        if (WorkspaceCheckpointState.BLOCKED.name().equals(row.state())) return row;
        TaskWorkspaceCheckpointRow blocked = copyState(row, WorkspaceCheckpointState.BLOCKED, code, bounded(message));
        update(row, blocked, LifecycleEvent.FAIL, Map.of("code", code));
        return mapper.findTaskWorkspaceCheckpoint(row.id()).orElse(blocked);
    }

    private TaskWorkspaceCheckpointRow copyState(TaskWorkspaceCheckpointRow row, WorkspaceCheckpointState state,
                                                  String code, String message) {
        return new TaskWorkspaceCheckpointRow(row.id(), row.taskId(), row.cycleId(), state.name(), row.snapshotId(),
                row.canonicalRoot(), row.rootFingerprint(), row.branchName(), row.sourceBranch(), row.baselineCommit(),
                row.checkpointRef(), row.checkpointCommit(), row.checkpointTree(), row.manifestJson(), row.manifestSha256(),
                row.stashCommit(), code, message, row.createdAt(), Instant.now().toString(), row.version());
    }

    private void update(TaskWorkspaceCheckpointRow from, TaskWorkspaceCheckpointRow to, LifecycleEvent event,
                        Map<String, ?> metadata) {
        lifecycle.transition(subject(from), from.state(), to.state(), event, to.blockerCode(), metadata,
                () -> mapper.updateTaskWorkspaceCheckpoint(to),
                () -> new ConflictException("RECOVERY_CHECKPOINT_VERSION_CONFLICT", "Workspace checkpoint changed concurrently"));
    }

    private LifecycleTransitionService.Subject subject(TaskWorkspaceCheckpointRow row) {
        return new LifecycleTransitionService.Subject(LifecycleMachineType.WORKSPACE_CHECKPOINT, row.id(),
                LifecycleScopeType.TASK, row.taskId());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    private static String bounded(String value) {
        if (value == null || value.isBlank()) return "Workspace checkpoint was blocked";
        return value.length() <= 2_000 ? value : value.substring(0, 2_000);
    }
}
