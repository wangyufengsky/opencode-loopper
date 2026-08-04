package io.opencode.loopper.runtime;

import tools.jackson.databind.JsonNode;
import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.SessionFailure;
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
                    permissionRule("bash", "git push", "deny"),
                    permissionRule("bash", "git push *", "deny"),
                    permissionRule("bash", "git reset --hard*", "deny"),
                    permissionRule("bash", "rm -rf*", "deny")
            ));
            if (readOnly) {
                // A judge can inspect the worktree through OpenCode's read facilities, but cannot
                // change files, execute a shell, or delegate a potentially mutating sub-task.
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
                        SessionPart parsed = monitorPart(part, messageIndex, partIndex++);
                        if (parsed != null && result.size() < 200) result.add(parsed);
                    }
                } else if (message.hasNonNull("text")) {
                    result.add(new SessionPart("message-" + messageIndex, "OUTPUT", "模型输出",
                            bounded(message.path("text").asText()), null));
                }
                messageIndex++;
            }
            return new SessionTranscript(result);
        } catch (SessionFailure e) { throw e; }
        catch (RuntimeException e) { throw new SessionFailure("OPENCODE_TRANSCRIPT_FAILED", e.getMessage()); }
    }

    private SessionPart monitorPart(JsonNode part, int messageIndex, int partIndex) {
        String sourceType = part.path("type").asText("").toLowerCase();
        String id = part.path("id").asText("message-" + messageIndex + "-part-" + partIndex);
        if ("text".equals(sourceType)) {
            String content = bounded(part.path("text").asText(""));
            return content.isBlank() ? null : new SessionPart(id, "OUTPUT", "模型输出", content, null);
        }
        if ("reasoning".equals(sourceType) || "thinking".equals(sourceType)) {
            String content = firstText(part.path("text"), part.path("content"), part.path("reasoning"));
            return content.isBlank() ? null : new SessionPart(id, "THINKING", "Thinking", bounded(content), part.path("state").asText(null));
        }
        if ("tool".equals(sourceType) || "tool-call".equals(sourceType) || "tool_invocation".equals(sourceType)) {
            JsonNode state = part.path("state");
            String label = firstText(part.path("tool"), part.path("name"), state.path("title"));
            String content = firstText(state.path("output"), state.path("title"), part.path("text"));
            String status = firstText(state.path("status"), part.path("status"));
            return new SessionPart(id, "TOOL", label.isBlank() ? "工具调用" : bounded(label), bounded(content), bounded(status));
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
                if (!info.path("error").isMissingNode() && !info.path("error").isNull()) return new SessionStatus("FAILED");
                JsonNode completed = info.path("time").path("completed");
                if (!completed.isMissingNode() && !completed.isNull()) return new SessionStatus("COMPLETED");
            }
            return relevantMessage ? new SessionStatus("RUNNING") : new SessionStatus("UNKNOWN");
        } catch (RuntimeException e) { throw new SessionFailure("OPENCODE_MESSAGES_FAILED", e.getMessage()); }
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
