package io.opencode.loopper.service;

import io.opencode.loopper.domain.SessionState;
import io.opencode.loopper.domain.TaskExecutionMode;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.runtime.DirectWorkspaceLeaseCoordinator;
import java.nio.file.Path;
import org.springframework.stereotype.Service;

/** Owns an application directory per report Task, independent of the source project's writer lease. */
@Service
public final class TemplateWorkspaceService {
    private final TemplateGitSnapshotService snapshots;
    private final DirectWorkspaceLeaseCoordinator leases;
    private final LoopperMapper mapper;
    private final TemplateGitCaptureGuard gitGuard;

    TemplateWorkspaceService(TemplateGitSnapshotService snapshots, DirectWorkspaceLeaseCoordinator leases, LoopperMapper mapper, TemplateGitCaptureGuard gitGuard) {
        this.gitGuard = gitGuard; this.snapshots = snapshots; this.leases = leases; this.mapper = mapper;
    }

    public static boolean applies(TaskRow task) { return task != null && TaskExecutionMode.TEMPLATE_REPORT.name().equals(task.executionMode()); }

    /** Only formal Start may call this method. Parameter confirmation never creates this directory. */
    public DirectWorkspaceLeaseCoordinator.WorkspaceIdentity prepareStart(TaskRow task) {
        requireTemplate(task);
        return DirectWorkspaceLeaseCoordinator.identifyDirectory(snapshots.prepareDirectory(task.id()));
    }

    public DirectWorkspaceLeaseCoordinator.Admission admitInTransaction(TaskRow task, DirectWorkspaceLeaseCoordinator.WorkspaceIdentity identity) {
        requireTemplate(task);
        return leases.acquireOrEnqueueInTransaction(identity, task.id(), "MANUAL", null);
    }

    public Path root(TaskRow task) {
        requireTemplate(task);
        Path expected = snapshots.workspace(task.id());
        try {
            if (java.nio.file.Files.isSymbolicLink(expected) || java.nio.file.Files.isSymbolicLink(expected.getParent())) {
                throw new java.io.IOException("symbolic link");
            }
            return expected.toRealPath();
        } catch (java.io.IOException failure) { throw new io.opencode.loopper.domain.TaskFailure("TEMPLATE_WORKSPACE_UNAVAILABLE", "模板执行目录不可访问或身份已改变"); }
    }

    public void requireWritable(TaskRow task) {
        requireTemplate(task);
        leases.requireWritableLease(root(task), task.id());
        if (task.worktreePath() != null && !Path.of(task.worktreePath()).toAbsolutePath().normalize().equals(root(task))) {
            throw new ConflictException("TEMPLATE_WORKSPACE_CHANGED", "模板任务目录与冻结执行目录不一致");
        }
    }

    /** The caller must additionally prove its local worker and any create-without-id obligation have stopped. */
    public boolean releaseStopped(TaskRow task) {
        requireTemplate(task);
        if (!gitGuard.stopped(task.id())) return false;
        if (mapper.listSessions(task.id()).stream().anyMatch(row -> !SessionState.valueOf(row.state()).terminal())
                || !mapper.activeJudgeRuns(task.id()).isEmpty()) return false;
        var queue = mapper.findTaskQueue(task.id()).orElse(null);
        if (queue == null || !java.util.Set.of("ADMITTED", "QUEUED").contains(queue.state())) return true;
        if (queue.state().equals("QUEUED")) leases.cancelQueued(task.id());
        else leases.releaseAfterWriterStopped(root(task), task.id(), "TEMPLATE_EXECUTION_STOPPED");
        return true;
    }

    private static void requireTemplate(TaskRow task) {
        if (!applies(task)) throw new BadRequestException("TEMPLATE_TASK_REQUIRED", "该操作只适用于模板任务");
    }
}
