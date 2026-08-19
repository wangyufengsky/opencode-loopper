package io.opencode.loopper.service;

import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.domain.SessionState;
import io.opencode.loopper.domain.TaskState;
import io.opencode.loopper.persistence.AttemptRow;
import io.opencode.loopper.persistence.ExecutionSessionRow;
import io.opencode.loopper.persistence.LoopDraftRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.SessionCheckpointRow;
import io.opencode.loopper.persistence.SessionTodoRow;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.runtime.GitWorktreeManager;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Durable local snapshots around OpenCode sessions.  This service deliberately
 * never starts a writer: fork snapshots are stored terminal while a paused Task
 * remains paused, so TaskService is still the sole owner of writer admission.
 */
@Service
public class SessionLifecycleService {
    private final LoopperMapper mapper;
    private final OpenCodeClient openCode;
    private final LoopDraftService drafts;
    private final TaskService tasks;
    private final TaskEventService events;
    private final SessionLifecyclePersistence persistence;
    private final ImplementationTodoSynchronizer todoSynchronizer;
    private final ObjectMapper json;

    public SessionLifecycleService(LoopperMapper mapper, OpenCodeClient openCode, LoopDraftService drafts, TaskService tasks,
                                   TaskEventService events, SessionLifecyclePersistence persistence,
                                   ImplementationTodoSynchronizer todoSynchronizer, ObjectMapper json) {
        this.mapper = mapper;
        this.openCode = openCode;
        this.drafts = drafts;
        this.tasks = tasks;
        this.events = events;
        this.persistence = persistence;
        this.todoSynchronizer = todoSynchronizer;
        this.json = json;
    }

    /** Reads provider todos, upserts them, and returns the persisted projection (not the provider payload). */
    public List<TodoDto> refreshTodos(String taskId, String sessionId) {
        Resolved resolved = resolve(taskId, sessionId);
        try {
            return todoSynchronizer.synchronize(resolved.task(), resolved.session()).todos().stream()
                    .map(this::todo).toList();
        } catch (RuntimeException failure) {
            throw unavailable("SESSION_TODOS_UNAVAILABLE", failure);
        }
    }

    public List<TodoDto> todos(String taskId, String sessionId) {
        resolve(taskId, sessionId);
        return mapper.listSessionTodos(sessionId).stream().map(this::todo).toList();
    }

    public CheckpointDto checkpoint(String taskId, String sessionId, String externalMessageId) {
        Resolved resolved = resolve(taskId, sessionId);
        OpenCodeClient.OpenCodeSession remote = remote(resolved.task(), resolved.session());
        // A checkpoint always first synchronizes actual provider todos. The bounded rows
        // and truncation flag are the non-authoritative progress refs captured below.
        ImplementationTodoSynchronizer.SyncResult todoSnapshot;
        try { todoSnapshot = todoSynchronizer.synchronize(resolved.task(), resolved.session()); }
        catch (RuntimeException failure) { throw unavailable("SESSION_TODOS_UNAVAILABLE", failure); }
        List<Map<String, Object>> messages;
        List<Map<String, Object>> parts;
        String diff;
        try {
            messages = openCode.sessionMessageRefs(remote).stream().map(message -> Map.<String, Object>of(
                    "id", blank(message.id()), "role", blank(message.role()),
                    "createdAt", blank(message.createdAt()), "completedAt", blank(message.completedAt()))).toList();
            parts = openCode.sessionTranscript(remote).parts().stream().map(part -> Map.<String, Object>of(
                    "id", blank(part.id()), "type", blank(part.type()), "status", blank(part.status()),
                    "startedAt", blank(part.startedAt()))).toList();
            diff = openCode.diff(remote);
        } catch (RuntimeException failure) {
            throw unavailable("SESSION_CHECKPOINT_UNAVAILABLE", failure);
        }
        if (externalMessageId != null && !externalMessageId.isBlank()
                && messages.stream().noneMatch(message -> externalMessageId.trim().equals(message.get("id")))) {
            throw new BadRequestException("CHECKPOINT_MESSAGE_NOT_FOUND",
                    "指定的 externalMessageId 不属于当前 OpenCode transcript");
        }
        // DB optimistic-lock versions are implementation churn, not provider refs;
        // excluding them keeps an identical provider snapshot hash-stable.
        List<Map<String, Object>> todoRefs = todoSnapshot.todos().stream().map(row -> {
            Map<String, Object> ref = new java.util.LinkedHashMap<>();
            ref.put("id", row.externalTodoId());
            ref.put("content", row.content());
            ref.put("status", row.status());
            ref.put("priority", row.priority());
            ref.put("ordinal", row.ordinal());
            ref.put("truncated", todoSnapshot.truncated());
            return java.util.Collections.unmodifiableMap(ref);
        }).toList();
        String messageRefsJson = write(Map.of("messages", messages, "parts", parts));
        String todoRefsJson = write(todoRefs);
        String diffRefJson = write(Map.of("sha256", sha256(blank(diff)), "content", blank(diff)));
        String contentSha = sha256(messageRefsJson + "\n" + todoRefsJson + "\n" + diffRefJson);
        String now = Instant.now().toString();
        SessionCheckpointRow row = new SessionCheckpointRow(UUID.randomUUID().toString(), taskId, sessionId, resolved.session().attemptId(),
                nullable(externalMessageId), messageRefsJson, todoRefsJson, diffRefJson, contentSha, now, 0);
        return checkpoint(persistence.insertCheckpoint(row));
    }

