package io.opencode.loopper.runtime;

import java.nio.file.Path;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Map;

public interface OpenCodeClient {
    /** Private managed-runtime variant used to keep DeepSeek structured output out of Thinking mode. */
    String STRUCTURED_NO_THINKING_VARIANT = "loopper-no-thinking";
    /** Private managed-runtime agent that bounds read-only machine-response loops. */
    String STRUCTURED_AGENT = "loopper-structured";
    int STRUCTURED_AGENT_STEPS = 24;
    double STRUCTURED_AGENT_TEMPERATURE = 0.0d;
    String STRUCTURED_AGENT_PROMPT = """
            Work only on the requested machine-response task. Use read, glob, and grep only when evidence is still missing.
            Never retry the same tool call or invent another tool name. Once enough evidence is available, stop exploring and
            immediately return exactly the response format requested by the user prompt, without commentary.
            """.strip();
    /** Private managed-runtime agent for one-shot task classification without repository exploration. */
    String ROUTER_AGENT = "loopper-router";
    int ROUTER_AGENT_STEPS = 1;
    double ROUTER_AGENT_TEMPERATURE = 0.0d;
    String ROUTER_AGENT_PROMPT = """
            Classify the supplied requirement in one response. Never call tools, inspect the repository, design a solution,
            propose implementation steps, or explain your reasoning. Return the exact object requested by the user prompt
            immediately and without commentary.
            """.strip();

