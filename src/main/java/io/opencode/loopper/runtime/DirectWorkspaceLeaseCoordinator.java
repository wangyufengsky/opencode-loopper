package io.opencode.loopper.runtime;

import io.opencode.loopper.domain.TaskFailure;
import io.opencode.loopper.domain.WorkspaceLeaseState;
import io.opencode.loopper.domain.TaskQueueState;
import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.domain.LifecycleMachineType;
import io.opencode.loopper.domain.LifecycleScopeType;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.TaskQueueRow;
import io.opencode.loopper.persistence.WorkspaceLeaseRow;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Persistent admission control for tasks which edit a registered directory in place.
 *
 * <p>Tasks now edit their registered checkout in place so IDE-bound tools and verifiers
 * share one authoritative directory. Exactly one task may hold a writer lease for one
 * canonical {@code toRealPath()} root. A terminal task
 * whose old writer has not been positively stopped stays {@code RELEASE_PENDING}; this
 * class deliberately has no force-release operation.</p>
 */
@Component
public class DirectWorkspaceLeaseCoordinator {
    private static final String MODE_DIRECT = "DIRECT";

    private static final int CONCURRENCY_RETRIES = 3;

    private final LoopperMapper mapper;
    private final LifecycleTransitionService lifecycle;
    private final TransactionTemplate transactions;

