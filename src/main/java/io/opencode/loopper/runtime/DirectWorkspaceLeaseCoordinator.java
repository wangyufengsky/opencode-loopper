package io.opencode.loopper.runtime;

import io.opencode.loopper.domain.TaskFailure;
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
 * <p>Git worktrees are already isolated. Direct workspaces are not: exactly one task
 * may hold a writer lease for one canonical {@code toRealPath()} root. A terminal task
 * whose old writer has not been positively stopped stays {@code RELEASE_PENDING}; this
 * class deliberately has no force-release operation.</p>
 */
@Component
public class DirectWorkspaceLeaseCoordinator {
    public static final String MODE_DIRECT = "DIRECT";
    public static final String LEASE_HELD = "HELD";
    public static final String LEASE_RELEASE_PENDING = "RELEASE_PENDING";
    public static final String LEASE_RELEASED = "RELEASED";
    public static final String QUEUE_QUEUED = "QUEUED";
    public static final String QUEUE_ADMITTED = "ADMITTED";
    public static final String QUEUE_CANCELLED = "CANCELLED";
    public static final String QUEUE_FINISHED = "FINISHED";

    private static final int CONCURRENCY_RETRIES = 3;

    private final LoopperMapper mapper;
    private final TransactionTemplate transactions;

    public DirectWorkspaceLeaseCoordinator(LoopperMapper mapper, PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
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
                if (result == null) throw new TaskFailure("DIRECT_LEASE_TRANSACTION_FAILED", "Direct workspace admission did not return a result");
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
            if (!LEASE_HELD.equals(lease.state()) && !LEASE_RELEASE_PENDING.equals(lease.state())) {
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
            if (LEASE_RELEASED.equals(lease.state())) {
                throw new TaskFailure("DIRECT_LEASE_NOT_HELD", "A released direct workspace lease cannot be marked pending");
            }
            String detail = reason == null || reason.isBlank() ? "WRITER_TERMINATION_UNCONFIRMED" : bounded(reason);
            updateLease(lease(lease, LEASE_RELEASE_PENDING,
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
            updateLease(lease(lease, LEASE_HELD, null, now(), null, detail));
            return snapshot(mapper.findWorkspaceLease(workspace.canonicalRoot()).orElseThrow(), workspace, queue(taskId));
        }));
    }

