package io.opencode.loopper.service;

import io.opencode.loopper.domain.SessionState;
import io.opencode.loopper.domain.TaskFailure;
import io.opencode.loopper.domain.TaskQueueState;
import io.opencode.loopper.domain.TaskState;
import io.opencode.loopper.domain.WorkspaceLeaseState;
import io.opencode.loopper.persistence.ErrorEventRow;
import io.opencode.loopper.persistence.ExecutionSessionRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.persistence.TaskQueueRow;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.persistence.WorkspaceLeaseRow;
import io.opencode.loopper.runtime.DirectWorkspaceLeaseCoordinator;
import io.opencode.loopper.runtime.GitWorktreeManager;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Reconciles the independent Task, queue and workspace-lease state machines.
 *
 * <p>Filesystem and Git checks intentionally run before the coordinator's short
 * release transaction. A terminal Task alone never proves that its writer stopped
 * or that its registered checkout is safe to switch.</p>
 */
@Service
public class WorkspaceLeaseReconciliationService {
    public static final String TRIGGER_AUTO = "AUTO";
    public static final String TRIGGER_MANUAL = "MANUAL";
    public static final String TRIGGER_ARCHIVE = "ARCHIVE";
    public static final String TRIGGER_RESTART = "RESTART";

    private final LoopperMapper mapper;
    private final DirectWorkspaceLeaseCoordinator leases;
    private final GitWorktreeManager worktrees;
    private final TaskEventService events;
    private final TaskWorkspaceCheckpointService checkpoints;
    private final Map<String, ReentrantLock> rootLocks = new ConcurrentHashMap<>();

    public WorkspaceLeaseReconciliationService(LoopperMapper mapper,
                                               DirectWorkspaceLeaseCoordinator leases,
                                               GitWorktreeManager worktrees,
                                               TaskEventService events, TaskWorkspaceCheckpointService checkpoints) {
        this.mapper = mapper;
        this.leases = leases;
        this.worktrees = worktrees;
        this.events = events;
        this.checkpoints = checkpoints;
    }

    /** Reconciles one persisted holder; callers decide how to continue an admitted waiter. */
    public Result reconcileHolder(String holderTaskId, String trigger, String releaseReason) {
        String normalizedTrigger = trigger(trigger);
        TaskQueueRow initialQueue = mapper.findTaskQueue(holderTaskId).orElse(null);
        if (initialQueue == null && mapper.findTask(holderTaskId).isEmpty()) {
            return Result.blocked(holderTaskId, "TASK_QUEUE_HOLDER_MISSING",
                    "项目写租约仍引用已不存在的任务，必须人工检查持久化状态");
        }
        if (initialQueue == null) {
            return Result.blocked(holderTaskId, "TASK_QUEUE_LEASE_INVARIANT_VIOLATION",
                    "项目写租约 holder 缺少对应的任务队列记录");
        }
        ReentrantLock rootLock = rootLocks.computeIfAbsent(initialQueue.canonicalRoot(), ignored -> new ReentrantLock());
        rootLock.lock();
        try {
            return reconcileHolderLocked(holderTaskId, normalizedTrigger, releaseReason);
        } finally {
            rootLock.unlock();
        }
    }

