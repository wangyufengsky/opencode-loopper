package io.opencode.loopper.runtime;

import tools.jackson.databind.JsonNode;
import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.SessionFailure;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Thin adapter for the local OpenCode server; all transport faults become SessionFailure. */
public class HttpOpenCodeClient implements OpenCodeClient {
    private static final int MAX_TODOS = 64;
    private static final int MAX_TODO_CONTENT_UTF8 = 1_024;
    private static final int MAX_TODO_TOTAL_UTF8 = 64 * 1_024;
    private final RestClient.Builder baseBuilder;
    private final Supplier<ConnectionDetails> connectionSupplier;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final OpenCodeCapabilityRegistry capabilities;
    private final Map<String, OpenCodeModel> sessionModels = new ConcurrentHashMap<>();
    private final ObjectMapper json = new ObjectMapper();
    public HttpOpenCodeClient(RestClient.Builder builder, LoopperProperties properties) {
        this(builder, () -> new ConnectionDetails(properties.getOpenCode().getBaseUrl(), properties.getOpenCode().getUsername(), properties.getOpenCode().getPassword()),
                new Timeouts(properties.getOpenCode().getConnectTimeout(), properties.getOpenCode().getRequestTimeout()),
                new OpenCodeCapabilityRegistry());
    }
    /** Runtime manager supplies an ephemeral connection without exposing its password in API DTOs. */
    public HttpOpenCodeClient(RestClient.Builder builder, URI baseUrl, String username, String password) {
        this(builder, () -> new ConnectionDetails(baseUrl, username, password),
                new Timeouts(Duration.ofSeconds(5), Duration.ofSeconds(30)), new OpenCodeCapabilityRegistry());
    }
    /** Resolves credentials and endpoint for every request so managed restart can rotate both safely. */
    public HttpOpenCodeClient(RestClient.Builder builder, Supplier<OpenCodeRuntimeManager.Connection> connectionSupplier) {
        this(builder, connectionSupplier, Duration.ofSeconds(5), Duration.ofSeconds(30),
                new OpenCodeCapabilityRegistry());
    }
    /** Dynamic managed connections still use the configured bounded transport. */
    public HttpOpenCodeClient(RestClient.Builder builder, Supplier<OpenCodeRuntimeManager.Connection> connectionSupplier,
                              LoopperProperties properties) {
        this(builder, connectionSupplier, properties.getOpenCode().getConnectTimeout(),
                properties.getOpenCode().getRequestTimeout(), new OpenCodeCapabilityRegistry());
    }
    public HttpOpenCodeClient(RestClient.Builder builder, Supplier<OpenCodeRuntimeManager.Connection> connectionSupplier,
                              LoopperProperties properties, OpenCodeCapabilityRegistry capabilities) {
        this(builder, connectionSupplier, properties.getOpenCode().getConnectTimeout(),
                properties.getOpenCode().getRequestTimeout(), capabilities);
    }
    private HttpOpenCodeClient(RestClient.Builder builder, Supplier<OpenCodeRuntimeManager.Connection> connectionSupplier,
                               Duration connectTimeout, Duration requestTimeout,
                               OpenCodeCapabilityRegistry capabilities) {
        this(builder, () -> {
            OpenCodeRuntimeManager.Connection connection = connectionSupplier.get();
            return new ConnectionDetails(connection.endpoint(), connection.username(), connection.password());
        }, new Timeouts(connectTimeout, requestTimeout), capabilities);
    }
    private HttpOpenCodeClient(RestClient.Builder builder, Supplier<ConnectionDetails> connectionSupplier,
                               Timeouts timeouts, OpenCodeCapabilityRegistry capabilities) {
        this.baseBuilder = builder;
        this.connectionSupplier = connectionSupplier;
        this.connectTimeout = timeouts.connectTimeout();
        this.requestTimeout = timeouts.requestTimeout();
        this.capabilities = capabilities;
    }
    @Override public boolean healthy() {
        try { client().get().uri("/global/health").retrieve().toBodilessEntity(); return true; }
        catch (RuntimeException ignored) { return false; }
    }
    @Override public OpenCodeSession createSession(Path worktree, String title, OpenCodeModel model) {
        return createSession(worktree, title, model, SessionProfile.IMPLEMENTATION);
    }
    @Override public OpenCodeSession createReadOnlySession(Path worktree, String title, OpenCodeModel model) {
        return createSession(worktree, title, model, SessionProfile.GENERAL_READ_ONLY);
    }
    @Override public OpenCodeSession createSession(Path worktree, String title, OpenCodeModel model,
                                                    SessionProfile profile) {
        try {
            Path canonical = worktree.toRealPath();
            Map<String, Object> request = new LinkedHashMap<>();
            if (title != null && !title.isBlank()) request.put("title", title);
            if (model != null && model.providerId() != null && !model.providerId().isBlank() && model.modelId() != null && !model.modelId().isBlank()) {
                request.put("model", Map.of("id", model.modelId(), "providerID", model.providerId()));
            }
            List<Map<String, String>> permissions = permissions(profile == null
                    ? SessionProfile.IMPLEMENTATION : profile);
            request.put("permission", permissions);
            JsonNode body = client().post().uri(uri -> directoryUri(uri, "/session", canonical))
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
            OpenCodeSession session = new OpenCodeSession(id, canonical);
            if (model != null) sessionModels.put(id, model);
            return session;
        } catch (SessionFailure e) { throw e; }
        catch (Exception e) { throw new SessionFailure("OPENCODE_SESSION_CREATE_FAILED", e.getMessage()); }
    }
    private static Map<String, String> permissionRule(String permission, String pattern, String action) {
        return Map.of("permission", permission, "pattern", pattern, "action", action);
    }
    private static List<Map<String, String>> permissions(SessionProfile profile) {
        if (profile != SessionProfile.IMPLEMENTATION) {
            List<Map<String, String>> rules = new ArrayList<>();
            rules.add(permissionRule("*", "*", "deny"));
            rules.add(permissionRule("read", "*", "allow"));
            rules.add(permissionRule("glob", "*", "allow"));
            rules.add(permissionRule("grep", "*", "allow"));
            if (profile == SessionProfile.DESIGNER_INTERACTIVE_READ_ONLY) {
                rules.add(permissionRule("question", "*", "allow"));
            }
            rules.add(permissionRule("read", ".env", "deny"));
            rules.add(permissionRule("read", ".env.*", "deny"));
            rules.add(permissionRule("read", ".env.example", "allow"));
            rules.add(permissionRule("external_directory", "*", "deny"));
            return List.copyOf(rules);
        }
        List<Map<String, String>> rules = new ArrayList<>(List.of(
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
                permissionRule("bash", "rm -rf*", "deny"),
                permissionRule("todowrite", "*", "allow")
        ));
        return List.copyOf(rules);
    }
    @Override public void promptAsync(OpenCodeSession session, String prompt) {
        promptAsync(session, PromptRequest.text(prompt));
    }
    @Override public void promptAsync(OpenCodeSession session, PromptRequest prompt) {
        boolean structured = prompt != null && prompt.responseFormat() instanceof ResponseFormat.JsonSchema;
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("parts", List.of(Map.of("type", "text", "text", prompt == null ? "" : prompt.text())));
            if (prompt != null && prompt.system() != null && !prompt.system().isBlank()) body.put("system", prompt.system());
            if (prompt != null && prompt.agent() != null && !prompt.agent().isBlank()) body.put("agent", prompt.agent());
            if (structured) {
                ResponseFormat.JsonSchema format = (ResponseFormat.JsonSchema) prompt.responseFormat();
                body.put("format", Map.of("type", "json_schema", "schema", format.schema(),
                        "retryCount", format.retryCount()));
            }
            client().post().uri(uri -> sessionUri(uri, "/session/{id}/prompt_async", session)).contentType(MediaType.APPLICATION_JSON)
                    .body(body).retrieve().toBodilessEntity();
            if (structured) capabilities.accepted(connectionSupplier.get().baseUrl(), sessionModels.get(session.id()));
        } catch (RestClientResponseException failure) {
            if (structured && formatRejected(failure)) {
                capabilities.transportUnsupported(connectionSupplier.get().baseUrl(), sessionModels.get(session.id()), failure.getMessage());
                throw new SessionFailure("OPENCODE_STRUCTURED_FORMAT_UNSUPPORTED", failure.getMessage());
            }
            throw new SessionFailure("OPENCODE_PROMPT_FAILED", failure.getMessage());
        } catch (RuntimeException e) { throw new SessionFailure("OPENCODE_PROMPT_FAILED", e.getMessage()); }
    }
    @Override public SessionStatus sessionStatus(OpenCodeSession session) {
        try {
            JsonNode body = client().get().uri(uri -> directoryUri(uri, "/session/status", session.worktree()))
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
        SessionResult result = sessionResult(session);
        if (!result.text().isBlank()) return result.text();
        if (result.hasStructured()) {
            try { return json.writeValueAsString(result.structured()); }
            catch (JacksonException failure) { throw new SessionFailure("OPENCODE_OUTPUT_FAILED", failure.getMessage()); }
        }
        throw new SessionFailure("OPENCODE_OUTPUT_MISSING", "OpenCode completed without assistant text or structured output");
    }

    @Override public SessionResult sessionResult(OpenCodeSession session) {
        try {
            JsonNode messages = sessionMessages(session);
            JsonNode latest = latestAssistantAfterUser(messages);
            if (latest == null) throw new SessionFailure("OPENCODE_OUTPUT_MISSING", "OpenCode did not return an assistant turn");
            JsonNode info = latest.path("info");
            JsonNode error = info.path("error");
            String errorType = null;
            String errorDetail = null;
            if (!error.isMissingNode() && !error.isNull()) {
                errorType = firstText(error.path("name"), error.path("code"), error.path("type"));
                errorDetail = errorDetail(error);
            }
            JsonNode structured = info.path("structured");
            if ((structured.isMissingNode() || structured.isNull()) && latest.has("structured")) {
                structured = latest.path("structured");
            }
            Map<String, Object> structuredValue = structured.isObject() ? object(structured) : Map.of();
            String text = assistantText(latest);
            int retryCount = info.path("structuredRetryCount").asInt(
                    info.path("structured_retry_count").asInt(0));
            URI endpoint = connectionSupplier.get().baseUrl();
            OpenCodeModel model = sessionModels.get(session.id());
            if (!structuredValue.isEmpty()) capabilities.structured(endpoint, model);
            if (structuredError(errorType, errorDetail)) capabilities.modelUnsupported(endpoint, model, errorDetail);
            return new SessionResult(text, structuredValue, blankToNull(errorType), blankToNull(errorDetail), retryCount);
        } catch (SessionFailure e) { throw e; }
        catch (RuntimeException e) { throw new SessionFailure("OPENCODE_OUTPUT_FAILED", e.getMessage()); }
    }

    private JsonNode latestAssistantAfterUser(JsonNode messages) {
        int latestUserIndex = -1;
        JsonNode latest = null;
        int index = 0;
        for (JsonNode message : messages) {
                JsonNode info = message.path("info");
                String role = info.path("role").asText(message.path("role").asText(""));
            if ("user".equalsIgnoreCase(role)) {
                latestUserIndex = index;
                latest = null;
            } else if ("assistant".equalsIgnoreCase(role) && index > latestUserIndex) {
                latest = message;
            }
            index++;
        }
        return latest;
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
            JsonNode body = client().get().uri(uri -> directoryUri(uri, "/question", session.worktree()))
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
            client().post().uri(uri -> directoryUri(uri, "/question/{requestId}/reply", session.worktree(),
                            Map.of("requestId", requestId)))
                    .contentType(MediaType.APPLICATION_JSON).body(Map.of("answers", answers)).retrieve().toBodilessEntity();
        } catch (RuntimeException e) { throw new SessionFailure("OPENCODE_QUESTION_REPLY_FAILED", e.getMessage()); }
    }

    @Override public void rejectQuestion(OpenCodeSession session, String requestId) {
        try {
            client().post().uri(uri -> directoryUri(uri, "/question/{requestId}/reject", session.worktree(),
                            Map.of("requestId", requestId)))
                    .retrieve().toBodilessEntity();
        } catch (RuntimeException e) { throw new SessionFailure("OPENCODE_QUESTION_REJECT_FAILED", e.getMessage()); }
    }

    @Override public List<PendingPermission> pendingPermissions(OpenCodeSession session) {
        try {
            JsonNode body = client().get().uri(uri -> directoryUri(uri, "/permission", session.worktree()))
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
            client().post().uri(uri -> directoryUri(uri, "/permission/{requestId}/reply", session.worktree(),
                            Map.of("requestId", requestId)))
                    .contentType(MediaType.APPLICATION_JSON).body(request).retrieve().toBodilessEntity();
        } catch (SessionFailure e) { throw e; }
        catch (RuntimeException e) { throw new SessionFailure("OPENCODE_PERMISSION_REPLY_FAILED", e.getMessage()); }
    }

    @Override public SessionTodoSnapshot sessionTodoSnapshot(OpenCodeSession session) {
        try {
            JsonNode body = client().get().uri(uri -> sessionUri(uri, "/session/{id}/todo", session))
                    .retrieve().body(JsonNode.class);
            JsonNode todos = listBody(body);
            if (todos == null) throw new SessionFailure("OPENCODE_TODO_INVALID_RESPONSE", "OpenCode did not return a todo list");
            List<SessionTodo> result = new ArrayList<>();
            Map<String, Integer> occurrences = new HashMap<>();
            int ordinal = 0;
            int totalBytes = 0;
            boolean truncated = false;
            for (JsonNode todo : todos) {
                if (result.size() >= MAX_TODOS) { truncated = true; break; }
                String rawContent = todo.path("content").asText("");
                String content = truncateUtf8(rawContent, MAX_TODO_CONTENT_UTF8);
                if (!content.equals(rawContent)) truncated = true;
                int bytes = content.getBytes(StandardCharsets.UTF_8).length;
                if (totalBytes + bytes > MAX_TODO_TOTAL_UTF8) { truncated = true; break; }
                totalBytes += bytes;
                String normalized = normalizeTodoContent(content);
                int occurrence = occurrences.merge(normalized, 1, Integer::sum);
                String rawStatus = todo.path("status").asText("");
                String rawPriority = todo.path("priority").asText("");
                Map<String, Object> metadata = new LinkedHashMap<>(object(todo.path("metadata")));
                metadata.put("rawStatus", rawStatus);
                metadata.put("rawPriority", rawPriority);
                String id = "todo-v2:" + sha256(normalized) + ":" + occurrence;
                result.add(new SessionTodo(id, content, todoStatus(rawStatus), todoPriority(rawPriority),
                        ordinal++, metadata));
            }
            if (truncated && !result.isEmpty()) {
                SessionTodo last = result.getLast();
                Map<String, Object> metadata = new LinkedHashMap<>(last.metadata());
                metadata.put("projectionTruncated", true);
                result.set(result.size() - 1, new SessionTodo(last.id(), last.content(), last.status(),
                        last.priority(), last.ordinal(), metadata));
            }
            return new SessionTodoSnapshot(result, truncated,
                    truncated ? "OpenCode Todo projection was truncated to Loopper safety bounds" : null);
        } catch (SessionFailure e) { throw e; }
        catch (RuntimeException e) { throw new SessionFailure("OPENCODE_TODO_LIST_FAILED", e.getMessage()); }
    }

    @Override public ToolCapabilityProbe toolCapabilities(Path worktree) {
        try {
            Path canonical = worktree.toRealPath();
            JsonNode body = client().get().uri(uri -> directoryUri(uri, "/experimental/tool/ids", canonical))
                    .retrieve().body(JsonNode.class);
            JsonNode ids = listBody(body);
            if (ids == null) return new ToolCapabilityProbe(CapabilityState.UNKNOWN, List.of(),
                    "OpenCode returned an invalid tool-id response");
            List<String> result = new ArrayList<>();
            for (JsonNode id : ids) if (id.isTextual() && !id.asText().isBlank()) result.add(id.asText());
            return new ToolCapabilityProbe(CapabilityState.AVAILABLE, result, null);
        } catch (RestClientResponseException failure) {
            if (failure.getStatusCode().value() == 404) {
                return new ToolCapabilityProbe(CapabilityState.UNAVAILABLE, List.of(),
                        "OpenCode does not expose /experimental/tool/ids");
            }
            return new ToolCapabilityProbe(CapabilityState.UNKNOWN, List.of(), bounded(failure.getMessage()));
        } catch (RuntimeException | java.io.IOException failure) {
            return new ToolCapabilityProbe(CapabilityState.UNKNOWN, List.of(), bounded(failure.getMessage()));
        }
    }

    @Override public List<AgentInfo> agents() {
        try {
            JsonNode body = client().get().uri("/agent").retrieve().body(JsonNode.class);
            JsonNode values = listBody(body);
            if (values == null) throw new SessionFailure("OPENCODE_AGENT_INVALID_RESPONSE",
                    "OpenCode did not return an agent list");
            List<AgentInfo> result = new ArrayList<>();
            for (JsonNode value : values) {
                String name = firstText(value.path("name"), value.path("id"));
                if (name.isBlank()) continue;
                result.add(new AgentInfo(name, blankToNull(value.path("mode").asText("")),
                        blankToNull(firstText(value.path("description"), value.path("prompt")))));
            }
            return List.copyOf(result);
        } catch (SessionFailure failure) { throw failure; }
        catch (RuntimeException failure) {
            throw new SessionFailure("OPENCODE_AGENT_LIST_FAILED", failure.getMessage());
        }
    }

    @Override public StructuredOutputCapability structuredOutputCapability(OpenCodeModel model) {
        return capabilities.capability(connectionSupplier.get().baseUrl(), model);
    }

    @Override public OpenCodeSession forkSession(OpenCodeSession session, String messageId) {
        try {
            Map<String, Object> request = new LinkedHashMap<>();
            if (messageId != null && !messageId.isBlank()) request.put("messageID", messageId);
            JsonNode body = client().post().uri(uri -> sessionUri(uri, "/session/{id}/fork", session))
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
            client().post().uri(uri -> sessionUri(uri, "/session/{id}/revert", session))
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
            client().post().uri(uri -> sessionUri(uri, "/session/{id}/summarize", session))
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
            result.put(entry.getKey(), jsonValue(entry.getValue()));
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    private Object jsonValue(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) return null;
        if (value.isObject()) return object(value);
        if (value.isArray()) {
            List<Object> result = new ArrayList<>();
            for (JsonNode item : value) {
                result.add(jsonValue(item));
            }
            return java.util.Collections.unmodifiableList(result);
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
        JsonNode body = client().get().uri(uri -> sessionUri(uri, "/session/{id}/message", session))
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
    private boolean formatRejected(RestClientResponseException failure) {
        int status = failure.getStatusCode().value();
        if (status != 400 && status != 404 && status != 415 && status != 422) return false;
        String body = failure.getResponseBodyAsString();
        String detail = (failure.getMessage() + " " + (body == null ? "" : body)).toLowerCase(Locale.ROOT);
        return detail.contains("format") || detail.contains("json_schema") || detail.contains("schema");
    }
    private boolean structuredError(String type, String detail) {
        String value = ((type == null ? "" : type) + " " + (detail == null ? "" : detail))
                .toLowerCase(Locale.ROOT);
        return value.contains("structuredoutput") || value.contains("structured_output")
                || value.contains("json schema") || value.contains("json_schema");
    }
    private String todoStatus(String value) {
        return switch (value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('-', '_')) {
            case "pending", "open", "todo" -> "PENDING";
            case "in_progress", "inprogress", "doing" -> "IN_PROGRESS";
            case "completed", "complete", "done" -> "COMPLETED";
            case "cancelled", "canceled" -> "CANCELLED";
            default -> "UNKNOWN";
        };
    }
    private String todoPriority(String value) {
        return switch (value == null ? "" : value.trim().toLowerCase(Locale.ROOT)) {
            case "high" -> "HIGH";
            case "medium", "normal" -> "MEDIUM";
            case "low" -> "LOW";
            default -> null;
        };
    }
    private String normalizeTodoContent(String value) {
        return (value == null ? "" : value).replace("\r\n", "\n").replace('\r', '\n')
                .trim().replaceAll("\\s+", " ");
    }
    private String truncateUtf8(String value, int maxBytes) {
        if (value == null || value.isEmpty()) return "";
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) return value;
        int end = value.length();
        while (end > 0 && value.substring(0, end).getBytes(StandardCharsets.UTF_8).length > maxBytes) end--;
        return value.substring(0, end);
    }
    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
    @Override public String diff(OpenCodeSession session) {
        try {
            return client().get().uri(uri -> sessionUri(uri, "/session/{id}/diff", session))
                    .retrieve().body(String.class);
        } catch (RuntimeException e) { throw new SessionFailure("OPENCODE_DIFF_FAILED", e.getMessage()); }
    }
    @Override public void abort(OpenCodeSession session) {
        try { client().post().uri(uri -> sessionUri(uri, "/session/{id}/abort", session)).retrieve().toBodilessEntity(); }
        catch (RuntimeException e) { throw new SessionFailure("OPENCODE_ABORT_FAILED", e.getMessage()); }
    }
    private static URI sessionUri(org.springframework.web.util.UriBuilder uri, String path, OpenCodeSession session) {
        return directoryUri(uri, path, session.worktree(), Map.of("id", session.id()));
    }
    private static URI directoryUri(org.springframework.web.util.UriBuilder uri, String path, Path directory) {
        return directoryUri(uri, path, directory, Map.of());
    }
    private static URI directoryUri(org.springframework.web.util.UriBuilder uri, String path, Path directory,
                                    Map<String, ?> pathVariables) {
        Map<String, Object> variables = new LinkedHashMap<>(pathVariables);
        variables.put("directory", directory.toString());
        return uri.path(path).queryParam("directory", "{directory}").build(variables);
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