    /** A new writer may start only for the current HELD owner, never for RELEASE_PENDING. */
    public LeaseSnapshot requireWritableLease(Path root, String taskId) {
        WorkspaceIdentity workspace = identify(root);
        WorkspaceLeaseRow lease = holder(workspace, taskId);
        if (!LEASE_HELD.equals(lease.state())) {
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
            if (QUEUE_ADMITTED.equals(row.state())) {
                throw new TaskFailure("DIRECT_QUEUE_HOLDER_CANNOT_CANCEL", "An admitted task must confirm writer termination before release");
            }
            if (QUEUE_QUEUED.equals(row.state())) {
                updateQueue(queue(row, QUEUE_CANCELLED, null, now()));
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
            String stableFileIdentity = fileKey == null
                    ? "created:" + attributes.creationTime().toMillis()
                    : fileKey.toString();
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
            if (QUEUE_ADMITTED.equals(existingQueue.state()) && existingLease != null
                    && taskId.equals(existingLease.holderTaskId()) && !LEASE_RELEASED.equals(existingLease.state())) {
                return admission("ADMITTED", existingLease, existingQueue, workspace);
            }
            if (QUEUE_QUEUED.equals(existingQueue.state())) {
                return admission("QUEUED", existingLease, existingQueue, workspace);
            }
            throw new TaskFailure("DIRECT_QUEUE_NOT_ADMITTABLE", "Task already has a " + existingQueue.state() + " direct queue record");
        }

        WorkspaceLeaseRow lease = mapper.findWorkspaceLease(workspace.canonicalRoot()).orElse(null);
        String timestamp = now();
        long position = mapper.nextQueuePosition(workspace.canonicalRoot());
        if (lease == null) {
            WorkspaceLeaseRow held = new WorkspaceLeaseRow(workspace.canonicalRoot(), workspace.rootFingerprint(), MODE_DIRECT,
                    taskId, writerSessionId, LEASE_HELD, timestamp, timestamp, null, null, 0);
            mapper.insertWorkspaceLease(held);
            TaskQueueRow admitted = new TaskQueueRow(taskId, workspace.canonicalRoot(), workspace.rootFingerprint(), position,
                    source, QUEUE_ADMITTED, timestamp, timestamp, null, 0);
            mapper.insertTaskQueue(admitted);
            return admission("ADMITTED", held, admitted, workspace);
        }
        requireSameWorkspace(lease, workspace);
        if (LEASE_RELEASED.equals(lease.state())) {
            WorkspaceLeaseRow held = new WorkspaceLeaseRow(lease.canonicalRoot(), lease.rootFingerprint(), MODE_DIRECT,
                    taskId, writerSessionId, LEASE_HELD, timestamp, timestamp, null, null, lease.version());
            updateLease(held);
            TaskQueueRow admitted = new TaskQueueRow(taskId, workspace.canonicalRoot(), workspace.rootFingerprint(), position,
                    source, QUEUE_ADMITTED, timestamp, timestamp, null, 0);
            mapper.insertTaskQueue(admitted);
            return admission("ADMITTED", mapper.findWorkspaceLease(workspace.canonicalRoot()).orElseThrow(), admitted, workspace);
        }
        TaskQueueRow queued = new TaskQueueRow(taskId, workspace.canonicalRoot(), workspace.rootFingerprint(), position,
                source, QUEUE_QUEUED, timestamp, null, null, 0);
        mapper.insertTaskQueue(queued);
        return admission("QUEUED", lease, queued, workspace);
    }

    private Release release(WorkspaceIdentity workspace, String taskId, String reason) {
        WorkspaceLeaseRow lease = holder(workspace, taskId);
        TaskQueueRow current = queue(taskId);
        if (!QUEUE_ADMITTED.equals(current.state())) {
            throw new TaskFailure("DIRECT_QUEUE_NOT_ADMITTED", "Only an admitted task can release a direct workspace");
        }
        updateQueue(queue(current, QUEUE_FINISHED, current.admittedAt(), now()));
        Optional<TaskQueueRow> next = mapper.nextQueuedTask(workspace.canonicalRoot());
        String timestamp = now();
        if (next.isEmpty()) {
            updateLease(new WorkspaceLeaseRow(lease.canonicalRoot(), lease.rootFingerprint(), MODE_DIRECT,
                    null, null, LEASE_RELEASED, lease.acquiredAt(), timestamp, timestamp,
                    reason == null || reason.isBlank() ? "WRITER_STOPPED" : bounded(reason), lease.version()));
            return new Release(snapshot(mapper.findWorkspaceLease(workspace.canonicalRoot()).orElseThrow(), workspace, queue(taskId)), null);
        }
        TaskQueueRow admitted = next.get();
        requireSameWorkspace(admitted, workspace);
        updateQueue(queue(admitted, QUEUE_ADMITTED, timestamp, null));
        updateLease(new WorkspaceLeaseRow(lease.canonicalRoot(), lease.rootFingerprint(), MODE_DIRECT,
                admitted.taskId(), null, LEASE_HELD, timestamp, timestamp, null, null, lease.version()));
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
        if (mapper.updateWorkspaceLease(row) != 1) {
            throw new TaskFailure("DIRECT_LEASE_CONCURRENT_CONFLICT", "Direct workspace lease changed concurrently; no transition was accepted");
        }
    }

    private void updateQueue(TaskQueueRow row) {
        if (mapper.updateTaskQueue(row) != 1) {
            throw new TaskFailure("DIRECT_QUEUE_CONCURRENT_CONFLICT", "Direct workspace queue changed concurrently; no transition was accepted");
        }
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