    private Result reconcileHolderLocked(String holderTaskId, String normalizedTrigger, String releaseReason) {
        TaskRow task = mapper.findTask(holderTaskId).orElse(null);
        if (task == null) {
            return Result.blocked(holderTaskId, "TASK_QUEUE_HOLDER_MISSING",
                    "项目写租约仍引用已不存在的任务，必须人工检查持久化状态");
        }
        TaskQueueRow queue = mapper.findTaskQueue(holderTaskId).orElse(null);
        if (queue == null) {
            return Result.blocked(holderTaskId, "TASK_QUEUE_LEASE_INVARIANT_VIOLATION",
                    "项目写租约 holder 缺少对应的任务队列记录");
        }
        WorkspaceLeaseRow lease = mapper.findWorkspaceLease(queue.canonicalRoot()).orElse(null);
        if (!TaskQueueState.ADMITTED.name().equals(queue.state())) {
            if (lease == null || WorkspaceLeaseState.RELEASED.name().equals(lease.state())
                    || !holderTaskId.equals(lease.holderTaskId())) {
                return Result.alreadySettled(holderTaskId);
            }
            return Result.blocked(holderTaskId, "TASK_QUEUE_LEASE_INVARIANT_VIOLATION",
                    "活动项目写租约的 holder 队列记录不是 ADMITTED");
        }
        if (lease == null || WorkspaceLeaseState.RELEASED.name().equals(lease.state())
                || !holderTaskId.equals(lease.holderTaskId())) {
            return Result.blocked(holderTaskId, "TASK_QUEUE_LEASE_INVARIANT_VIOLATION",
                    "ADMITTED 队列记录与活动项目写租约 holder 不一致");
        }
        boolean decisionCheckpointReady = TaskState.AWAITING_DECISION.name().equals(task.state())
                && mapper.latestTaskWorkspaceCheckpoint(task.id())
                .map(checkpoint -> "READY".equals(checkpoint.state())).orElse(false);
        boolean publishedCheckpointRestored = TaskState.AWAITING_DECISION.name().equals(task.state())
                && mapper.latestTaskWorkspaceCheckpoint(task.id())
                .map(checkpoint -> "RESTORED".equals(checkpoint.state())).orElse(false)
                && mapper.findTaskPublication(task.id())
                .map(publication -> Set.of("PUSHED", "LOCAL_COMPLETED").contains(publication.state()))
                .orElse(false);
        boolean rollingPackageCheckpointReady = TaskState.PACKAGE_DESIGNING.name().equals(task.state())
                && "ROLLING_PACKAGES".equals(task.executionMode())
                && "RELEASE_BETWEEN_PACKAGES".equals(task.workspacePolicy())
                && mapper.latestTaskWorkspaceCheckpoint(task.id())
                .map(checkpoint -> "READY".equals(checkpoint.state())).orElse(false);
        boolean rollingFailureCheckpointReady = TaskState.WAITING_INPUT.name().equals(task.state())
                && "ROLLING_PACKAGES".equals(task.executionMode())
                && "RELEASE_BETWEEN_PACKAGES".equals(task.workspacePolicy())
                && mapper.currentTaskPackageRun(task.id())
                .map(run -> "PACKAGE_EXECUTION_FAILED".equals(run.waitingReasonCode())).orElse(false)
                && mapper.latestTaskWorkspaceCheckpoint(task.id())
                .map(checkpoint -> "READY".equals(checkpoint.state())).orElse(false);
        if (!TaskState.valueOf(task.state()).terminal()
                && !decisionCheckpointReady && !publishedCheckpointRestored
                && !rollingPackageCheckpointReady && !rollingFailureCheckpointReady) {
            return Result.blocked(holderTaskId, "TASK_QUEUE_HOLDER_ACTIVE",
                    TaskState.AWAITING_DECISION.name().equals(task.state())
                            ? "任务正在等待用户处置，但工作区尚未安全冻结"
                            : "当前项目写租约 holder 尚未进入终态");
        }

        WriterSafety writer = writerSafety(task.id(), lease);
        if (!writer.confirmed()) {
            return block(task, lease, normalizedTrigger, true, "SESSION_WRITER_UNCONFIRMED", writer.detail());
        }

        ProjectRow project = mapper.findProject(task.projectId()).orElse(null);
        if (project == null || project.rootPath() == null || project.rootPath().isBlank()) {
            return block(task, lease, normalizedTrigger, false, "DIRECT_WORKSPACE_UNAVAILABLE",
                    "任务所属项目或登记目录已不可用，拒绝释放写租约");
        }
        Path root = Path.of(project.rootPath());
        try {
            DirectWorkspaceLeaseCoordinator.WorkspaceIdentity identity = DirectWorkspaceLeaseCoordinator.identify(root);
            if (!lease.canonicalRoot().equals(identity.canonicalRoot())
                    || !lease.rootFingerprint().equals(identity.rootFingerprint())) {
                return block(task, lease, normalizedTrigger, false, "DIRECT_ROOT_FINGERPRINT_MISMATCH",
                        "项目登记目录的规范路径或稳定指纹已变化，拒绝切换分支或转移写租约");
            }
            if (task.branchName() != null && !GitWorktreeManager.DIRECT_BRANCH.equals(task.branchName())) {
                if (TaskState.CANCELLED.name().equals(task.state()) && worktrees.sourceCheckoutHasChanges(root)) {
                    var cycle = mapper.latestTaskExecutionCycle(task.id()).orElse(null);
                    if (cycle == null) return block(task, lease, normalizedTrigger, false,
                            "CANCELLATION_CHECKPOINT_UNAVAILABLE", "取消任务的修改尚无可保存的执行轮次，保留当前文件和分支");
                    var checkpoint = checkpoints.freeze(task, cycle);
                    if (!"READY".equals(checkpoint.state())) return block(task, lease, normalizedTrigger, false,
                            checkpoint.blockerCode(), checkpoint.blockerMessage());
                }
                if (worktrees.sourceCheckoutHasChanges(root)) {
                    return block(task, lease, normalizedTrigger, false, "SOURCE_BRANCH_WORKSPACE_DIRTY",
                            "当前 holder 的任务分支仍有未提交或未跟踪文件，清理前不会切换分支或转移写租约");
                }
                if (TaskState.CANCELLED.name().equals(task.state())) worktrees.restoreMainBranch(root, task.branchName());
                else worktrees.restoreSourceBranch(root, task.branchName(), task.sourceBranch());
            }
            DirectWorkspaceLeaseCoordinator.Release released = leases.releaseAfterWriterStopped(
                    root, task.id(), releaseReason == null || releaseReason.isBlank()
                            ? "TERMINAL_TASK_RECONCILED" : releaseReason);
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("trigger", normalizedTrigger);
            evidence.put("reason", releaseReason == null ? "TERMINAL_TASK_RECONCILED" : releaseReason);
            evidence.put("state", released.admittedNext() == null
                    ? WorkspaceLeaseState.RELEASED.name() : "TRANSFERRED");
            if (released.admittedNext() != null) evidence.put("admittedTaskId", released.admittedNext().taskId());
            events.emit(task.id(), "workspace.lease_released", evidence);
            return Result.released(task.id(), released.admittedNext());
        } catch (TaskFailure failure) {
            if (ownershipAlreadySettled(task.id())) return Result.alreadySettled(task.id());
            return block(task, lease, normalizedTrigger, false, failure.code(), failure.getMessage());
        }
    }

