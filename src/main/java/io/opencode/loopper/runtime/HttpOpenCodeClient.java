package io.opencode.loopper.runtime;

import tools.jackson.databind.JsonNode;
import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.SessionFailure;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/** Thin adapter for the local OpenCode server; all transport faults become SessionFailure. */
public class HttpOpenCodeClient implements OpenCodeClient {
    private final RestClient.Builder baseBuilder;
    private final Supplier<ConnectionDetails> connectionSupplier;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    public HttpOpenCodeClient(RestClient.Builder builder, LoopperProperties properties) {
        this(builder, () -> new ConnectionDetails(properties.getOpenCode().getBaseUrl(), properties.getOpenCode().getUsername(), properties.getOpenCode().getPassword()),
                new Timeouts(properties.getOpenCode().getConnectTimeout(), properties.getOpenCode().getRequestTimeout()));
    }
    /** Runtime manager supplies an ephemeral connection without exposing its password in API DTOs. */
    public HttpOpenCodeClient(RestClient.Builder builder, URI baseUrl, String username, String password) {
        this(builder, () -> new ConnectionDetails(baseUrl, username, password), new Timeouts(Duration.ofSeconds(5), Duration.ofSeconds(30)));
    }
    /** Resolves credentials and endpoint for every request so managed restart can rotate both safely. */
    public HttpOpenCodeClient(RestClient.Builder builder, Supplier<OpenCodeRuntimeManager.Connection> connectionSupplier) {
        this(builder, connectionSupplier, Duration.ofSeconds(5), Duration.ofSeconds(30));
    }
    /** Dynamic managed connections still use the configured bounded transport. */
    public HttpOpenCodeClient(RestClient.Builder builder, Supplier<OpenCodeRuntimeManager.Connection> connectionSupplier,
                              LoopperProperties properties) {
        this(builder, connectionSupplier, properties.getOpenCode().getConnectTimeout(), properties.getOpenCode().getRequestTimeout());
    }
    private HttpOpenCodeClient(RestClient.Builder builder, Supplier<OpenCodeRuntimeManager.Connection> connectionSupplier,
                               Duration connectTimeout, Duration requestTimeout) {
        this(builder, () -> {
            OpenCodeRuntimeManager.Connection connection = connectionSupplier.get();
            return new ConnectionDetails(connection.endpoint(), connection.username(), connection.password());
        }, new Timeouts(connectTimeout, requestTimeout));
    }
    private HttpOpenCodeClient(RestClient.Builder builder, Supplier<ConnectionDetails> connectionSupplier,
                               Timeouts timeouts) {
        this.baseBuilder = builder;
        this.connectionSupplier = connectionSupplier;
        this.connectTimeout = timeouts.connectTimeout();
        this.requestTimeout = timeouts.requestTimeout();
    }
    @Override public boolean healthy() {
        try { client().get().uri("/global/health").retrieve().toBodilessEntity(); return true; }
        catch (RuntimeException ignored) { return false; }
    }
    @Override public OpenCodeSession createSession(Path worktree, String title, OpenCodeModel model) {
        return createSession(worktree, title, model, false);
    }
    @Override public OpenCodeSession createReadOnlySession(Path worktree, String title, OpenCodeModel model) {
        return createSession(worktree, title, model, true);
    }
    private OpenCodeSession createSession(Path worktree, String title, OpenCodeModel model, boolean readOnly) {
        try {
            Path canonical = worktree.toRealPath();
            Map<String, Object> request = new LinkedHashMap<>();
            if (title != null && !title.isBlank()) request.put("title", title);
            if (model != null && model.providerId() != null && !model.providerId().isBlank() && model.modelId() != null && !model.modelId().isBlank()) {
                request.put("model", Map.of("id", model.modelId(), "providerID", model.providerId()));
            }
            // Session rules apply even when Loopper safely reuses an operator-owned server.
            // Deliberately leave ordinary in-worktree edit/bash at the server default; this
            // is a narrow deny-list, not an implicit blanket permission grant.
            List<Map<String, String>> permissions = new java.util.ArrayList<>(List.of(
                    permissionRule("external_directory", "*", "deny"),
                    permissionRule("bash", "*git*commit*", "deny"),
                    permissionRule("bash", "*git*commit-tree*", "deny"),
                    permissionRule("bash", "*git*update-ref*", "deny"),
                    permissionRule("bash", "*git*symbolic-ref*", "deny"),
                    permissionRule("bash", "*git*push*", "deny"),
                    permissionRule("bash", "*git*branch*", "deny"),
                    permissionRule("bash", "*git*checkout*", "deny"),
                    permissionRule("bash", "*git*switch*", "deny"),
                    permissionRule("bash", "*git*merge*", "deny"),
                    permissionRule("bash", "*git*rebase*", "deny"),
                    permissionRule("bash", "*git*cherry-pick*", "deny"),
                    permissionRule("bash", "*git*tag*", "deny"),
                    permissionRule("bash", "*git*stash*", "deny"),
                    permissionRule("bash", "*git*worktree*", "deny"),
                    permissionRule("bash", "*git*fetch*", "deny"),
                    permissionRule("bash", "*git*pull*", "deny"),
                    permissionRule("bash", "git reset --hard*", "deny"),
                    permissionRule("bash", "rm -rf*", "deny")
            ));
            if (readOnly) {
                // A judge can inspect the worktree through OpenCode's read facilities, but cannot
                // change files, execute a shell, or delegate a potentially mutating sub-task.
                // Explicitly allow the bounded read tools because an operator-owned OpenCode
                // server may default them to "ask"; judge sessions are internal and are not
                // exposed as actionable Inbox writers, so leaving them at "ask" deadlocks review.
                permissions.add(permissionRule("read", "*", "allow"));
                permissions.add(permissionRule("glob", "*", "allow"));
                permissions.add(permissionRule("grep", "*", "allow"));
                permissions.add(permissionRule("edit", "*", "deny"));
                permissions.add(permissionRule("write", "*", "deny"));
                permissions.add(permissionRule("bash", "*", "deny"));
                permissions.add(permissionRule("task", "*", "deny"));
            }
            request.put("permission", permissions);
            JsonNode body = client().post().uri(uri -> uri.path("/session").queryParam("directory", canonical.toString()).build())
                    .contentType(MediaType.APPLICATION_JSON).body(request)
                    .retrieve().body(JsonNode.class);
            String id = body == null ? null : body.path("id").asText(null);
            if (id == null && body != null) id = body.path("session").path("id").asText(null);
            if (id == null || id.isBlank()) throw new SessionFailure("OPENCODE_INVALID_RESPONSE", "OpenCode did not return a session id");
            String reportedDirectory = body.path("directory").asText(null);
            if ((reportedDirectory == null || reportedDirectory.isBlank()) && body.has("session")) {
                reportedDirectory = body.path("session").path("directory").asText(null);
            }
            if (reportedDirectory == null || reportedDirectory.isBlank()) {
                throw new SessionFailure("OPENCODE_DIRECTORY_MISSING",
                        "OpenCode did not confirm the execution directory for the new session");
            }
            Path reported = Path.of(reportedDirectory).toRealPath();
            if (!reported.equals(canonical)) {
                throw new SessionFailure("OPENCODE_DIRECTORY_MISMATCH",
                        "OpenCode created the session outside the requested execution workspace");
            }
            return new OpenCodeSession(id, canonical);
        } catch (SessionFailure e) { throw e; }
        catch (Exception e) { throw new SessionFailure("OPENCODE_SESSION_CREATE_FAILED", e.getMessage()); }
    }
    private static Map<String, String> permissionRule(String permission, String pattern, String action) {
        return Map.of("permission", permission, "pattern", pattern, "action", action);
    }
    @Override public void promptAsync(OpenCodeSession session, String prompt) {
        try {
            client().post().uri(uri -> uri.path("/session/{id}/prompt_async").queryParam("directory", session.worktree().toString()).build(session.id())).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("parts", java.util.List.of(Map.of("type", "text", "text", prompt)))).retrieve().toBodilessEntity();
        } catch (RuntimeException e) { throw new SessionFailure("OPENCODE_PROMPT_FAILED", e.getMessage()); }
    }
    @Override public SessionStatus sessionStatus(OpenCodeSession session) {
        try {
            JsonNode body = client().get().uri(uri -> uri.path("/session/status").queryParam("directory", session.worktree().toString()).build())
                    .retrieve().body(JsonNode.class);
            JsonNode entry = body == null ? null : body.get(session.id());
            if (entry == null || entry.isNull()) {
                return messageStatus(session);
            }
            String state = entry.isTextual() ? entry.asText() : entry.path("status").asText(null);
            if (state == null || state.isBlank()) state = entry.isTextual() ? entry.asText() : entry.path("type").asText(null);
            if (state == null || state.isBlank()) return new SessionStatus("UNKNOWN");
            String detail = entry.isTextual() ? null : entry.path("message").asText(null);
            if ((detail == null || detail.isBlank()) && !entry.isTextual()) {
                detail = entry.path("action").path("message").asText(null);
            }
            return new SessionStatus(state, detail);
        } catch (SessionFailure e) { throw e; }
        catch (RuntimeException e) { throw new SessionFailure("OPENCODE_STATUS_FAILED", e.getMessage()); }
    }
    @Override public String sessionOutput(OpenCodeSession session) {
        try {
            JsonNode messages = sessionMessages(session);
            String latest = null;
            for (JsonNode message : messages) {
                JsonNode info = message.path("info");
                String role = info.path("role").asText(message.path("role").asText(""));
                if (!"assistant".equalsIgnoreCase(role)) continue;
                String output = assistantText(message);
                if (!output.isBlank()) latest = output;
            }
            if (latest == null) throw new SessionFailure("OPENCODE_OUTPUT_MISSING", "OpenCode completed without assistant text");
            return latest;
        } catch (SessionFailure e) { throw e; }
        catch (RuntimeException e) { throw new SessionFailure("OPENCODE_OUTPUT_FAILED", e.getMessage()); }
    }

    @Override public String sessionLiveOutput(OpenCodeSession session) {
        try {
            JsonNode messages = sessionMessages(session);
            int latestUserIndex = -1;
            String latest = "";
            int index = 0;
            for (JsonNode message : messages) {
                JsonNode info = message.path("info");
                String role = info.path("role").asText(message.path("role").asText(""));
                if ("user".equalsIgnoreCase(role)) {
                    latestUserIndex = index;
                    latest = "";
                } else if ("assistant".equalsIgnoreCase(role) && index > latestUserIndex) {
                    String output = assistantText(message);
                    if (!output.isBlank()) latest = output;
                }
                index++;
            }
            return bounded(latest);
        } catch (SessionFailure e) { throw e; }
        catch (RuntimeException e) { throw new SessionFailure("OPENCODE_LIVE_OUTPUT_FAILED", e.getMessage()); }
    }

    @Override public SessionTranscript sessionTranscript(OpenCodeSession session) {
        try {
            JsonNode messages = sessionMessages(session);
            List<SessionPart> result = new ArrayList<>();
            int messageIndex = 0;
            for (JsonNode message : messages) {
                JsonNode info = message.path("info");
                String role = info.path("role").asText(message.path("role").asText(""));
                if (!"assistant".equalsIgnoreCase(role)) { messageIndex++; continue; }
                JsonNode parts = message.path("parts");
                if (parts.isArray()) {
                    int partIndex = 0;
                    for (JsonNode part : parts) {
                        SessionPart parsed = monitorPart(part, message, messageIndex, partIndex++);
                        if (parsed != null && result.size() < 200) result.add(parsed);
                    }
                } else if (message.hasNonNull("text")) {
                    result.add(new SessionPart("message-" + messageIndex, "OUTPUT", "模型输出",
                            bounded(message.path("text").asText()), null, startedAt(message.path("info").path("time").path("created"))));
                }
                messageIndex++;
            }
            return new SessionTranscript(result);
        } catch (SessionFailure e) { throw e; }
        catch (RuntimeException e) { throw new SessionFailure("OPENCODE_TRANSCRIPT_FAILED", e.getMessage()); }
    }

    @Override public List<SessionMessageRef> sessionMessageRefs(OpenCodeSession session) {
        try {
            List<SessionMessageRef> result = new ArrayList<>();
            for (JsonNode message : sessionMessages(session)) {
                JsonNode info = message.path("info");
                String id = firstText(info.path("id"), message.path("id"));
                if (id.isBlank()) continue;
                String role = firstText(info.path("role"), message.path("role"));
                result.add(new SessionMessageRef(id, role,
                        startedAt(info.path("time").path("created")),
                        startedAt(info.path("time").path("completed"))));
            }
            return List.copyOf(result);
        } catch (SessionFailure failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new SessionFailure("OPENCODE_MESSAGE_REFS_FAILED", failure.getMessage());
        }
    }

    @Override public List<PendingQuestion> pendingQuestions(OpenCodeSession session) {
        try {
            JsonNode body = client().get().uri(uri -> uri.path("/question")
                            .queryParam("directory", session.worktree().toString()).build())
                    .retrieve().body(JsonNode.class);
            JsonNode requests = body != null && body.isArray() ? body : body == null ? null : body.path("data");
            if (requests == null || !requests.isArray()) {
                throw new SessionFailure("OPENCODE_QUESTION_INVALID_RESPONSE", "OpenCode did not return a pending question list");
            }
            List<PendingQuestion> result = new ArrayList<>();
            for (JsonNode request : requests) {
                String sessionId = request.path("sessionID").asText("");
                if (!session.id().equals(sessionId)) continue;
                String requestId = request.path("id").asText("");
                if (requestId.isBlank()) continue;
                List<QuestionPrompt> questions = new ArrayList<>();
                JsonNode prompts = request.path("questions");
                if (prompts.isArray()) {
                    for (JsonNode prompt : prompts) {
                        List<QuestionOption> options = new ArrayList<>();
                        JsonNode optionNodes = prompt.path("options");
                        if (optionNodes.isArray()) {
                            for (JsonNode option : optionNodes) {
                                options.add(new QuestionOption(option.path("label").asText(""), option.path("description").asText("")));
                            }
                        }
                        questions.add(new QuestionPrompt(prompt.path("question").asText(""), prompt.path("header").asText(""),
                                options, prompt.path("multiple").asBoolean(false), !prompt.has("custom") || prompt.path("custom").asBoolean(true)));
                    }
                }
                result.add(new PendingQuestion(requestId, sessionId, questions));
            }
            return List.copyOf(result);
        } catch (SessionFailure e) { throw e; }
        catch (RuntimeException e) { throw new SessionFailure("OPENCODE_QUESTION_LIST_FAILED", e.getMessage()); }
    }

    @Override public void replyQuestion(OpenCodeSession session, String requestId, List<List<String>> answers) {
        try {
            client().post().uri(uri -> uri.path("/question/{requestId}/reply")
                            .queryParam("directory", session.worktree().toString()).build(requestId))
                    .contentType(MediaType.APPLICATION_JSON).body(Map.of("answers", answers)).retrieve().toBodilessEntity();
        } catch (RuntimeException e) { throw new SessionFailure("OPENCODE_QUESTION_REPLY_FAILED", e.getMessage()); }
    }

    @Override public void rejectQuestion(OpenCodeSession session, String requestId) {
        try {
            client().post().uri(uri -> uri.path("/question/{requestId}/reject")
                            .queryParam("directory", session.worktree().toString()).build(requestId))
                    .retrieve().toBodilessEntity();
        } catch (RuntimeException e) { throw new SessionFailure("OPENCODE_QUESTION_REJECT_FAILED", e.getMessage()); }
    }

    @Override public List<PendingPermission> pendingPermissions(OpenCodeSession session) {
        try {
            JsonNode body = client().get().uri(uri -> uri.path("/permission")
                            .queryParam("directory", session.worktree().toString()).build())
                    .retrieve().body(JsonNode.class);
            JsonNode requests = listBody(body);
            if (requests == null) {
                throw new SessionFailure("OPENCODE_PERMISSION_INVALID_RESPONSE", "OpenCode did not return a pending permission list");
            }
            List<PendingPermission> result = new ArrayList<>();
            for (JsonNode request : requests) {
                String sessionId = request.path("sessionID").asText("");
                if (!session.id().equals(sessionId)) continue;
                String requestId = request.path("id").asText("");
                if (requestId.isBlank()) continue;
                JsonNode metadata = request.path("metadata");
                result.add(new PendingPermission(requestId, sessionId, request.path("permission").asText(""),
                        strings(request.path("patterns")), object(metadata),
                        firstText(metadata.path("title"), metadata.path("description"), request.path("permission"))));
            }
            return List.copyOf(result);
        } catch (SessionFailure e) { throw e; }
        catch (RuntimeException e) { throw new SessionFailure("OPENCODE_PERMISSION_LIST_FAILED", e.getMessage()); }
    }

    @Override public void replyPermission(OpenCodeSession session, String requestId, PermissionReply reply, String message) {
        if (requestId == null || requestId.isBlank()) throw new SessionFailure("OPENCODE_PERMISSION_ID_REQUIRED", "Permission request id is required");
        if (reply == null) throw new SessionFailure("OPENCODE_PERMISSION_REPLY_REQUIRED", "Permission reply is required");
        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("reply", switch (reply) {
                case ONCE -> "once";
                case SESSION -> "always";
                case REJECT -> "reject";
            });
            if (message != null && !message.isBlank()) request.put("message", message);
            client().post().uri(uri -> uri.path("/permission/{requestId}/reply")
                            .queryParam("directory", session.worktree().toString()).build(requestId))
                    .contentType(MediaType.APPLICATION_JSON).body(request).retrieve().toBodilessEntity();
        } catch (SessionFailure e) { throw e; }
        catch (RuntimeException e) { throw new SessionFailure("OPENCODE_PERMISSION_REPLY_FAILED", e.getMessage()); }
    }

    @Override public List<SessionTodo> sessionTodos(OpenCodeSession session) {
        try {
            JsonNode body = client().get().uri(uri -> uri.path("/session/{id}/todo")
                            .queryParam("directory", session.worktree().toString()).build(session.id()))
                    .retrieve().body(JsonNode.class);
            JsonNode todos = listBody(body);
            if (todos == null) throw new SessionFailure("OPENCODE_TODO_INVALID_RESPONSE", "OpenCode did not return a todo list");
            List<SessionTodo> result = new ArrayList<>();
            int ordinal = 0;
            for (JsonNode todo : todos) {
                String id = todo.path("id").asText("");
                if (id.isBlank()) id = session.id() + ":todo:" + ordinal;
                result.add(new SessionTodo(id, todo.path("content").asText(""), todo.path("status").asText(""),
                        todo.path("priority").asText(""), ordinal++, object(todo.path("metadata"))));
            }
            return List.copyOf(result);
        } catch (SessionFailure e) { throw e; }
        catch (RuntimeException e) { throw new SessionFailure("OPENCODE_TODO_LIST_FAILED", e.getMessage()); }
    }

    @Override public OpenCodeSession forkSession(OpenCodeSession session, String messageId) {
        try {
            Map<String, Object> request = new LinkedHashMap<>();
            if (messageId != null && !messageId.isBlank()) request.put("messageID", messageId);
            JsonNode body = client().post().uri(uri -> uri.path("/session/{id}/fork")
                            .queryParam("directory", session.worktree().toString()).build(session.id()))
                    .contentType(MediaType.APPLICATION_JSON).body(request).retrieve().body(JsonNode.class);
            String id = body == null ? null : body.path("id").asText(null);
            if (id == null && body != null) id = body.path("session").path("id").asText(null);
            if (id == null || id.isBlank()) throw new SessionFailure("OPENCODE_FORK_INVALID_RESPONSE", "OpenCode did not return a forked session id");
            return new OpenCodeSession(id, session.worktree());
        } catch (SessionFailure e) { throw e; }
        catch (RuntimeException e) { throw new SessionFailure("OPENCODE_FORK_FAILED", e.getMessage()); }
    }

    @Override public void revertSession(OpenCodeSession session, String messageId, String partId) {
        if (messageId == null || messageId.isBlank()) throw new SessionFailure("OPENCODE_REVERT_MESSAGE_REQUIRED", "Revert requires a message id");
        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("messageID", messageId);
            if (partId != null && !partId.isBlank()) request.put("partID", partId);
            client().post().uri(uri -> uri.path("/session/{id}/revert")
                            .queryParam("directory", session.worktree().toString()).build(session.id()))
                    .contentType(MediaType.APPLICATION_JSON).body(request).retrieve().toBodilessEntity();
        } catch (SessionFailure e) { throw e; }
        catch (RuntimeException e) { throw new SessionFailure("OPENCODE_REVERT_FAILED", e.getMessage()); }
    }

    @Override public void summarizeSession(OpenCodeSession session, OpenCodeModel model, boolean automatic) {
        try {
            Map<String, Object> request = new LinkedHashMap<>();
            if (model != null && model.providerId() != null && !model.providerId().isBlank() && model.modelId() != null && !model.modelId().isBlank()) {
                request.put("providerID", model.providerId());
                request.put("modelID", model.modelId());
            }
            request.put("auto", automatic);
            client().post().uri(uri -> uri.path("/session/{id}/summarize")
                            .queryParam("directory", session.worktree().toString()).build(session.id()))
                    .contentType(MediaType.APPLICATION_JSON).body(request).retrieve().toBodilessEntity();
        } catch (SessionFailure e) { throw e; }
        catch (RuntimeException e) { throw new SessionFailure("OPENCODE_SUMMARIZE_FAILED", e.getMessage()); }
    }

    @Override public List<UsageRecord> sessionUsage(OpenCodeSession session) {
        try {
            List<UsageRecord> result = new ArrayList<>();
            for (JsonNode message : sessionMessages(session)) {
                JsonNode info = message.path("info");
                String role = info.path("role").asText(message.path("role").asText(""));
                if (!"assistant".equalsIgnoreCase(role)) continue;
                String messageId = firstText(info.path("id"), message.path("id"));
                if (messageId.isBlank()) continue;
                JsonNode tokens = info.path("tokens");
                Long input = nullableLong(tokens.path("input"));
                Long output = nullableLong(tokens.path("output"));
                Long total = nullableLong(tokens.path("total"));
                BigDecimal cost = nullableDecimal(info.path("cost"));
                boolean reliable = input != null || output != null || total != null || cost != null;
                result.add(new UsageRecord(messageId, nullableText(info.path("providerID")), nullableText(info.path("modelID")),
                        input, output, total, cost, nullableText(info.path("currency")), reliable));
            }
            return List.copyOf(result);
        } catch (SessionFailure e) { throw e; }
        catch (RuntimeException e) { throw new SessionFailure("OPENCODE_USAGE_LIST_FAILED", e.getMessage()); }
    }

    private SessionPart monitorPart(JsonNode part, JsonNode message, int messageIndex, int partIndex) {
        String sourceType = part.path("type").asText("").toLowerCase();
        String id = part.path("id").asText("message-" + messageIndex + "-part-" + partIndex);
        String startedAt = startedAt(part.path("time").path("start"), part.path("state").path("time").path("start"),
                message.path("info").path("time").path("created"));
        if ("text".equals(sourceType)) {
            String content = bounded(part.path("text").asText(""));
            return content.isBlank() ? null : new SessionPart(id, "OUTPUT", "模型输出", content, null, startedAt);
        }
        if ("reasoning".equals(sourceType) || "thinking".equals(sourceType)) {
            String content = firstText(part.path("text"), part.path("content"), part.path("reasoning"));
            return content.isBlank() ? null : new SessionPart(id, "THINKING", "Thinking", bounded(content), part.path("state").asText(null), startedAt);
        }
        if ("tool".equals(sourceType) || "tool-call".equals(sourceType) || "tool_invocation".equals(sourceType)) {
            JsonNode state = part.path("state");
            String label = firstText(part.path("tool"), part.path("name"), state.path("title"));
            String content = firstText(state.path("output"), state.path("title"), part.path("text"));
            String status = firstText(state.path("status"), part.path("status"));
            return new SessionPart(id, "TOOL", label.isBlank() ? "工具调用" : bounded(label), bounded(content), bounded(status), startedAt);
        }
        return null;
    }

    private String startedAt(JsonNode... candidates) {
        for (JsonNode candidate : candidates) {
            if (candidate == null || candidate.isMissingNode() || candidate.isNull()) continue;
            if (candidate.isNumber()) {
                long value = candidate.asLong();
                if (value <= 0) continue;
                return (value >= 10_000_000_000L ? java.time.Instant.ofEpochMilli(value) : java.time.Instant.ofEpochSecond(value)).toString();
            }
            if (candidate.isTextual() && !candidate.asText().isBlank()) return candidate.asText();
        }
        return null;
    }

    private String firstText(JsonNode... candidates) {
        for (JsonNode candidate : candidates) {
            if (candidate != null && candidate.isValueNode()) {
                String value = candidate.asText("");
                if (!value.isBlank()) return value;
            }
        }
        return "";
    }

    private String bounded(String value) {
        if (value == null) return "";
        return value.length() <= 40_000 ? value : value.substring(0, 40_000) + "\n… output truncated by Loopper …";
    }

    private JsonNode listBody(JsonNode body) {
        JsonNode value = body != null && body.isArray() ? body : body == null ? null : body.path("data");
        return value != null && value.isArray() ? value : null;
    }

    private List<String> strings(JsonNode value) {
        if (value == null || !value.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonNode item : value) if (item.isValueNode()) result.add(item.asText(""));
        return List.copyOf(result);
    }

    private Map<String, Object> object(JsonNode value) {
        if (value == null || !value.isObject()) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : value.properties()) {
            Object mapped = jsonValue(entry.getValue());
            if (mapped != null) result.put(entry.getKey(), mapped);
        }
        return Map.copyOf(result);
    }

    private Object jsonValue(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) return null;
        if (value.isObject()) return object(value);
        if (value.isArray()) {
            List<Object> result = new ArrayList<>();
            for (JsonNode item : value) {
                Object mapped = jsonValue(item);
                if (mapped != null) result.add(mapped);
            }
            return List.copyOf(result);
        }
        if (value.isBoolean()) return value.booleanValue();
        if (value.isIntegralNumber()) return value.longValue();
        if (value.isNumber()) return value.decimalValue();
        return value.asText("");
    }

    private Long nullableLong(JsonNode value) {
        return value == null || value.isMissingNode() || value.isNull() || !value.isNumber() ? null : value.longValue();
    }

    private BigDecimal nullableDecimal(JsonNode value) {
        return value == null || value.isMissingNode() || value.isNull() || !value.isNumber() ? null : value.decimalValue();
    }

    private String nullableText(JsonNode value) {
        return value == null || value.isMissingNode() || value.isNull() || value.asText("").isBlank() ? null : value.asText();
    }

    private JsonNode sessionMessages(OpenCodeSession session) {
        JsonNode body = client().get().uri(uri -> uri.path("/session/{id}/message").queryParam("directory", session.worktree().toString()).build(session.id()))
                .retrieve().body(JsonNode.class);
        JsonNode messages = body != null && body.isArray() ? body : body == null ? null : body.path("data");
        if (messages == null || !messages.isArray()) throw new SessionFailure("OPENCODE_OUTPUT_MISSING", "OpenCode did not return a message list");
        return messages;
    }
    private String assistantText(JsonNode message) {
        StringBuilder text = new StringBuilder();
        JsonNode parts = message.path("parts");
        if (parts.isArray()) {
            for (JsonNode part : parts) {
                if ("text".equalsIgnoreCase(part.path("type").asText()) && part.hasNonNull("text")) {
                    if (!text.isEmpty()) text.append('\n');
                    text.append(part.path("text").asText());
                }
            }
        }
        if (text.isEmpty() && message.hasNonNull("text")) text.append(message.path("text").asText());
        return text.toString();
    }
    private SessionStatus messageStatus(OpenCodeSession session) {
        try {
            JsonNode messages = sessionMessages(session);
            boolean relevantMessage = false;
            int latestUserIndex = -1;
            int latestAssistantIndex = -1;
            JsonNode latestAssistant = null;
            int index = 0;
            for (JsonNode message : messages) {
                JsonNode info = message.path("info");
                String role = info.path("role").asText(message.path("role").asText(""));
                if (!"assistant".equalsIgnoreCase(role) && !"user".equalsIgnoreCase(role)) { index++; continue; }
                relevantMessage = true;
                if ("user".equalsIgnoreCase(role)) latestUserIndex = index;
                if ("assistant".equalsIgnoreCase(role)) { latestAssistant = message; latestAssistantIndex = index; }
                index++;
            }
            // A reusable session retains its earlier replies.  It is complete only
            // when the latest assistant reply follows the latest user prompt.
            if (latestAssistant != null && latestAssistantIndex > latestUserIndex) {
                JsonNode info = latestAssistant.path("info");
                if (!info.path("error").isMissingNode() && !info.path("error").isNull()) {
                    return new SessionStatus("FAILED", errorDetail(info.path("error")));
                }
                JsonNode completed = info.path("time").path("completed");
                if (!completed.isMissingNode() && !completed.isNull()) return new SessionStatus("COMPLETED");
            }
            return relevantMessage ? new SessionStatus("RUNNING") : new SessionStatus("UNKNOWN");
        } catch (RuntimeException e) { throw new SessionFailure("OPENCODE_MESSAGES_FAILED", e.getMessage()); }
    }
    private String errorDetail(JsonNode error) {
        String detail = firstText(error.path("message"), error.path("data").path("message"),
                error.path("name"), error.path("code"));
        return detail.isBlank() ? error.toString() : detail;
    }
    @Override public String diff(OpenCodeSession session) {
        try {
            return client().get().uri(uri -> uri.path("/session/{id}/diff").queryParam("directory", session.worktree().toString()).build(session.id()))
                    .retrieve().body(String.class);
        } catch (RuntimeException e) { throw new SessionFailure("OPENCODE_DIFF_FAILED", e.getMessage()); }
    }
    @Override public void abort(OpenCodeSession session) {
        try { client().post().uri(uri -> uri.path("/session/{id}/abort").queryParam("directory", session.worktree().toString()).build(session.id())).retrieve().toBodilessEntity(); }
        catch (RuntimeException e) { throw new SessionFailure("OPENCODE_ABORT_FAILED", e.getMessage()); }
    }
    private RestClient client() {
        ConnectionDetails connection = connectionSupplier.get();
        var spec = OpenCodeHttpTransport.bounded(baseBuilder, connectTimeout, requestTimeout).baseUrl(connection.baseUrl().toString());
        if (connection.username() != null && !connection.username().isBlank()) {
            spec.defaultHeaders(headers -> headers.setBasicAuth(connection.username(), connection.password() == null ? "" : connection.password()));
        }
        return spec.build();
    }
    private record ConnectionDetails(URI baseUrl, String username, String password) { }
    private record Timeouts(Duration connectTimeout, Duration requestTimeout) { }
}
