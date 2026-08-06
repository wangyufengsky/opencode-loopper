package io.opencode.loopper.runtime;

import java.nio.file.Path;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public interface OpenCodeClient {
    boolean healthy();
    OpenCodeSession createSession(Path worktree, String title, OpenCodeModel model);
    /** Creates a session whose permission rules deny all mutation and shell/task execution. */
    OpenCodeSession createReadOnlySession(Path worktree, String title, OpenCodeModel model);
    void promptAsync(OpenCodeSession session, String prompt);
    SessionStatus sessionStatus(OpenCodeSession session);
    /** Returns the latest assistant text after a completed session, preserving the original model response. */
    String sessionOutput(OpenCodeSession session);
    /** Returns the latest assistant turn even while OpenCode is still appending text parts. */
    String sessionLiveOutput(OpenCodeSession session);
    /** Returns provider-exposed incremental assistant parts for the live local monitoring UI. */
    SessionTranscript sessionTranscript(OpenCodeSession session);
    /** Returns provider message identities, distinct from content-part identities used by the live transcript. */
    default List<SessionMessageRef> sessionMessageRefs(OpenCodeSession session) {
        return sessionTranscript(session).parts().stream()
                .map(part -> new SessionMessageRef(part.id(), "assistant", part.startedAt(), null)).toList();
    }
    /** Returns pending interactive questions belonging to this exact session. */
    List<PendingQuestion> pendingQuestions(OpenCodeSession session);
    void replyQuestion(OpenCodeSession session, String requestId, List<List<String>> answers);
    void rejectQuestion(OpenCodeSession session, String requestId);
    /** Returns pending permission requests belonging to this exact session. */
    default List<PendingPermission> pendingPermissions(OpenCodeSession session) { return List.of(); }
    default void replyPermission(OpenCodeSession session, String requestId, PermissionReply reply, String message) {
        throw new UnsupportedOperationException("OpenCode permission replies are not supported by this adapter");
    }
    default List<SessionTodo> sessionTodos(OpenCodeSession session) { return List.of(); }
    default OpenCodeSession forkSession(OpenCodeSession session, String messageId) {
        throw new UnsupportedOperationException("OpenCode session fork is not supported by this adapter");
    }
    default void revertSession(OpenCodeSession session, String messageId, String partId) {
        throw new UnsupportedOperationException("OpenCode session revert is not supported by this adapter");
    }
    default void summarizeSession(OpenCodeSession session, OpenCodeModel model, boolean automatic) {
        throw new UnsupportedOperationException("OpenCode session summarize is not supported by this adapter");
    }
    /** Provider usage is nullable. Missing data must stay unknown and must never be normalized to zero. */
    default List<UsageRecord> sessionUsage(OpenCodeSession session) { return List.of(); }
    String diff(OpenCodeSession session);
    void abort(OpenCodeSession session);
    record OpenCodeSession(String id, Path worktree) { }
    record OpenCodeModel(String providerId, String modelId, Boolean thinking) { }
    record SessionTranscript(List<SessionPart> parts) {
        public SessionTranscript { parts = parts == null ? List.of() : List.copyOf(parts); }
    }
    record SessionPart(String id, String type, String label, String content, String status, String startedAt) {
        public SessionPart(String id, String type, String label, String content, String status) {
            this(id, type, label, content, status, null);
        }
    }
    record SessionMessageRef(String id, String role, String createdAt, String completedAt) { }
    record PendingQuestion(String id, String sessionId, List<QuestionPrompt> questions) {
        public PendingQuestion { questions = questions == null ? List.of() : List.copyOf(questions); }
    }
    record QuestionPrompt(String question, String header, List<QuestionOption> options, boolean multiple, boolean custom) {
        public QuestionPrompt { options = options == null ? List.of() : List.copyOf(options); }
    }
    record QuestionOption(String label, String description) { }
    record PendingPermission(String id, String sessionId, String permission, List<String> patterns,
                             Map<String, Object> metadata, String title) {
        public PendingPermission {
            patterns = patterns == null ? List.of() : List.copyOf(patterns);
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }
    enum PermissionReply { ONCE, SESSION, REJECT }
    record SessionTodo(String id, String content, String status, String priority, int ordinal,
                       Map<String, Object> metadata) {
        public SessionTodo { metadata = metadata == null ? Map.of() : Map.copyOf(metadata); }
    }
    record UsageRecord(String messageId, String providerId, String modelId, Long inputTokens,
                       Long outputTokens, Long totalTokens, BigDecimal costAmount,
                       String currency, boolean reliable) { }
    record SessionStatus(String state, String detail) {
        public SessionStatus(String state) { this(state, null); }
        public boolean completed() { return "COMPLETED".equalsIgnoreCase(state) || "IDLE".equalsIgnoreCase(state) || "DONE".equalsIgnoreCase(state); }
        public boolean failed() { return "FAILED".equalsIgnoreCase(state) || "ERROR".equalsIgnoreCase(state) || "ABORTED".equalsIgnoreCase(state)
                || "TIMED_OUT".equalsIgnoreCase(state) || "RETRY".equalsIgnoreCase(state); }
    }
}