    /** Only exceptional terminal holders with a real waiter participate in background reconciliation. */
    public List<String> terminalHolderIdsWithQueuedWaiters() {
        return mapper.blockingWorkspaceLeasesWithQueuedWaiter().stream()
                .map(WorkspaceLeaseRow::holderTaskId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .filter(id -> mapper.findTask(id).map(row -> TaskState.valueOf(row.state()).terminal()
                        || TaskState.AWAITING_DECISION.name().equals(row.state())).orElse(false))
                .toList();
    }

    public boolean ownsActiveLease(String taskId) {
        return mapper.findActiveWorkspaceLeaseByHolder(taskId).isPresent()
                || mapper.findTaskQueue(taskId)
                .map(row -> TaskQueueState.ADMITTED.name().equals(row.state())).orElse(false);
    }

    private Result block(TaskRow task, WorkspaceLeaseRow lease, String trigger, boolean writerUnconfirmed,
                         String code, String detail) {
        boolean changed = false;
        try {
            changed = leases.retainBlocked(lease.canonicalRoot(), task.id(), writerUnconfirmed,
                    writerUnconfirmed ? lease.writerSessionId() : null, code);
        } catch (TaskFailure ignoredConcurrentRetention) {
            if (ownershipAlreadySettled(task.id())) return Result.alreadySettled(task.id());
        }
        if (changed) {
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("trigger", trigger);
            evidence.put("code", code);
            evidence.put("detail", safe(detail));
            evidence.put("state", writerUnconfirmed
                    ? WorkspaceLeaseState.RELEASE_PENDING.name() : WorkspaceLeaseState.HELD.name());
            events.emit(task.id(), "workspace.lease_reconcile_blocked", evidence);
        }
        return Result.blocked(task.id(), code, safe(detail));
    }

    private WriterSafety writerSafety(String taskId, WorkspaceLeaseRow lease) {
        if (mapper.listVerifierRuntimes(taskId).stream()
                .anyMatch(runtime -> List.of("STARTING", "RUNNING", "STOPPING", "DISCONNECTED")
                        .contains(runtime.state()))) {
            return WriterSafety.blocked("任务仍有未确认停止的托管验证运行时");
        }
        List<ExecutionSessionRow> sessions = mapper.listSessions(taskId);
        if (sessions.stream().anyMatch(session -> !SessionState.valueOf(session.state()).terminal())) {
            return WriterSafety.blocked("任务仍有未确认终止的可写 Session");
        }
        if (lease.writerSessionId() != null) {
            ExecutionSessionRow writer = sessions.stream()
                    .filter(session -> lease.writerSessionId().equals(session.id())).findFirst().orElse(null);
            if (writer == null || !SessionState.valueOf(writer.state()).terminal()) {
                return WriterSafety.blocked("租约记录的可写 Session 尚未被确认终止");
            }
        }
        Set<String> confirmed = mapper.listErrors(taskId).stream()
                .filter(error -> "SESSION_ABORT_CLEANUP_CONFIRMED".equals(error.code()) && error.sessionId() != null)
                .map(ErrorEventRow::sessionId).collect(Collectors.toSet());
        boolean unresolvedAbort = mapper.listErrors(taskId).stream()
                .anyMatch(error -> "SESSION_ABORT_UNCONFIRMED".equals(error.code())
                        && error.sessionId() != null && !confirmed.contains(error.sessionId()));
        return unresolvedAbort ? WriterSafety.blocked("历史写入 Session 的终止状态仍未确认") : WriterSafety.safe();
    }

    private boolean ownershipAlreadySettled(String taskId) {
        return !ownsActiveLease(taskId);
    }

    private static String trigger(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase();
        if (!List.of(TRIGGER_AUTO, TRIGGER_MANUAL, TRIGGER_ARCHIVE, TRIGGER_RESTART).contains(normalized)) {
            throw new IllegalArgumentException("Unknown workspace lease reconciliation trigger: " + value);
        }
        return normalized;
    }

    private static String safe(String value) {
        String detail = value == null || value.isBlank() ? "项目写租约暂时不能安全释放" : value;
        return detail.length() <= 1_000 ? detail : detail.substring(0, 1_000);
    }

    private record WriterSafety(boolean confirmed, String detail) {
        private static WriterSafety safe() { return new WriterSafety(true, null); }
        private static WriterSafety blocked(String detail) { return new WriterSafety(false, detail); }
    }

    public record Result(String holderTaskId, boolean released, boolean alreadySettled,
                         String blockerCode, String blockerMessage,
                         DirectWorkspaceLeaseCoordinator.QueueSnapshot admittedNext) {
        public boolean blocked() { return !released && !alreadySettled; }
        private static Result released(String holderTaskId, DirectWorkspaceLeaseCoordinator.QueueSnapshot next) {
            return new Result(holderTaskId, true, false, null, null, next);
        }
        private static Result alreadySettled(String holderTaskId) {
            return new Result(holderTaskId, false, true, null, null, null);
        }
        private static Result blocked(String holderTaskId, String code, String message) {
            return new Result(holderTaskId, false, false, code, message, null);
        }
    }
}