    public List<CheckpointDto> checkpoints(String taskId, String sessionId) {
        resolve(taskId, sessionId);
        return mapper.listSessionCheckpoints(sessionId).stream().map(this::checkpoint).toList();
    }

    /** Forks a remote transcript only after every prior writer is durably known stopped. */
    public ForkDto fork(String taskId, String sessionId, String messageId) {
        Resolved resolved = requirePausedStopped(taskId, sessionId);
        if (messageId == null || messageId.isBlank()) throw new BadRequestException("FORK_MESSAGE_REQUIRED", "Fork requires an OpenCode message id");
        OpenCodeClient.OpenCodeSession child;
        try { child = openCode.forkSession(remote(resolved.task(), resolved.session()), messageId.trim()); }
        catch (RuntimeException failure) { throw unavailable("SESSION_FORK_UNAVAILABLE", failure); }
        // The fork is an idle remote snapshot, not a newly admitted local writer.
        // Re-check the paused/no-writer invariant inside the short persistence transaction.
        SessionLifecyclePersistence.ForkSnapshot persisted = persistence.insertForkSnapshot(taskId, sessionId, child.id());
        return new ForkDto(persisted.session().id(), persisted.attempt().id(), child.id(), persisted.session().state(), persisted.session().createdAt());
    }

    public RevertDto revert(String taskId, String sessionId, String messageId, String partId) {
        Resolved resolved = requirePausedStopped(taskId, sessionId);
        if (GitWorktreeManager.DIRECT_BRANCH.equals(resolved.task().branchName())) {
            throw new ConflictException("DIRECT_REVERT_REQUIRES_RECOVERY", "直接执行目录禁止 in-place revert；请创建派生 Recovery 任务");
        }
        if (messageId == null || messageId.isBlank() || partId == null || partId.isBlank()) {
            throw new BadRequestException("REVERT_REFERENCE_REQUIRED", "Revert requires OpenCode message and part ids");
        }
        try { openCode.revertSession(remote(resolved.task(), resolved.session()), messageId.trim(), partId.trim()); }
        catch (RuntimeException failure) { throw unavailable("SESSION_REVERT_UNAVAILABLE", failure); }
        events.emit(taskId, "session.reverted", Map.of("sessionId", sessionId, "messageId", messageId.trim(), "partId", partId.trim()));
        return new RevertDto(sessionId, "派生工作树已按 OpenCode 引用回退；任务仍保持暂停", Instant.now().toString());
    }

    public SummaryDto summarize(String taskId, String sessionId, boolean automatic) {
        Resolved resolved = resolve(taskId, sessionId);
        OpenCodeClient.OpenCodeSession remote = remote(resolved.task(), resolved.session());
        UsageInsightsService.BudgetDecision budget = tasks.guardNextModelCall(taskId, "SESSION_SUMMARIZE");
        if (budget.blocked()) throw new ConflictException(budget.code(), budget.message());
        String before = status(remote);
        try { openCode.summarizeSession(remote, model(resolved.task()), automatic); }
        catch (RuntimeException failure) { throw unavailable("SESSION_SUMMARIZE_UNAVAILABLE", failure); }
        String after = status(remote);
        events.emit(taskId, "session.summarized", Map.of("sessionId", sessionId, "automatic", automatic,
                "remoteStateBefore", before, "remoteStateAfter", after));
        return new SummaryDto(sessionId, automatic, before, after, Instant.now().toString());
    }

