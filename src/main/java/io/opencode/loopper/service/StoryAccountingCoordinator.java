package io.opencode.loopper.service;

import io.opencode.loopper.domain.SessionFailure;
import io.opencode.loopper.persistence.DesignerMessageRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.StoryAccountingCallRow;
import io.opencode.loopper.persistence.StoryAccountingOwnerRow;
import io.opencode.loopper.persistence.StoryAccountingSessionRow;
import io.opencode.loopper.persistence.StoryBindingRow;
import io.opencode.loopper.runtime.OpenCodeClient;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;

/** Best-effort story accounting. No failure from this component may alter a business lifecycle. */
@Service
public class StoryAccountingCoordinator {
    private static final Logger log = LoggerFactory.getLogger(StoryAccountingCoordinator.class);
    private static final String COMMAND = "aicoding";
    private static final int MAX_DETAIL = 2_000;
    private final LoopperMapper mapper;
    private final TaskEventService taskEvents;
    private DesignerEventHub designerEvents;
    private final Duration timeout;
    private final String startupAt = Instant.now().toString();
    private final ExecutorService commands = Executors.newVirtualThreadPerTaskExecutor();
    private volatile SessionCommandTransport sessionTransport;
    private final TransactionTemplate transactions;
    private final Map<String, CompletableFuture<Void>> starting = new ConcurrentHashMap<>();

    @Autowired
    public StoryAccountingCoordinator(LoopperMapper mapper, TaskEventService taskEvents,
                                     PlatformTransactionManager transactionManager, DesignerEventHub designerEvents) {
        this(mapper, taskEvents, Duration.ofSeconds(30), new TransactionTemplate(transactionManager));
        this.designerEvents = designerEvents;
    }

    StoryAccountingCoordinator(LoopperMapper mapper, TaskEventService taskEvents, Duration timeout) {
        this(mapper, taskEvents, timeout, null);
    }

    StoryAccountingCoordinator(LoopperMapper mapper, TaskEventService taskEvents, Duration timeout,
                               TransactionTemplate transactions) {
        this.mapper = mapper;
        this.taskEvents = taskEvents;
        this.timeout = timeout;
        this.transactions = transactions;
    }

    public void beforeBusinessPrompt(OpenCodeClient.OpenCodeSession remote, CommandTransport transport) {
        safely("begin", remote, () -> mapper.findStoryAccountingOwner(remote.id())
                .ifPresent(owner -> {
                    begin(owner, remote, transport);
                    mapper.markStoryAccountingOwnerObserved(remote.id());
                }));
    }

    /** Router is started before its remote ownership row is durable, so it supplies the exact Designer owner once. */
    public void beforeRouterPrompt(String designerSessionId, OpenCodeClient.OpenCodeSession remote,
                                   CommandTransport transport) {
        safely("router begin", remote, () -> mapper.findDesignerStoryBinding(designerSessionId)
                .map(binding -> owner(binding, designerSessionId, null, "ROUTER", false))
                .ifPresent(owner -> begin(owner, remote, transport)));
    }

    public void beforeAbort(OpenCodeClient.OpenCodeSession remote) {
        // Abort alone is not owner retirement. The persisted lifecycle must close
        // first; the collector then submits complete without reopening business work.
        safely("observe abort", remote, () -> mapper.markStoryAccountingOwnerObserved(remote.id()));
    }

    public void afterTerminalStatus(OpenCodeClient.OpenCodeSession remote, CommandTransport transport) {
        safely("terminal complete", remote, () -> {
            StoryAccountingSessionRow accounting = mapper.findStoryAccountingSession(remote.id()).orElse(null);
            if (accounting == null) return;
            if (mapper.findStoryAccountingOwner(remote.id()).isPresent()) {
                mapper.markStoryAccountingOwnerObserved(remote.id());
            }
            if (!mapper.storyAccountingOwnerActive(remote.id())) complete(remote, transport);
        });
    }

    public void installTransport(SessionCommandTransport transport) { this.sessionTransport = transport; }

    @Scheduled(fixedDelay = 1_000)
    public void completeRetiredSessions() {
        SessionCommandTransport transport = sessionTransport;
        if (transport == null) return;
        try {
            for (StoryAccountingSessionRow row : mapper.listRetiredStoryAccountingSessions()) {
                OpenCodeClient.OpenCodeSession remote = new OpenCodeClient.OpenCodeSession(row.externalSessionId(),
                        java.nio.file.Path.of(row.worktreePath()));
                commands.submit(() -> safely("retired complete", remote,
                        () -> complete(remote, request -> transport.execute(remote, request))));
            }
        } catch (RuntimeException failure) {
            log.warn("Unable to collect retired story-accounting Sessions", failure);
        }
    }

    public java.util.Set<String> accountingMessageIds(String externalSessionId) {
        try { return java.util.Set.copyOf(mapper.listStoryAccountingMessageIds(externalSessionId)); }
        catch (RuntimeException failure) {
            log.warn("Unable to read story-accounting message identities for {}", externalSessionId, failure);
            return java.util.Set.of();
        }
    }

