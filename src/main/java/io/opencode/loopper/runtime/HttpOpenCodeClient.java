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
    private final RestClient.Builder baseBuilder;
    private final Supplier<ConnectionDetails> connectionSupplier;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final OpenCodeCapabilityRegistry capabilities;
    private final Map<String, OpenCodeModel> sessionModels = new ConcurrentHashMap<>();
    private final Map<String, SessionProfile> sessionProfiles = new ConcurrentHashMap<>();
    private final Map<String, Boolean> managedSessions = new ConcurrentHashMap<>();
    private final Map<String, Boolean> structuredPrompts = new ConcurrentHashMap<>();
    private final ObjectMapper json = new ObjectMapper();
    private final OpenCodeResponseParser responses = new OpenCodeResponseParser();
    private final OpenCodeMachineResponseInspector machineResponses =
            new OpenCodeMachineResponseInspector(json, responses);
    private final OpenCodeTodoParser todoParser = new OpenCodeTodoParser();
    public HttpOpenCodeClient(RestClient.Builder builder, LoopperProperties properties) {
        this(builder, () -> new ConnectionDetails(properties.getOpenCode().getBaseUrl(), properties.getOpenCode().getUsername(), properties.getOpenCode().getPassword(), false),
                new Timeouts(properties.getOpenCode().getConnectTimeout(), properties.getOpenCode().getRequestTimeout()),
                new OpenCodeCapabilityRegistry());
    }
    /** Runtime manager supplies an ephemeral connection without exposing its password in API DTOs. */
    public HttpOpenCodeClient(RestClient.Builder builder, URI baseUrl, String username, String password) {
        this(builder, () -> new ConnectionDetails(baseUrl, username, password, false),
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
            return new ConnectionDetails(connection.endpoint(), connection.username(), connection.password(), connection.managed());
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
            SessionProfile effectiveProfile = profile == null ? SessionProfile.IMPLEMENTATION : profile;
            Map<String, Object> request = new LinkedHashMap<>();
            if (title != null && !title.isBlank()) request.put("title", title);
            if (model != null && model.providerId() != null && !model.providerId().isBlank() && model.modelId() != null && !model.modelId().isBlank()) {
                request.put("model", Map.of("id", model.modelId(), "providerID", model.providerId()));
            }
            List<String> mcpServers = effectiveProfile == SessionProfile.ROUTER_NO_TOOLS
                    ? List.of() : mcpServers(canonical);
            List<Map<String, String>> permissions = OpenCodePermissionPolicy.rules(effectiveProfile, mcpServers);
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
            sessionProfiles.put(id, effectiveProfile);
            managedSessions.put(id, connectionSupplier.get().managed());
            return session;
        } catch (SessionFailure e) { throw e; }
        catch (Exception e) { throw new SessionFailure("OPENCODE_SESSION_CREATE_FAILED", e.getMessage()); }
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
            SessionProfile profile = sessionProfiles.get(session.id());
            if (prompt != null && prompt.agent() != null && !prompt.agent().isBlank()) {
                body.put("agent", prompt.agent());
            } else if (Boolean.TRUE.equals(managedSessions.get(session.id()))) {
                if (profile == SessionProfile.ROUTER_NO_TOOLS) body.put("agent", ROUTER_AGENT);
                else if (machineResponseProfile(profile)) body.put("agent", STRUCTURED_AGENT);
            }
            OpenCodeModel selectedModel = sessionModels.get(session.id());
            boolean managed = Boolean.TRUE.equals(managedSessions.get(session.id()));
            if (isDeepSeek(selectedModel) && (structured && Boolean.FALSE.equals(selectedModel.thinking())
                    || managed && profile == SessionProfile.ROUTER_NO_TOOLS)) {
                body.put("variant", STRUCTURED_NO_THINKING_VARIANT);
            }
            if (structured) {
                ResponseFormat.JsonSchema format = (ResponseFormat.JsonSchema) prompt.responseFormat();
                body.put("format", Map.of("type", "json_schema", "schema", format.schema(),
                        "retryCount", format.retryCount()));
            }
            client().post().uri(uri -> sessionUri(uri, "/session/{id}/prompt_async", session)).contentType(MediaType.APPLICATION_JSON)
                    .body(body).retrieve().toBodilessEntity();
            structuredPrompts.put(session.id(), structured);
        } catch (RestClientResponseException failure) {
            if (structured && formatRejected(failure)) {
                capabilities.transportUnsupported(connectionSupplier.get().baseUrl(), sessionModels.get(session.id()), failure.getMessage());
                throw new SessionFailure("OPENCODE_STRUCTURED_FORMAT_UNSUPPORTED", failure.getMessage());
            }
            throw new SessionFailure("OPENCODE_PROMPT_FAILED", failure.getMessage());
        } catch (RuntimeException e) { throw new SessionFailure("OPENCODE_PROMPT_FAILED", e.getMessage()); }
    }
    private static boolean isDeepSeek(OpenCodeModel model) {
        return model != null && model.providerId() != null && "deepseek".equalsIgnoreCase(model.providerId().trim());
    }
    private static boolean machineResponseProfile(SessionProfile profile) {
        return profile == SessionProfile.DECOMPOSER_READ_ONLY
                || profile == SessionProfile.ROUTER_NO_TOOLS
                || profile == SessionProfile.COMPILER_READ_ONLY
                || profile == SessionProfile.COMPILER_BINDING_NO_TOOLS
                || profile == SessionProfile.COMPILER_REPAIR_NO_TOOLS
                || profile == SessionProfile.REVIEWER_READ_ONLY
                || profile == SessionProfile.JUDGE_READ_ONLY
                || profile == SessionProfile.PROJECT_CONVENTION_READ_ONLY
                || profile == SessionProfile.MACHINE_FINALIZER_NO_TOOLS;
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
            inspectMachineResponseProgress(session);
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
            JsonNode latest = responses.latestAssistantAfterUser(messages);
            if (latest == null) throw new SessionFailure("OPENCODE_OUTPUT_MISSING", "OpenCode did not return an assistant turn");
            JsonNode info = latest.path("info");
            JsonNode error = info.path("error");
            String errorType = null;
            String errorDetail = null;
            if (!error.isMissingNode() && !error.isNull()) {
                errorType = responses.firstText(error.path("name"), error.path("code"), error.path("type"));
                errorDetail = responses.errorDetail(error);
            }
            JsonNode structured = info.path("structured");
            if ((structured.isMissingNode() || structured.isNull()) && latest.has("structured")) {
                structured = latest.path("structured");
            }
            Map<String, Object> structuredValue = structured.isObject() ? responses.object(structured) : Map.of();
            String text = responses.assistantText(latest);
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

    @Override public String sessionLiveOutput(OpenCodeSession session) {
        try {
            return responses.liveOutput(sessionMessages(session));
        } catch (SessionFailure e) { throw e; }
        catch (RuntimeException e) { throw new SessionFailure("OPENCODE_LIVE_OUTPUT_FAILED", e.getMessage()); }
    }

    @Override public SessionTranscript sessionTranscript(OpenCodeSession session) {
        try {
            return responses.transcript(sessionMessages(session));
        } catch (SessionFailure e) { throw e; }
        catch (RuntimeException e) { throw new SessionFailure("OPENCODE_TRANSCRIPT_FAILED", e.getMessage()); }
    }

    @Override public List<SessionMessageRef> sessionMessageRefs(OpenCodeSession session) {
        try {
            return responses.messageRefs(sessionMessages(session));
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
            List<PendingQuestion> result = responses.questions(body, session.id());
            if (result == null) {
                throw new SessionFailure("OPENCODE_QUESTION_INVALID_RESPONSE", "OpenCode did not return a pending question list");
            }
            return result;
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
            List<PendingPermission> result = responses.permissions(body, session.id());
            if (result == null) {
                throw new SessionFailure("OPENCODE_PERMISSION_INVALID_RESPONSE", "OpenCode did not return a pending permission list");
            }
            return result;
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
            SessionTodoSnapshot snapshot = todoParser.parse(body);
            if (snapshot == null) {
                throw new SessionFailure("OPENCODE_TODO_INVALID_RESPONSE", "OpenCode did not return a todo list");
            }
            return snapshot;
        } catch (SessionFailure e) { throw e; }
        catch (RuntimeException e) { throw new SessionFailure("OPENCODE_TODO_LIST_FAILED", e.getMessage()); }
    }

    @Override public ToolCapabilityProbe toolCapabilities(Path worktree) {
        try {
            Path canonical = worktree.toRealPath();
            JsonNode body = client().get().uri(uri -> directoryUri(uri, "/experimental/tool/ids", canonical))
                    .retrieve().body(JsonNode.class);
            JsonNode ids = responses.listBody(body);
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
            return new ToolCapabilityProbe(CapabilityState.UNKNOWN, List.of(), responses.bounded(failure.getMessage()));
        } catch (RuntimeException | java.io.IOException failure) {
            return new ToolCapabilityProbe(CapabilityState.UNKNOWN, List.of(), responses.bounded(failure.getMessage()));
        }
    }

    private List<String> mcpServers(Path worktree) {
        try {
            JsonNode body = client().get().uri(uri -> directoryUri(uri, "/mcp", worktree))
                    .retrieve().body(JsonNode.class);
            if (body == null || !body.isObject()) {
                throw new SessionFailure("OPENCODE_MCP_DISCOVERY_FAILED",
                        "OpenCode 未返回有效的 MCP Server 列表");
            }
            List<String> servers = new ArrayList<>();
            body.propertyStream().limit(64).forEach(entry -> {
                String name = entry.getKey();
                if (name != null && !name.isBlank() && name.length() <= 128) servers.add(name);
            });
            return List.copyOf(servers);
        } catch (SessionFailure failure) { throw failure; }
        catch (RuntimeException failure) {
            throw new SessionFailure("OPENCODE_MCP_DISCOVERY_FAILED",
                    "无法读取当前项目的 MCP Server：" + responses.bounded(failure.getMessage()));
        }
    }

    @Override public List<AgentInfo> agents() {
        try {
            JsonNode body = client().get().uri("/agent").retrieve().body(JsonNode.class);
            List<AgentInfo> result = responses.agents(body);
            if (result == null) throw new SessionFailure("OPENCODE_AGENT_INVALID_RESPONSE",
                    "OpenCode did not return an agent list");
            return result;
        } catch (SessionFailure failure) { throw failure; }
        catch (RuntimeException failure) {
            throw new SessionFailure("OPENCODE_AGENT_LIST_FAILED", failure.getMessage());
        }
    }

    @Override public StructuredOutputCapability structuredOutputCapability(OpenCodeModel model) {
        ConnectionDetails connection = connectionSupplier.get();
        try {
            JsonNode health = client().get().uri("/global/health").retrieve().body(JsonNode.class);
            capabilities.observeRuntime(connection.baseUrl(), health == null ? null : health.path("version").asText(null));
        } catch (RuntimeException ignored) {
            // Capability discovery is advisory. Existing runtime observations remain authoritative when health is transiently unreadable.
        }
        return capabilities.capability(connection.baseUrl(), model);
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
            return responses.usage(sessionMessages(session));
        } catch (SessionFailure e) { throw e; }
        catch (RuntimeException e) { throw new SessionFailure("OPENCODE_USAGE_LIST_FAILED", e.getMessage()); }
    }

    private JsonNode sessionMessages(OpenCodeSession session) {
        try {
            JsonNode body = client().get().uri(uri -> sessionUri(uri, "/session/{id}/message", session))
                    .retrieve().body(JsonNode.class);
            JsonNode messages = body != null && body.isArray() ? body : body == null ? null : body.path("data");
            if (messages == null || !messages.isArray()) throw new SessionFailure("OPENCODE_OUTPUT_MISSING", "OpenCode did not return a message list");
            return messages;
        } catch (RestClientResponseException failure) {
            if (Boolean.TRUE.equals(structuredPrompts.get(session.id())) && formatRejected(failure)) {
                capabilities.transportUnsupported(connectionSupplier.get().baseUrl(), sessionModels.get(session.id()),
                        failure.getResponseBodyAsString());
                throw new SessionFailure("OPENCODE_STRUCTURED_FORMAT_UNSUPPORTED", failure.getMessage());
            }
            throw new SessionFailure("OPENCODE_MESSAGES_FAILED", failure.getMessage());
        }
    }
    private void inspectMachineResponseProgress(OpenCodeSession session) {
        if (!machineResponseProfile(sessionProfiles.get(session.id()))) return;
        inspectMachineResponseProgress(session, sessionMessages(session));
    }
    private void inspectMachineResponseProgress(OpenCodeSession session, JsonNode messages) {
        if (!machineResponseProfile(sessionProfiles.get(session.id()))) return;
        boolean structuredPrompt = Boolean.TRUE.equals(structuredPrompts.get(session.id()));
        URI endpoint = connectionSupplier.get().baseUrl();
        OpenCodeModel model = sessionModels.get(session.id());
        machineResponses.inspect(messages, structuredPrompt,
                () -> capabilities.structured(endpoint, model),
                detail -> capabilities.modelUnsupported(endpoint, model, detail));
    }
    private SessionStatus messageStatus(OpenCodeSession session) {
        try {
            JsonNode messages = sessionMessages(session);
            inspectMachineResponseProgress(session, messages);
            return responses.messageStatus(messages);
        } catch (SessionFailure e) { throw e; }
        catch (RuntimeException e) { throw new SessionFailure("OPENCODE_MESSAGES_FAILED", e.getMessage()); }
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
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
    @Override public String diff(OpenCodeSession session) {
        try {
            return client().get().uri(uri -> sessionUri(uri, "/session/{id}/diff", session))
                    .retrieve().body(String.class);
        } catch (RuntimeException e) { throw new SessionFailure("OPENCODE_DIFF_FAILED", e.getMessage()); }
    }
    @Override public void abort(OpenCodeSession session) {
        try { client().post().uri(uri -> sessionUri(uri, "/session/{id}/abort", session)).retrieve().toBodilessEntity(); }
        catch (RestClientResponseException failure) {
            if (failure.getStatusCode().value() == 404) return;
            throw new SessionFailure("OPENCODE_ABORT_FAILED", failure.getMessage());
        } catch (RuntimeException e) { throw new SessionFailure("OPENCODE_ABORT_FAILED", e.getMessage()); }
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
    private record ConnectionDetails(URI baseUrl, String username, String password, boolean managed) { }
    private record Timeouts(Duration connectTimeout, Duration requestTimeout) { }
}