    private Resolved requirePausedStopped(String taskId, String sessionId) {
        Resolved resolved = resolve(taskId, sessionId);
        if (!TaskState.PAUSED.name().equals(resolved.task().state())) {
            throw new ConflictException("TASK_NOT_PAUSED", "Fork 或 revert 前 Task 必须处于 PAUSED 状态");
        }
        for (ExecutionSessionRow row : mapper.listSessions(taskId)) {
            if (SessionState.CREATING.name().equals(row.state()) || SessionState.RUNNING.name().equals(row.state())) {
                throw new ConflictException("SESSION_WRITER_ACTIVE", "存在仍在运行的写入 Session，拒绝创建重叠 writer");
            }
            if (SessionState.DISCONNECTED.name().equals(row.state())) {
                throw new ConflictException("SESSION_WRITER_UNCONFIRMED", "存在未确认终止的写入 Session，拒绝操作");
            }
        }
        String state = status(remote(resolved.task(), resolved.session()));
        if (!terminal(state)) throw new ConflictException("SESSION_WRITER_UNCONFIRMED", "所选 Session 尚未由 OpenCode 确认终止");
        return resolved;
    }

    private Resolved resolve(String taskId, String sessionId) {
        TaskRow task = mapper.findTask(taskId).orElseThrow(() -> new NotFoundException("Task not found: " + taskId));
        ExecutionSessionRow session = mapper.findSession(sessionId).orElseThrow(() -> new NotFoundException("Execution session not found: " + sessionId));
        if (!taskId.equals(session.taskId())) throw new BadRequestException("SESSION_TASK_MISMATCH", "Session does not belong to this Task");
        return new Resolved(task, session);
    }

    private OpenCodeClient.OpenCodeSession remote(TaskRow task, ExecutionSessionRow session) {
        if (session.externalSessionId() == null || session.externalSessionId().isBlank() || task.worktreePath() == null || task.worktreePath().isBlank()) {
            throw new ConflictException("SESSION_REMOTE_UNAVAILABLE", "Session 没有可用的 OpenCode 远端标识或 worktree");
        }
        return new OpenCodeClient.OpenCodeSession(session.externalSessionId(), Path.of(task.worktreePath()));
    }

    private String status(OpenCodeClient.OpenCodeSession remote) {
        try { return blank(openCode.sessionStatus(remote).state()); }
        catch (RuntimeException failure) { throw new ConflictException("SESSION_WRITER_UNCONFIRMED", "无法确认旧写入 Session 已终止"); }
    }

    private boolean terminal(String state) {
        return "COMPLETED".equalsIgnoreCase(state) || "IDLE".equalsIgnoreCase(state) || "DONE".equalsIgnoreCase(state)
                || "FAILED".equalsIgnoreCase(state) || "ERROR".equalsIgnoreCase(state) || "ABORTED".equalsIgnoreCase(state)
                || "TIMED_OUT".equalsIgnoreCase(state);
    }

    private OpenCodeClient.OpenCodeModel model(TaskRow task) {
        LoopDraftRow draft = task.loopDraftId() == null ? null : mapper.findDraft(task.loopDraftId()).orElse(null);
        LoopSpec.ModelSpec model = draft == null ? null : drafts.spec(draft).model();
        return model == null ? new OpenCodeClient.OpenCodeModel(null, null, null)
                : new OpenCodeClient.OpenCodeModel(model.providerId(), model.modelId(), model.thinking());
    }

    private TodoDto todo(SessionTodoRow row) { return new TodoDto(row.id(), row.externalTodoId(), row.content(), row.status(), row.priority(), row.ordinal(), row.observedAt()); }
    private CheckpointDto checkpoint(SessionCheckpointRow row) { return new CheckpointDto(row.id(), row.taskId(), row.executionSessionId(), row.attemptId(), row.externalMessageId(), row.contentSha256(), row.createdAt()); }
    private String write(Object value) { try { return json.writeValueAsString(value); } catch (JacksonException failure) { throw new IllegalStateException("Unable to serialize session snapshot", failure); } }
    private String sha256(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception failure) { throw new IllegalStateException("SHA-256 unavailable", failure); } }
    private String blank(String value) { return value == null ? "" : value; }
    private String nullable(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private ServiceUnavailableException unavailable(String code, RuntimeException failure) { return new ServiceUnavailableException(code, failure.getMessage() == null ? "OpenCode 会话暂时不可用" : failure.getMessage()); }

    public record TodoDto(String id, String externalTodoId, String content, String status, String priority, int ordinal, String observedAt) { }
    public record CheckpointDto(String id, String taskId, String sessionId, String attemptId, String externalMessageId, String contentSha256, String createdAt) { }
    public record ForkDto(String sessionId, String attemptId, String externalSessionId, String state, String createdAt) { }
    public record RevertDto(String sessionId, String message, String revertedAt) { }
    public record SummaryDto(String sessionId, boolean automatic, String remoteStateBefore, String remoteStateAfter, String summarizedAt) { }
    private record Resolved(TaskRow task, ExecutionSessionRow session) { }
}