    private void begin(StoryAccountingOwnerRow owner, OpenCodeClient.OpenCodeSession remote,
                       CommandTransport transport) {
        CompletableFuture<Void> pending = new CompletableFuture<>();
        CompletableFuture<Void> existing = starting.putIfAbsent(remote.id(), pending);
        if (existing != null) {
            try { existing.get(timeout.toMillis(), TimeUnit.MILLISECONDS); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
            catch (ExecutionException | TimeoutException ignored) { /* Main work remains independent. */ }
            return;
        }
        try {
            Prepared prepared = transaction(() -> prepareBegin(owner, remote));
            if (prepared != null) execute(prepared.session(), prepared.call(), transport);
        } finally {
            pending.complete(null);
            starting.remove(remote.id(), pending);
        }
    }

    private synchronized Prepared prepareBegin(StoryAccountingOwnerRow owner,
                                                OpenCodeClient.OpenCodeSession remote) {
        if (mapper.findStoryAccountingSession(remote.id()).isPresent()) return null;
        if (mapper.incrementStorySessionOrdinal(owner.bindingId()) != 1) return null;
        int ordinal = mapper.currentStorySessionOrdinal(owner.bindingId());
        String operation = ordinal == 1 ? "start" : "continue";
        String now = Instant.now().toString();
        StoryAccountingSessionRow session = new StoryAccountingSessionRow(UUID.randomUUID().toString(),
                owner.bindingId(), owner.designerSessionId(), owner.taskId(), remote.id(), remote.generation(),
                remote.worktree().toString(), owner.role(), ordinal, operation, false, "BINDING", null, now, now);
        mapper.insertStoryAccountingSession(session);
        String arguments = operation + " " + owner.systemCode() + " " + owner.storyCode();
        StoryAccountingCallRow call = preparedCall(session.id(), "BEGIN", operation, arguments, now);
        mapper.insertStoryAccountingCall(call);
        return new Prepared(session, call);
    }

    private void complete(OpenCodeClient.OpenCodeSession remote, CommandTransport transport) {
        Prepared prepared = transaction(() -> prepareComplete(remote));
        if (prepared != null) execute(prepared.session(), prepared.call(), transport);
    }

    private synchronized Prepared prepareComplete(OpenCodeClient.OpenCodeSession remote) {
        StoryAccountingSessionRow session = mapper.findStoryAccountingSession(remote.id()).orElse(null);
        if (session == null || "BINDING".equals(session.state())
                || mapper.findStoryAccountingCall(session.id(), "COMPLETE").isPresent()) return null;
        String now = Instant.now().toString();
        StoryAccountingSessionRow completing = copy(session, "COMPLETING", session.pluginRunId(), now);
        mapper.updateStoryAccountingSession(completing);
        StoryAccountingCallRow call = preparedCall(session.id(), "COMPLETE", "complete", "complete", now);
        mapper.insertStoryAccountingCall(call);
        return new Prepared(completing, call);
    }

    private StoryAccountingCallRow preparedCall(String sessionId, String phase, String operation,
                                                  String arguments, String now) {
        String id = UUID.randomUUID().toString();
        String messageId = "msg_loopper_aicoding_" + id.replace("-", "");
        return new StoryAccountingCallRow(id, sessionId, phase, messageId, operation, arguments,
                "PREPARED", null, null, null, null, false, now, null);
    }

    private void execute(StoryAccountingSessionRow session, StoryAccountingCallRow call,
                         CommandTransport transport) {
        OpenCodeClient.CommandResult result = null;
        String state = "SUCCEEDED";
        String code = null;
        String detail = null;
        var future = commands.submit(() -> transport.execute(new OpenCodeClient.CommandRequest(
                COMMAND, call.argumentsText(), call.messageId())));
        try {
            result = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException failure) {
            future.cancel(true);
            state = "UNKNOWN";
            code = "STORY_ACCOUNTING_TIMEOUT";
            detail = "请求超时（" + timeout.toSeconds() + " 秒）";
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            state = "UNKNOWN";
            code = "STORY_ACCOUNTING_INTERRUPTED";
            detail = "统计调用被中断";
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            state = "FAILED";
            code = cause instanceof SessionFailure sessionFailure
                    ? sessionFailure.code() : "STORY_ACCOUNTING_COMMAND_FAILED";
            detail = bounded(cause.getMessage());
        }
        String now = Instant.now().toString();
        boolean failed = !"SUCCEEDED".equals(state);
        StoryAccountingCallRow finished = new StoryAccountingCallRow(call.id(), call.accountingSessionId(),
                call.phase(), call.messageId(), call.operation(), call.argumentsText(), state,
                result == null || result.runId() == null ? null : bounded(result.runId()),
                result == null ? null : bounded(result.output()), code, detail, failed,
                call.startedAt(), now);
        String sessionState = "BEGIN".equals(call.phase())
                ? failed ? "BIND_FAILED" : "ACTIVE"
                : failed ? "COMPLETE_FAILED" : "COMPLETED";
        String runId = result != null && result.runId() != null ? bounded(result.runId()) : session.pluginRunId();
        boolean claimed = Boolean.TRUE.equals(transaction(() -> {
            if (mapper.finishStoryAccountingCall(finished) != 1) return false;
            mapper.updateStoryAccountingSession(copy(session, sessionState, runId, now));
            return true;
        }));
        if (claimed && failed) notifyFailure(session, call, detail == null ? code : detail);
    }

    private void notifyFailure(StoryAccountingSessionRow session, StoryAccountingCallRow call, String reason) {
        String message = "AI 工作量统计失败：" + bounded(reason) + "，任务继续执行。";
        try {
            if (session.designerSessionId() != null) {
                    mapper.appendDesignerMessage(new DesignerMessageRow(UUID.randomUUID().toString(),
                            session.designerSessionId(), 0,
                            "SYSTEM", message, "STORY_BINDING_FAILED", Instant.now().toString(),
                            "SYSTEM", null, null));
                if (designerEvents != null) mapper.findDesignerSession(session.designerSessionId()).ifPresent(owner ->
                        designerEvents.publish(owner.id(), "STORY_BINDING_FAILED", owner.state(),
                                owner.workflowPhase(), "SYSTEM", owner.externalSessionState(),
                                false, "", message));
            } else if (session.taskId() != null) {
                taskEvents.emit(session.taskId(), "story_binding.failed", Map.of(
                        "message", message, "phase", call.phase(), "externalSessionId", session.externalSessionId(),
                        "role", session.role()));
            }
        } catch (RuntimeException notificationFailure) {
            log.warn("Story-accounting notification delivery failed for {}", session.externalSessionId(),
                    notificationFailure);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedCalls() {
        try { recoverPreparedCalls(); }
        catch (RuntimeException failure) { log.warn("Unable to recover story accounting; business startup continues", failure); }
    }

    private void recoverPreparedCalls() {
        for (StoryAccountingCallRow call : mapper.listInterruptedStoryAccountingCalls(startupAt)) {
            StoryAccountingSessionRow session = mapper.findStoryAccountingSessionById(call.accountingSessionId()).orElse(null);
            if (session == null) continue;
            String now = Instant.now().toString();
            String detail = "进程重启前的统计调用结果未知，未自动重发";
            StoryAccountingCallRow unknown = new StoryAccountingCallRow(call.id(), call.accountingSessionId(),
                    call.phase(), call.messageId(), call.operation(), call.argumentsText(), "UNKNOWN",
                    call.pluginRunId(), call.resultText(), "STORY_ACCOUNTING_INTERRUPTED", detail, true,
                    call.startedAt(), now);
            boolean claimed = Boolean.TRUE.equals(transaction(() -> {
                if (mapper.finishStoryAccountingCall(unknown) != 1) return false;
                mapper.updateStoryAccountingSession(copy(session,
                        "BEGIN".equals(call.phase()) ? "BIND_FAILED" : "COMPLETE_FAILED", session.pluginRunId(), now));
                return true;
            }));
            if (claimed) notifyFailure(session, call, detail);
        }
    }

    private synchronized <T> T transaction(java.util.function.Supplier<T> action) {
        return transactions == null ? action.get() : transactions.execute(status -> action.get());
    }

    private void safely(String operation, OpenCodeClient.OpenCodeSession remote, Runnable action) {
        try { action.run(); }
        catch (RuntimeException failure) {
            log.warn("Story accounting {} failed for {}; business flow will continue", operation, remote.id(), failure);
        }
    }

    private static StoryAccountingOwnerRow owner(StoryBindingRow binding, String designerId,
                                                  String taskId, String role, boolean reusable) {
        return new StoryAccountingOwnerRow(binding.id(), binding.systemCode(), binding.storyCode(),
                designerId, taskId, role, reusable);
    }
    private static StoryAccountingSessionRow copy(StoryAccountingSessionRow row, String state,
                                                   String runId, String now) {
        return new StoryAccountingSessionRow(row.id(), row.bindingId(), row.designerSessionId(), row.taskId(),
                row.externalSessionId(), row.runtimeGenerationId(), row.worktreePath(), row.role(), row.ordinal(),
                row.bindOperation(), row.ownerObserved(), state, runId, row.createdAt(), now);
    }
    private static String bounded(String value) {
        if (value == null || value.isBlank()) return "未知原因";
        String normalized = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return normalized.length() <= MAX_DETAIL ? normalized : normalized.substring(0, MAX_DETAIL);
    }

    @PreDestroy void close() { commands.shutdownNow(); }
    @FunctionalInterface public interface CommandTransport {
        OpenCodeClient.CommandResult execute(OpenCodeClient.CommandRequest request);
    }
    @FunctionalInterface public interface SessionCommandTransport {
        OpenCodeClient.CommandResult execute(OpenCodeClient.OpenCodeSession session, OpenCodeClient.CommandRequest request);
    }
    private record Prepared(StoryAccountingSessionRow session, StoryAccountingCallRow call) { }
}