    boolean healthy();
    OpenCodeSession createSession(Path worktree, String title, OpenCodeModel model);
    /** Creates a session whose permission rules deny all mutation and shell/task execution. */
    OpenCodeSession createReadOnlySession(Path worktree, String title, OpenCodeModel model);
    /** Creates a role-scoped session. Older adapters safely degrade every read-only role to the legacy read-only profile. */
    default OpenCodeSession createSession(Path worktree, String title, OpenCodeModel model, SessionProfile profile) {
        return profile == null || profile == SessionProfile.IMPLEMENTATION
                ? createSession(worktree, title, model)
                : createReadOnlySession(worktree, title, model);
    }
    void promptAsync(OpenCodeSession session, String prompt);
    /** Typed prompt transport. No caller-controlled tool list is accepted at this boundary. */
    default void promptAsync(OpenCodeSession session, PromptRequest prompt) {
        promptAsync(session, prompt == null ? "" : prompt.text());
    }
    SessionStatus sessionStatus(OpenCodeSession session);
    /** Returns the latest assistant text after a completed session, preserving the original model response. */
    String sessionOutput(OpenCodeSession session);
    /** Returns text, structured output, or the provider's typed assistant error for the latest turn. */
    default SessionResult sessionResult(OpenCodeSession session) {
        return new SessionResult(sessionOutput(session), Map.of(), null, null, 0);
    }
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
    default List<SessionTodo> sessionTodos(OpenCodeSession session) { return sessionTodoSnapshot(session).todos(); }
    default SessionTodoSnapshot sessionTodoSnapshot(OpenCodeSession session) {
        return new SessionTodoSnapshot(List.of(), false, null);
    }
    /** Discovers the bounded built-in tool ids for one canonical workspace. Failures are projected, not thrown. */
    default ToolCapabilityProbe toolCapabilities(Path worktree) {
        return new ToolCapabilityProbe(CapabilityState.UNKNOWN, List.of(), "Tool discovery is not supported by this adapter");
    }
    /** Discovers native OpenCode agents without selecting one for Loopper's first implementation phase. */
    default List<AgentInfo> agents() { return List.of(); }
    default StructuredOutputCapability structuredOutputCapability(OpenCodeModel model) {
        return new StructuredOutputCapability(CapabilityState.UNKNOWN, CapabilityState.UNKNOWN, null);
    }
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
    /**
     * Aborts a remote Session and returns the provider's positive acknowledgement.
     * Implementations must throw when the remote endpoint does not confirm the
     * request; callers may therefore use a normal return as termination proof.
     */
    default AbortConfirmation abortWithConfirmation(OpenCodeSession session) {
        abort(session);
        return AbortConfirmation.ACKNOWLEDGED;
    }
    record OpenCodeSession(String id, Path worktree) { }
    enum AbortConfirmation { ACKNOWLEDGED, ALREADY_ABSENT }
    record OpenCodeModel(String providerId, String modelId, Boolean thinking) { }
    enum SessionProfile {
        ROUTER_NO_TOOLS,
        DECOMPOSER_READ_ONLY,
        DESIGNER_INTERACTIVE_READ_ONLY,
        COMPILER_READ_ONLY,
        COMPILER_BINDING_NO_TOOLS,
        COMPILER_REPAIR_NO_TOOLS,
        REVIEWER_READ_ONLY,
        JUDGE_READ_ONLY,
        PROJECT_CONVENTION_READ_ONLY,
        MACHINE_FINALIZER_NO_TOOLS,
        GENERAL_READ_ONLY,
        IMPLEMENTATION
    }
    sealed interface ResponseFormat permits ResponseFormat.Text, ResponseFormat.JsonSchema {
        record Text() implements ResponseFormat { }
        record JsonSchema(String schemaId, Map<String, Object> schema, int retryCount) implements ResponseFormat {
            public JsonSchema {
                schema = schema == null ? Map.of() : Map.copyOf(schema);
                retryCount = Math.max(0, retryCount);
            }
        }
    }
    record FilePart(String filename, String mediaType, URI managedUri, String sha256) {
        public FilePart {
            if (filename == null || filename.isBlank()) throw new IllegalArgumentException("File part filename is required");
            if (mediaType == null || mediaType.isBlank()) throw new IllegalArgumentException("File part media type is required");
            if (managedUri == null || !"file".equalsIgnoreCase(managedUri.getScheme())) {
                throw new IllegalArgumentException("File part must use a managed file URI");
            }
            if (sha256 == null || sha256.isBlank()) throw new IllegalArgumentException("File part SHA-256 is required");
        }
    }
    record PromptRequest(String text, String system, String agent, ResponseFormat responseFormat,
                         String messageId, List<FilePart> files) {
        public PromptRequest {
            text = text == null ? "" : text;
            responseFormat = responseFormat == null ? new ResponseFormat.Text() : responseFormat;
            messageId = messageId == null || messageId.isBlank() ? null : messageId;
            files = files == null ? List.of() : List.copyOf(files);
        }
        public PromptRequest(String text, String system, String agent, ResponseFormat responseFormat) {
            this(text, system, agent, responseFormat, null, List.of());
        }
        public static PromptRequest text(String text) {
            return new PromptRequest(text, null, null, new ResponseFormat.Text(), null, List.of());
        }
    }
    record SessionResult(String text, Map<String, Object> structured, String errorType,
                         String errorDetail, int structuredRetryCount) {
        public SessionResult {
            text = text == null ? "" : text;
            // JSON objects may legitimately contain top-level null values (for example reason:null).
            // Map.copyOf rejects those values, so retain them in an unmodifiable insertion-ordered copy.
            structured = structured == null ? Map.of()
                    : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(structured));
            structuredRetryCount = Math.max(0, structuredRetryCount);
        }
        public boolean hasStructured() { return !structured.isEmpty(); }
    }
    enum CapabilityState { AVAILABLE, UNAVAILABLE, UNKNOWN }
    record StructuredOutputCapability(CapabilityState transport, CapabilityState selectedModel, String detail) {
        public StructuredOutputCapability {
            transport = transport == null ? CapabilityState.UNKNOWN : transport;
            selectedModel = selectedModel == null ? CapabilityState.UNKNOWN : selectedModel;
        }
    }
    record AgentInfo(String name, String mode, String description) { }
    record ToolCapabilityProbe(CapabilityState state, List<String> toolIds, String detail) {
        public ToolCapabilityProbe {
            state = state == null ? CapabilityState.UNKNOWN : state;
            toolIds = toolIds == null ? List.of() : List.copyOf(toolIds);
        }
        public boolean contains(String toolId) { return toolId != null && toolIds.stream().anyMatch(toolId::equals); }
    }
    record SessionTranscript(List<SessionPart> parts, List<UsageRecord> usage) {
        public SessionTranscript(List<SessionPart> parts) { this(parts, List.of()); }
        public SessionTranscript {
            parts = parts == null ? List.of() : List.copyOf(parts);
            usage = usage == null ? List.of() : List.copyOf(usage);
        }
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
            metadata = metadata == null ? Map.of()
                    : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(metadata));
        }
    }
    enum PermissionReply { ONCE, SESSION, REJECT }
    record SessionTodo(String id, String content, String status, String priority, int ordinal,
                       Map<String, Object> metadata) {
        public SessionTodo {
            metadata = metadata == null ? Map.of()
                    : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(metadata));
        }
    }
    record SessionTodoSnapshot(List<SessionTodo> todos, boolean truncated, String detail) {
        public SessionTodoSnapshot { todos = todos == null ? List.of() : List.copyOf(todos); }
    }
    record UsageRecord(String messageId, String providerId, String modelId, Long inputTokens,
                       Long outputTokens, Long totalTokens, BigDecimal costAmount,
                       String currency, boolean reliable) { }
    record SessionStatus(String state, String detail) {
        public SessionStatus(String state) { this(state, null); }
        public boolean completed() { return "COMPLETED".equalsIgnoreCase(state) || "IDLE".equalsIgnoreCase(state) || "DONE".equalsIgnoreCase(state); }
        /** RETRY is provider-managed recovery on the same remote Session, not a terminal failure. */
        public boolean failed() { return "FAILED".equalsIgnoreCase(state) || "ERROR".equalsIgnoreCase(state) || "ABORTED".equalsIgnoreCase(state)
                || "TIMED_OUT".equalsIgnoreCase(state); }
        public boolean retrying() { return "RETRY".equalsIgnoreCase(state); }
    }
}