    public DirectWorkspaceLeaseCoordinator(LoopperMapper mapper, LifecycleTransitionService lifecycle,
                                           PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.lifecycle = lifecycle;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * Idempotently admit a task when the root is free, otherwise append it to the
     * root's persistent FIFO queue. A unique lease holder and optimistic updates
     * make a concurrent contender fail closed rather than create two writers.
     */
    public Admission acquireOrEnqueue(Path root, String taskId, String source, String writerSessionId) {
        WorkspaceIdentity workspace = identify(root);
        String normalizedSource = source(source);
        RuntimeException lastConflict = null;
        for (int attempt = 0; attempt < CONCURRENCY_RETRIES; attempt++) {
            try {
                Admission result = transactions.execute(status -> acquire(workspace, taskId, normalizedSource, writerSessionId));
                if (result == null) throw new TaskFailure("DIRECT_LEASE_TRANSACTION_FAILED", "In-place workspace admission did not return a result");
                return result;
            } catch (RuntimeException failure) {
                if (!concurrencyFailure(failure) || attempt + 1 == CONCURRENCY_RETRIES) {
                    if (concurrencyFailure(failure)) {
                        throw new TaskFailure("DIRECT_LEASE_CONCURRENT_CONFLICT",
                                "Direct workspace admission conflicted repeatedly; no writer was admitted");
                    }
                    throw failure;
                }
                lastConflict = failure;
                Thread.yield();
            }
        }
        throw new TaskFailure("DIRECT_LEASE_CONCURRENT_CONFLICT", safeMessage(lastConflict));
    }

    /** Records the remote writer id without changing queue ownership. */
    public LeaseSnapshot heartbeat(Path root, String taskId, String writerSessionId) {
        WorkspaceIdentity workspace = identify(root);
        return required(transactions.execute(status -> {
            WorkspaceLeaseRow lease = holder(workspace, taskId);
            if (!WorkspaceLeaseState.HELD.name().equals(lease.state())
                    && !WorkspaceLeaseState.RELEASE_PENDING.name().equals(lease.state())) {
                throw new TaskFailure("DIRECT_LEASE_NOT_HELD", "Direct workspace lease is not active");
            }
            updateLease(lease(lease, lease.state(), writerSessionId == null ? lease.writerSessionId() : writerSessionId,
                    now(), null, lease.releaseReason()));
            return snapshot(mapper.findWorkspaceLease(workspace.canonicalRoot()).orElseThrow(), workspace, queue(taskId));
        }));
    }

    /**
     * Keeps the lease blocking when a previous writer cannot be confirmed stopped.
     * This is intentionally separate from task terminality and has no automatic expiry.
     */
    public LeaseSnapshot markWriterUnconfirmed(Path root, String taskId, String writerSessionId, String reason) {
        WorkspaceIdentity workspace = identify(root);
        return required(transactions.execute(status -> {
            WorkspaceLeaseRow lease = holder(workspace, taskId);
            if (WorkspaceLeaseState.RELEASED.name().equals(lease.state())) {
                throw new TaskFailure("DIRECT_LEASE_NOT_HELD", "A released direct workspace lease cannot be marked pending");
            }
            String detail = reason == null || reason.isBlank() ? "WRITER_TERMINATION_UNCONFIRMED" : bounded(reason);
            updateLease(lease(lease, WorkspaceLeaseState.RELEASE_PENDING.name(),
                    writerSessionId == null ? lease.writerSessionId() : writerSessionId, now(), null, detail));
            return snapshot(mapper.findWorkspaceLease(workspace.canonicalRoot()).orElseThrow(), workspace, queue(taskId));
        }));
    }

    /** Clears a positively-terminated writer while retaining ownership for a paused/retrying task. */
    public LeaseSnapshot retainAfterWriterStopped(Path root, String taskId, String reason) {
        WorkspaceIdentity workspace = identify(root);
        return required(transactions.execute(status -> {
            WorkspaceLeaseRow lease = holder(workspace, taskId);
            String detail = reason == null || reason.isBlank() ? null : bounded(reason);
            updateLease(lease(lease, WorkspaceLeaseState.HELD.name(), null, now(), null, detail));
            return snapshot(mapper.findWorkspaceLease(workspace.canonicalRoot()).orElseThrow(), workspace, queue(taskId));
        }));
    }

    /**
     * Persists a fail-closed blocker without resolving or trusting the workspace path.
     * This method can only retain the existing holder; it cannot release or transfer ownership.
     *
     * @return whether the persisted blocker changed
     */
    public boolean retainBlocked(String canonicalRoot, String taskId, boolean writerUnconfirmed,
                                 String writerSessionId, String reason) {
        Boolean changed = transactions.execute(status -> {
            WorkspaceLeaseRow lease = mapper.findWorkspaceLease(canonicalRoot)
                    .orElseThrow(() -> new TaskFailure("DIRECT_LEASE_MISSING", "No direct workspace lease exists for this root"));
            if (!taskId.equals(lease.holderTaskId()) || WorkspaceLeaseState.RELEASED.name().equals(lease.state())) {
                throw new TaskFailure("DIRECT_LEASE_NOT_HOLDER", "Task does not hold this direct workspace lease");
            }
            String nextState = writerUnconfirmed ? WorkspaceLeaseState.RELEASE_PENDING.name() : WorkspaceLeaseState.HELD.name();
            String nextWriter = writerUnconfirmed ? writerSessionId : null;
            String detail = reason == null || reason.isBlank() ? "WORKSPACE_LEASE_RECONCILIATION_BLOCKED" : bounded(reason);
            if (nextState.equals(lease.state()) && java.util.Objects.equals(nextWriter, lease.writerSessionId())
                    && detail.equals(lease.releaseReason())) return false;
            updateLease(lease(lease, nextState, nextWriter, now(), null, detail));
            return true;
        });
        if (changed == null) throw new TaskFailure("DIRECT_LEASE_TRANSACTION_FAILED", "Direct workspace blocker was not persisted");
        return changed;
    }

    /** A new writer may start only for the current HELD owner, never for RELEASE_PENDING. */
    public LeaseSnapshot requireWritableLease(Path root, String taskId) {
        WorkspaceIdentity workspace = identify(root);
        WorkspaceLeaseRow lease = holder(workspace, taskId);
        if (!WorkspaceLeaseState.HELD.name().equals(lease.state())) {
            throw new TaskFailure("DIRECT_WRITER_TERMINATION_UNCONFIRMED",
                    "The previous direct writer is not confirmed terminal; writable takeover is blocked");
        }
        return snapshot(lease, workspace, queue(taskId));
    }

    /**
     * Releases an admitted task only after its caller has positively confirmed the
     * writer terminal. In the same transaction it marks the current queue row
     * finished and admits exactly the earliest compatible queued row.
     */
    public Release releaseAfterWriterStopped(Path root, String taskId, String reason) {
        WorkspaceIdentity workspace = identify(root);
        return required(transactions.execute(status -> release(workspace, taskId, reason)));
    }

    /** Cancels a waiting row. It never releases a holder or overrides RELEASE_PENDING. */
    public QueueSnapshot cancelQueued(String taskId) {
        return required(transactions.execute(status -> {
            TaskQueueRow row = queue(taskId);
            if (TaskQueueState.ADMITTED.name().equals(row.state())) {
                throw new TaskFailure("DIRECT_QUEUE_HOLDER_CANNOT_CANCEL", "An admitted task must confirm writer termination before release");
            }
            if (TaskQueueState.QUEUED.name().equals(row.state())) {
                updateQueue(queue(row, TaskQueueState.CANCELLED.name(), null, now()));
            }
            return queueSnapshot(mapper.findTaskQueue(taskId).orElseThrow());
        }));
    }

    /** Restart code can inspect every root still capable of blocking a direct writer. */
    public List<BlockingLease> blockingLeases() {
        return mapper.blockingWorkspaceLeases().stream().map(this::blocking).toList();
    }

    /** Resolve aliases before persistence; the hash is intentionally stable across restarts. */
    public static WorkspaceIdentity identify(Path input) {
        if (input == null) throw new TaskFailure("DIRECT_WORKSPACE_REQUIRED", "Direct workspace root is required");
        try {
            Path canonical = input.toRealPath();
            if (!Files.isDirectory(canonical)) {
                throw new TaskFailure("DIRECT_WORKSPACE_NOT_DIRECTORY", "Direct workspace root must be a directory");
            }
            BasicFileAttributes attributes = Files.readAttributes(canonical, BasicFileAttributes.class);
            Object fileKey = attributes.fileKey();
            // Some Linux filesystems can immediately reuse an inode after deletion. Combining
            // the stable file key with birth time distinguishes that replacement while keeping
            // the fingerprint stable when ordinary files inside the directory change.
            String stableFileIdentity = "fileKey:" + (fileKey == null ? "unavailable" : fileKey)
                    + "\u0000created:" + attributes.creationTime().toInstant();
            String root = canonical.toString();
            return new WorkspaceIdentity(root, sha256(root + "\u0000" + stableFileIdentity));
        } catch (TaskFailure failure) {
            throw failure;
        } catch (Exception failure) {
            throw new TaskFailure("DIRECT_WORKSPACE_UNAVAILABLE", "Direct workspace root cannot be resolved: " + safeMessage(failure));
        }
    }

    private Admission acquire(WorkspaceIdentity workspace, String taskId, String source, String writerSessionId) {
        TaskQueueRow existingQueue = mapper.findTaskQueue(taskId).orElse(null);
        if (existingQueue != null) {
            requireSameWorkspace(existingQueue, workspace);
            WorkspaceLeaseRow existingLease = mapper.findWorkspaceLease(workspace.canonicalRoot()).orElse(null);
            if (TaskQueueState.ADMITTED.name().equals(existingQueue.state()) && existingLease != null
                    && taskId.equals(existingLease.holderTaskId())
                    && !WorkspaceLeaseState.RELEASED.name().equals(existingLease.state())) {
                return admission(TaskQueueState.ADMITTED.name(), existingLease, existingQueue, workspace);
            }
            if (TaskQueueState.QUEUED.name().equals(existingQueue.state())) {
                return admission(TaskQueueState.QUEUED.name(), existingLease, existingQueue, workspace);
            }
            throw new TaskFailure("DIRECT_QUEUE_NOT_ADMITTABLE", "Task already has a " + existingQueue.state() + " direct queue record");
        }

        WorkspaceLeaseRow lease = mapper.findWorkspaceLease(workspace.canonicalRoot()).orElse(null);
        String timestamp = now();
        long position = mapper.nextQueuePosition(workspace.canonicalRoot());
        if (lease == null) {
            WorkspaceLeaseRow held = new WorkspaceLeaseRow(workspace.canonicalRoot(), workspace.rootFingerprint(), MODE_DIRECT,
                    taskId, writerSessionId, WorkspaceLeaseState.HELD.name(), timestamp, timestamp, null, null, 0);
            createLease(held);
            TaskQueueRow admitted = new TaskQueueRow(taskId, workspace.canonicalRoot(), workspace.rootFingerprint(), position,
                    source, TaskQueueState.ADMITTED.name(), timestamp, timestamp, null, 0);
            createQueue(admitted);
            return admission(TaskQueueState.ADMITTED.name(), held, admitted, workspace);
        }
        if (WorkspaceLeaseState.RELEASED.name().equals(lease.state())) {
            // A released lease has no writer to protect, so refresh its fingerprint. This also
            // lets a safely idle workspace cross fingerprint-algorithm upgrades without weakening
            // the fail-closed check for HELD or RELEASE_PENDING leases.
            WorkspaceLeaseRow held = new WorkspaceLeaseRow(lease.canonicalRoot(), workspace.rootFingerprint(), MODE_DIRECT,
                    taskId, writerSessionId, WorkspaceLeaseState.HELD.name(), timestamp, timestamp, null, null, lease.version());
            updateLease(held);
            TaskQueueRow admitted = new TaskQueueRow(taskId, workspace.canonicalRoot(), workspace.rootFingerprint(), position,
                    source, TaskQueueState.ADMITTED.name(), timestamp, timestamp, null, 0);
            createQueue(admitted);
            return admission(TaskQueueState.ADMITTED.name(),
                    mapper.findWorkspaceLease(workspace.canonicalRoot()).orElseThrow(), admitted, workspace);
        }
        requireSameWorkspace(lease, workspace);
        TaskQueueRow queued = new TaskQueueRow(taskId, workspace.canonicalRoot(), workspace.rootFingerprint(), position,
                source, TaskQueueState.QUEUED.name(), timestamp, null, null, 0);
        createQueue(queued);
        return admission(TaskQueueState.QUEUED.name(), lease, queued, workspace);
    }

    private Release release(WorkspaceIdentity workspace, String taskId, String reason) {
        WorkspaceLeaseRow lease = holder(workspace, taskId);
        TaskQueueRow current = queue(taskId);
        if (!TaskQueueState.ADMITTED.name().equals(current.state())) {
            throw new TaskFailure("DIRECT_QUEUE_NOT_ADMITTED", "Only an admitted task can release a direct workspace");
        }
        updateQueue(queue(current, TaskQueueState.FINISHED.name(), current.admittedAt(), now()));
        Optional<TaskQueueRow> next = mapper.nextQueuedTask(workspace.canonicalRoot());
        String timestamp = now();
        if (next.isEmpty()) {
            updateLease(new WorkspaceLeaseRow(lease.canonicalRoot(), lease.rootFingerprint(), MODE_DIRECT,
                    null, null, WorkspaceLeaseState.RELEASED.name(), lease.acquiredAt(), timestamp, timestamp,
                    reason == null || reason.isBlank() ? "WRITER_STOPPED" : bounded(reason), lease.version()));
            return new Release(snapshot(mapper.findWorkspaceLease(workspace.canonicalRoot()).orElseThrow(), workspace, queue(taskId)), null);
        }
        TaskQueueRow admitted = next.get();
        requireSameWorkspace(admitted, workspace);
        updateQueue(queue(admitted, TaskQueueState.ADMITTED.name(), timestamp, null));
        updateLease(new WorkspaceLeaseRow(lease.canonicalRoot(), lease.rootFingerprint(), MODE_DIRECT,
                admitted.taskId(), null, WorkspaceLeaseState.HELD.name(), timestamp, timestamp, null, null, lease.version()));
        return new Release(snapshot(mapper.findWorkspaceLease(workspace.canonicalRoot()).orElseThrow(), workspace, queue(taskId)),
                queueSnapshot(mapper.findTaskQueue(admitted.taskId()).orElseThrow()));
    }

    private WorkspaceLeaseRow holder(WorkspaceIdentity workspace, String taskId) {
        WorkspaceLeaseRow lease = mapper.findWorkspaceLease(workspace.canonicalRoot())
                .orElseThrow(() -> new TaskFailure("DIRECT_LEASE_MISSING", "No direct workspace lease exists for this root"));
        requireSameWorkspace(lease, workspace);
        if (!taskId.equals(lease.holderTaskId())) {
            throw new TaskFailure("DIRECT_LEASE_NOT_HOLDER", "Task does not hold this direct workspace lease");
        }
        return lease;
    }

    private TaskQueueRow queue(String taskId) {
        return mapper.findTaskQueue(taskId)
                .orElseThrow(() -> new TaskFailure("DIRECT_QUEUE_MISSING", "Task has no direct workspace queue record"));
    }

    private void updateLease(WorkspaceLeaseRow row) {
        WorkspaceLeaseRow current = mapper.findWorkspaceLease(row.canonicalRoot())
                .orElseThrow(() -> new TaskFailure("DIRECT_LEASE_MISSING", "No direct workspace lease exists for this root"));
        boolean sameState = current.state().equals(row.state());
        boolean ownershipTransfer = sameState
                && !java.util.Objects.equals(current.holderTaskId(), row.holderTaskId());
        if (sameState && !ownershipTransfer) {
            lifecycle.mutateWithoutTransition(() -> mapper.updateWorkspaceLeaseDetails(row),
                    () -> new TaskFailure("DIRECT_LEASE_CONCURRENT_CONFLICT", "Direct workspace lease changed concurrently; no update was accepted"));
        } else {
            lifecycle.transition(leaseSubject(row), current.state(), row.state(),
                    ownershipTransfer ? LifecycleEvent.TRANSFER : null, row.releaseReason(), java.util.Map.of(),
                    () -> sameState ? mapper.updateWorkspaceLeaseDetails(row) : mapper.updateWorkspaceLease(row),
                    () -> new TaskFailure("DIRECT_LEASE_CONCURRENT_CONFLICT", "Direct workspace lease changed concurrently; no transition was accepted"));
        }
    }

    private void updateQueue(TaskQueueRow row) {
        TaskQueueRow current = mapper.findTaskQueue(row.taskId())
                .orElseThrow(() -> new TaskFailure("DIRECT_QUEUE_MISSING", "Task has no direct workspace queue record"));
        lifecycle.transition(queueSubject(row), current.state(), row.state(), null, java.util.Map.of(),
                () -> mapper.updateTaskQueue(row),
                () -> new TaskFailure("DIRECT_QUEUE_CONCURRENT_CONFLICT", "Direct workspace queue changed concurrently; no transition was accepted"));
    }

    private void createLease(WorkspaceLeaseRow row) {
        lifecycle.create(leaseSubject(row), row.state(), java.util.Map.of(), () -> mapper.insertWorkspaceLease(row),
                () -> new TaskFailure("DIRECT_LEASE_CONCURRENT_CONFLICT", "Direct workspace lease could not be created"));
    }

    private void createQueue(TaskQueueRow row) {
        lifecycle.create(queueSubject(row), row.state(), java.util.Map.of("source", row.source()),
                () -> mapper.insertTaskQueue(row),
                () -> new TaskFailure("DIRECT_QUEUE_CONCURRENT_CONFLICT", "Direct workspace queue could not be created"));
    }

    private LifecycleTransitionService.Subject leaseSubject(WorkspaceLeaseRow row) {
        return new LifecycleTransitionService.Subject(LifecycleMachineType.WORKSPACE_LEASE, row.rootFingerprint(),
                LifecycleScopeType.WORKSPACE, row.rootFingerprint());
    }

    private LifecycleTransitionService.Subject queueSubject(TaskQueueRow row) {
        return new LifecycleTransitionService.Subject(LifecycleMachineType.TASK_QUEUE, row.taskId(),
                LifecycleScopeType.TASK, row.taskId());
    }

    private WorkspaceLeaseRow lease(WorkspaceLeaseRow old, String state, String writerSessionId, String heartbeat,
                                    String releasedAt, String reason) {
        return lease(old, state, writerSessionId, heartbeat, releasedAt, reason, old.holderTaskId());
    }

    private WorkspaceLeaseRow lease(WorkspaceLeaseRow old, String state, String writerSessionId, String heartbeat,
                                    String releasedAt, String reason, String holderTaskId) {
        return new WorkspaceLeaseRow(old.canonicalRoot(), old.rootFingerprint(), MODE_DIRECT, holderTaskId, writerSessionId,
                state, old.acquiredAt(), heartbeat, releasedAt, reason, old.version());
    }

    private TaskQueueRow queue(TaskQueueRow old, String state, String admittedAt, String finishedAt) {
        return new TaskQueueRow(old.taskId(), old.canonicalRoot(), old.rootFingerprint(), old.position(), old.source(), state,
                old.enqueuedAt(), admittedAt, finishedAt, old.version());
    }

    private Admission admission(String state, WorkspaceLeaseRow lease, TaskQueueRow queue, WorkspaceIdentity workspace) {
        return new Admission(state, workspace.canonicalRoot(), workspace.rootFingerprint(), queue.position(),
                lease == null ? null : lease.holderTaskId(), lease == null ? null : lease.state());
    }

    private LeaseSnapshot snapshot(WorkspaceLeaseRow lease, WorkspaceIdentity workspace, TaskQueueRow queue) {
        return new LeaseSnapshot(workspace.canonicalRoot(), workspace.rootFingerprint(), lease.holderTaskId(), lease.writerSessionId(),
                lease.state(), queue.position(), queue.state(), lease.heartbeatAt(), lease.releaseReason());
    }

    private QueueSnapshot queueSnapshot(TaskQueueRow row) {
        return new QueueSnapshot(row.taskId(), row.canonicalRoot(), row.rootFingerprint(), row.position(), row.state());
    }

    private BlockingLease blocking(WorkspaceLeaseRow lease) {
        boolean available = false;
        boolean fingerprintMatches = false;
        String observed = null;
        try {
            WorkspaceIdentity workspace = identify(Path.of(lease.canonicalRoot()));
            available = true;
            observed = workspace.rootFingerprint();
            fingerprintMatches = lease.rootFingerprint().equals(workspace.rootFingerprint());
        } catch (TaskFailure ignored) {
            // A missing/replaced root remains blocked: it needs a human-safe recovery decision.
        }
        return new BlockingLease(lease.canonicalRoot(), lease.rootFingerprint(), observed, available, fingerprintMatches,
                lease.holderTaskId(), lease.writerSessionId(), lease.state(), lease.heartbeatAt(), lease.releaseReason());
    }

    private void requireSameWorkspace(WorkspaceLeaseRow row, WorkspaceIdentity workspace) {
        if (!workspace.rootFingerprint().equals(row.rootFingerprint()) || !MODE_DIRECT.equals(row.mode())) {
            throw new TaskFailure("DIRECT_WORKSPACE_FINGERPRINT_MISMATCH", "Direct workspace identity changed; refusing to share or release its lease");
        }
    }

    private void requireSameWorkspace(TaskQueueRow row, WorkspaceIdentity workspace) {
        if (!workspace.canonicalRoot().equals(row.canonicalRoot()) || !workspace.rootFingerprint().equals(row.rootFingerprint())) {
            throw new TaskFailure("DIRECT_WORKSPACE_FINGERPRINT_MISMATCH", "Task queue entry belongs to a different direct workspace identity");
        }
    }

    private static String source(String source) {
        String normalized = source == null ? "" : source.trim().toUpperCase(Locale.ROOT);
        if (!List.of("MANUAL", "RECOVERY", "AUTOMATION").contains(normalized)) {
            throw new TaskFailure("DIRECT_QUEUE_SOURCE_INVALID", "Direct queue source must be MANUAL, RECOVERY, or AUTOMATION");
        }
        return normalized;
    }

    private static boolean concurrencyFailure(Throwable failure) {
        Throwable cursor = failure;
        while (cursor != null) {
            if (cursor instanceof DataIntegrityViolationException || cursor.getClass().getSimpleName().contains("DuplicateKey")
                    || cursor.getClass().getSimpleName().contains("ConcurrencyFailure")) return true;
            if (cursor instanceof TaskFailure taskFailure && ("DIRECT_LEASE_CONCURRENT_CONFLICT".equals(taskFailure.code())
                    || "DIRECT_QUEUE_CONCURRENT_CONFLICT".equals(taskFailure.code()))) return true;
            cursor = cursor.getCause();
        }
        return false;
    }

    private static String now() { return Instant.now().toString(); }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception failure) { throw new IllegalStateException("SHA-256 is unavailable", failure); }
    }
    private static String safeMessage(Throwable failure) {
        return failure == null || failure.getMessage() == null || failure.getMessage().isBlank()
                ? "Direct workspace transition failed" : bounded(failure.getMessage());
    }
    private static String bounded(String value) { return value.length() <= 1_000 ? value : value.substring(0, 1_000); }
    private static <T> T required(T value) {
        if (value == null) throw new TaskFailure("DIRECT_LEASE_TRANSACTION_FAILED", "Direct workspace transaction did not complete");
        return value;
    }

    public record WorkspaceIdentity(String canonicalRoot, String rootFingerprint) { }
    public record Admission(String state, String canonicalRoot, String rootFingerprint, long queuePosition,
                            String holderTaskId, String leaseState) { }
    public record LeaseSnapshot(String canonicalRoot, String rootFingerprint, String holderTaskId, String writerSessionId,
                                String state, long queuePosition, String queueState, String heartbeatAt, String releaseReason) { }
    public record QueueSnapshot(String taskId, String canonicalRoot, String rootFingerprint, long position, String state) { }
    public record Release(LeaseSnapshot releasedHolder, QueueSnapshot admittedNext) { }
    public record BlockingLease(String canonicalRoot, String persistedFingerprint, String observedFingerprint,
                                boolean rootAvailable, boolean fingerprintMatches, String holderTaskId,
                                String writerSessionId, String state, String heartbeatAt, String releaseReason) { }
}
