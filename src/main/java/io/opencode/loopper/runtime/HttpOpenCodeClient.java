package io.opencode.loopper.runtime;
import static io.opencode.loopper.runtime.OpenCodeHttpTransport.directoryUri;
import static io.opencode.loopper.runtime.OpenCodeHttpTransport.sessionUri;
import tools.jackson.databind.JsonNode;
import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.SessionFailure;
import io.opencode.loopper.service.StoryAccountingCoordinator;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
/** Thin adapter for the local OpenCode server; all transport faults become SessionFailure. */
public class HttpOpenCodeClient implements OpenCodeClient {
    private final Supplier<OpenCodeConnectionDetails> connectionSupplier;
    private final OpenCodeHttpTransport http;
    private final OpenCodeCapabilityRegistry capabilities;
    private final Map<String, OpenCodeModel> sessionModels = new ConcurrentHashMap<>();
    private final Map<String, SessionProfile> sessionProfiles = new ConcurrentHashMap<>();
    private final Map<String, Boolean> managedSessions = new ConcurrentHashMap<>();
    private final Map<String, Boolean> structuredPrompts = new ConcurrentHashMap<>();
    private final OpenCodeSessionConnectionGuard sessionConnections;
    private final ObjectMapper json = new ObjectMapper();
    private final OpenCodeResponseParser responses = new OpenCodeResponseParser();
    private final OpenCodeMcpDiscovery mcpDiscovery = new OpenCodeMcpDiscovery(responses);
    private final OpenCodeMachineResponseInspector machineResponses =
            new OpenCodeMachineResponseInspector(json, responses);
    private final OpenCodeTodoParser todoParser = new OpenCodeTodoParser();
    private final OpenCodeExactRecoveryTransport exactRecovery;
    private final OpenCodeCommandTransport commandTransport;
    private OpenCodeAttachmentResources attachmentResources;
    private StoryAccountingCoordinator storyAccounting;
    public HttpOpenCodeClient(RestClient.Builder builder, LoopperProperties properties) {
        this(builder, () -> new OpenCodeConnectionDetails(properties.getOpenCode().getBaseUrl(), properties.getOpenCode().getUsername(), properties.getOpenCode().getPassword(), false, null, null),
                () -> OpenCodeHttpClientSemantics.externalIdentity(properties.getOpenCode().getBaseUrl()),
                new Timeouts(properties.getOpenCode().getConnectTimeout(), properties.getOpenCode().getRequestTimeout()),
                new OpenCodeCapabilityRegistry(), OpenCodeSessionRuntimeBindings.untracked(), false);
    }
    /** Runtime manager supplies an ephemeral connection without exposing its password in API DTOs. */
    public HttpOpenCodeClient(RestClient.Builder builder, URI baseUrl, String username, String password) {
        this(builder, () -> new OpenCodeConnectionDetails(baseUrl, username, password, false, null, null),
                () -> OpenCodeHttpClientSemantics.externalIdentity(baseUrl),
                new Timeouts(Duration.ofSeconds(5), Duration.ofSeconds(30)), new OpenCodeCapabilityRegistry(),
                OpenCodeSessionRuntimeBindings.untracked(), false);
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
                properties.getOpenCode().getRequestTimeout(), capabilities,
                OpenCodeSessionRuntimeBindings.untracked(), false);
    }
    public HttpOpenCodeClient(RestClient.Builder builder,
                              Supplier<OpenCodeRuntimeManager.Connection> connectionSupplier,
                              LoopperProperties properties, OpenCodeCapabilityRegistry capabilities,
                              OpenCodeSessionRuntimeBindings runtimeBindings) {
        this(builder, connectionSupplier, properties.getOpenCode().getConnectTimeout(),
                properties.getOpenCode().getRequestTimeout(), capabilities, runtimeBindings, true);
    }
    public HttpOpenCodeClient(RestClient.Builder builder,
                              Supplier<OpenCodeRuntimeManager.Connection> connectionSupplier,
                              Supplier<OpenCodeRuntimeManager.RuntimeIdentity> localIdentitySupplier,
                              LoopperProperties properties, OpenCodeCapabilityRegistry capabilities,
                              OpenCodeSessionRuntimeBindings runtimeBindings) {
        this(builder, connectionSupplier, localIdentitySupplier,
                properties.getOpenCode().getConnectTimeout(), properties.getOpenCode().getRequestTimeout(),
                capabilities, runtimeBindings, true);
    }
    private HttpOpenCodeClient(RestClient.Builder builder, Supplier<OpenCodeRuntimeManager.Connection> connectionSupplier,
                               Duration connectTimeout, Duration requestTimeout,
                               OpenCodeCapabilityRegistry capabilities) {
        this(builder, connectionSupplier, connectTimeout, requestTimeout, capabilities,
                OpenCodeSessionRuntimeBindings.untracked(), false);
    }
    public HttpOpenCodeClient(RestClient.Builder builder, Supplier<OpenCodeRuntimeManager.Connection> connectionSupplier,
            Supplier<OpenCodeRuntimeManager.RuntimeIdentity> localIdentitySupplier, LoopperProperties properties,
            OpenCodeCapabilityRegistry capabilities, OpenCodeSessionRuntimeBindings bindings, OpenCodeAttachmentResources resources) {
        this(builder, connectionSupplier, localIdentitySupplier, properties, capabilities, bindings);
        this.attachmentResources = resources;
    }
    public HttpOpenCodeClient(RestClient.Builder builder, Supplier<OpenCodeRuntimeManager.Connection> connectionSupplier,
            Supplier<OpenCodeRuntimeManager.RuntimeIdentity> localIdentitySupplier, LoopperProperties properties,
            OpenCodeCapabilityRegistry capabilities, OpenCodeSessionRuntimeBindings bindings,
            OpenCodeAttachmentResources resources, StoryAccountingCoordinator storyAccounting) {
        this(builder, connectionSupplier, localIdentitySupplier, properties, capabilities, bindings, resources);
        this.storyAccounting = storyAccounting;
        if (storyAccounting != null) {
            storyAccounting.installTransport(this::executeCommand);
            storyAccounting.installCancellation(this::cancelCommand);
        }
    }
    private HttpOpenCodeClient(RestClient.Builder builder, Supplier<OpenCodeRuntimeManager.Connection> connectionSupplier,
                               Duration connectTimeout, Duration requestTimeout,
                               OpenCodeCapabilityRegistry capabilities,
                               OpenCodeSessionRuntimeBindings runtimeBindings,
                               boolean requirePersistentBinding) {
        this(builder, connectionSupplier, OpenCodeHttpClientSemantics.unavailableLocalIdentity(), connectTimeout, requestTimeout,
                capabilities, runtimeBindings, requirePersistentBinding);
    }
    private HttpOpenCodeClient(RestClient.Builder builder,
                               Supplier<OpenCodeRuntimeManager.Connection> connectionSupplier,
                               Supplier<OpenCodeRuntimeManager.RuntimeIdentity> localIdentitySupplier,
                               Duration connectTimeout, Duration requestTimeout,
                               OpenCodeCapabilityRegistry capabilities,
                               OpenCodeSessionRuntimeBindings runtimeBindings,
                               boolean requirePersistentBinding) {
        this(builder, () -> {
            OpenCodeRuntimeManager.Connection connection = connectionSupplier.get();
            return new OpenCodeConnectionDetails(connection.endpoint(), connection.username(), connection.password(), connection.managed(),
                    connection.generation(), connection.internalMcpServer());
        }, localIdentitySupplier, new Timeouts(connectTimeout, requestTimeout), capabilities,
                runtimeBindings, requirePersistentBinding);
    }
    private HttpOpenCodeClient(RestClient.Builder builder, Supplier<OpenCodeConnectionDetails> connectionSupplier,
                               Supplier<OpenCodeRuntimeManager.RuntimeIdentity> localIdentitySupplier,
                               Timeouts timeouts, OpenCodeCapabilityRegistry capabilities,
                               OpenCodeSessionRuntimeBindings runtimeBindings,
                               boolean requirePersistentBinding) {
        this.connectionSupplier = connectionSupplier;
        this.http = new OpenCodeHttpTransport(builder, timeouts.connectTimeout(), timeouts.requestTimeout());
        this.capabilities = capabilities;
        this.sessionConnections = new OpenCodeSessionConnectionGuard(connectionSupplier, runtimeBindings,
                requirePersistentBinding);
        this.exactRecovery = new OpenCodeExactRecoveryTransport(connectionSupplier, localIdentitySupplier,
                http, mcpDiscovery, sessionConnections, runtimeBindings, requirePersistentBinding);
        this.commandTransport = new OpenCodeCommandTransport(this::client, this::client, session -> http.commandClient(connectionFor(session)));
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
            OpenCodeConnectionDetails connection = connectionSupplier.get();
            RestClient sessionClient = client(connection);
            Map<String, Object> request = new LinkedHashMap<>();
            if (title != null && !title.isBlank()) request.put("title", title);
            if (model != null && model.providerId() != null && !model.providerId().isBlank() && model.modelId() != null && !model.modelId().isBlank()) {
                request.put("model", Map.of("id", model.modelId(), "providerID", model.providerId()));
            }
            OpenCodeMcpDiscovery.Access mcp = effectiveProfile == SessionProfile.ROUTER_NO_TOOLS
                    ? OpenCodeMcpDiscovery.Access.empty()
                    : mcpDiscovery.discover(sessionClient, canonical, connection.internalMcpServer());
            if (OpenCodeHttpClientSemantics.candidateProfile(effectiveProfile)) {
                mcp.requireCandidateReady(connection.managed(), connection.generation(), connection.internalMcpServer());
            }
            List<Map<String, String>> permissions = OpenCodePermissionPolicy.rules(effectiveProfile,
                    mcp.connectedServers(), connection.internalMcpServer());
            request.put("permission", permissions);
            JsonNode body = sessionClient.post().uri(uri -> directoryUri(uri, "/session", canonical))
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
            OpenCodeSession session = sessionConnections.created(id, canonical, connection);
            if (model != null) sessionModels.put(id, model);
            sessionProfiles.put(id, effectiveProfile);
            managedSessions.put(id, connection.managed());
            return session;
        } catch (SessionFailure e) { throw e; }
        catch (Exception e) { throw new SessionFailure("OPENCODE_SESSION_CREATE_FAILED", e.getMessage()); }
    }
    @Override public SessionCreationPlan prepareSessionCreation(Path worktree, String baseTitle,
            OpenCodeModel model, SessionProfile profile, String creationCredential) {
        return exactRecovery.prepare(worktree, baseTitle, model, profile, creationCredential);
    }
    @Override public SessionCreationPlan prepareCandidateSessionCreationLocally(
            Path worktree, String baseTitle, OpenCodeModel model,
            SessionProfile profile, String creationCredential) {
        return exactRecovery.prepareCandidateLocally(
                worktree, baseTitle, model, profile, creationCredential);
    }
    @Override public void requireCandidateSessionReady(SessionCreationPlan persistedPlan) {
        exactRecovery.requireCandidateReady(persistedPlan);
    }
    @Override public SessionAttestation createSession(SessionCreationPlan plan) {
        SessionAttestation created = exactRecovery.create(plan);
        cachePlan(created.remoteId(), created.plan());
        return created;
    }
    @Override public SessionLookup findSessionsByExactTitle(SessionCreationPlan plan) {
        SessionLookup lookup = exactRecovery.findSessions(plan);
        lookup.matches().forEach(match -> cachePlan(match.remoteId(), match.plan()));
        return lookup;
    }
    @Override public void promptAsync(OpenCodeSession session, String prompt) {
        promptAsync(session, PromptRequest.text(prompt));
    }
    @Override public void promptAsync(OpenCodeSession session, PromptRequest prompt) {
        boolean structured = prompt != null && prompt.responseFormat() instanceof ResponseFormat.JsonSchema;
        try {
            if (storyAccounting != null) storyAccounting.beforeBusinessPrompt(session, request -> executeCommand(session, request));
            SessionProfile profile = sessionProfiles.get(session.id());
            boolean attached = prompt != null && !prompt.files().isEmpty();
            List<Map<String, Object>> files = List.of();
            if (attached) {
                OpenCodeConnectionDetails connection = connectionFor(session);
                if (attachmentResources == null || !connection.managed() || prompt.messageId() == null || profile == SessionProfile.ROUTER_NO_TOOLS) {
                    throw new SessionFailure("ATTACHMENT_MCP_REQUIRED", "ATTACHMENT_MCP_REQUIRED: Attachments require a managed MCP runtime and a non-Router message identity");
                }
                files = attachmentResources.prepare(session.id(), connection.generation(), connection.internalMcpServer(), prompt.files());
            }
            Map<String, Object> body = OpenCodePromptBody.encode(prompt, profile,
                    Boolean.TRUE.equals(managedSessions.get(session.id())), sessionModels.get(session.id()), files);
            if (storyAccounting != null && !storyAccounting.accountingMessageIds(session.id()).isEmpty()) OpenCodePromptBody.restoreBusinessContext(body, sessionModels.get(session.id()));
            client(session).post().uri(uri -> sessionUri(uri, "/session/{id}/prompt_async", session)).contentType(MediaType.APPLICATION_JSON)
                    .body(body).retrieve().toBodilessEntity();
            if (attached) attachmentResources.verifyDelivery(session.id(), () -> exactRecovery.findPrompt(
                    session, prompt, OpenCodeClient.promptRequestSha256(prompt)).exists());
            structuredPrompts.put(session.id(), structured);
        } catch (RestClientResponseException failure) {
            if (structured && OpenCodeHttpClientSemantics.formatRejected(failure)) {
                capabilities.transportUnsupported(connectionFor(session).baseUrl(), sessionModels.get(session.id()), failure.getMessage());
                throw new SessionFailure("OPENCODE_STRUCTURED_FORMAT_UNSUPPORTED", failure.getMessage());
            }
            throw new SessionFailure("OPENCODE_PROMPT_FAILED", failure.getMessage());
        } catch (SessionFailure failure) { throw failure; }
        catch (RuntimeException e) { throw new SessionFailure("OPENCODE_PROMPT_FAILED", e.getMessage()); }
    }
    @Override public MessageLookup findPromptMessage(OpenCodeSession session, PromptRequest expectedRequest,
            String persistedRequestSha256) {
        return exactRecovery.findPrompt(session, expectedRequest, persistedRequestSha256);
    }
    @Override public SessionStatus sessionStatus(OpenCodeSession session) {
        if (storyAccounting != null && storyAccounting.awaitingBusinessStart(session.id())) return new SessionStatus("RUNNING");
        try {
            JsonNode body = client(session).get().uri(uri -> directoryUri(uri, "/session/status", session.worktree()))
                    .retrieve().body(JsonNode.class);
            JsonNode entry = body == null ? null : body.get(session.id());
            if (entry == null || entry.isNull()) {
                return observedStatus(session, messageStatus(session));
            }
            String state = entry.isTextual() ? entry.asText() : entry.path("status").asText(null);
            if (state == null || state.isBlank()) state = entry.isTextual() ? entry.asText() : entry.path("type").asText(null);
            if (state == null || state.isBlank()) return observedStatus(session, new SessionStatus("UNKNOWN"));
            String detail = entry.isTextual() ? null : entry.path("message").asText(null);
            if ((detail == null || detail.isBlank()) && !entry.isTextual()) {
                detail = entry.path("action").path("message").asText(null);
            }
            inspectMachineResponseProgress(session);
            return observedStatus(session, accountingStatus(session, new SessionStatus(state, detail)));
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
            URI endpoint = connectionFor(session).baseUrl();
            OpenCodeModel model = sessionModels.get(session.id());
            if (!structuredValue.isEmpty()) capabilities.structured(endpoint, model);
            if (OpenCodeHttpClientSemantics.structuredError(errorType, errorDetail)) capabilities.modelUnsupported(endpoint, model, errorDetail);
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
        if (storyAccounting != null && storyAccounting.awaitingBusinessStart(session.id())) return List.of();
        try {
            JsonNode body = client(session).get().uri(uri -> directoryUri(uri, "/question", session.worktree()))
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
            client(session).post().uri(uri -> directoryUri(uri, "/question/{requestId}/reply", session.worktree(),
                            Map.of("requestId", requestId)))
                    .contentType(MediaType.APPLICATION_JSON).body(Map.of("answers", answers)).retrieve().toBodilessEntity();
        } catch (RuntimeException e) { throw new SessionFailure("OPENCODE_QUESTION_REPLY_FAILED", e.getMessage()); }
    }
    @Override public void rejectQuestion(OpenCodeSession session, String requestId) {
        try {
            client(session).post().uri(uri -> directoryUri(uri, "/question/{requestId}/reject", session.worktree(),
                            Map.of("requestId", requestId)))
                    .retrieve().toBodilessEntity();
        } catch (RuntimeException e) { throw new SessionFailure("OPENCODE_QUESTION_REJECT_FAILED", e.getMessage()); }
    }
    @Override public List<PendingPermission> pendingPermissions(OpenCodeSession session) {
        try {
            JsonNode body = client(session).get().uri(uri -> directoryUri(uri, "/permission", session.worktree()))
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
            client(session).post().uri(uri -> directoryUri(uri, "/permission/{requestId}/reply", session.worktree(),
                            Map.of("requestId", requestId)))
                    .contentType(MediaType.APPLICATION_JSON).body(request).retrieve().toBodilessEntity();
        } catch (SessionFailure e) { throw e; }
        catch (RuntimeException e) { throw new SessionFailure("OPENCODE_PERMISSION_REPLY_FAILED", e.getMessage()); }
    }
    @Override public SessionTodoSnapshot sessionTodoSnapshot(OpenCodeSession session) {
        try {
            JsonNode body = client(session).get().uri(uri -> sessionUri(uri, "/session/{id}/todo", session))
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
        return commandTransport.tools(worktree);
    }
    @Override public CommandCapabilityProbe commandCapabilities(Path worktree) {
        return commandTransport.capabilities(worktree);
    }
    @Override public CommandResult executeCommand(OpenCodeSession session, CommandRequest request) {
        return commandTransport.execute(session, request);
    }
    @Override public SessionTranscript commandTranscript(OpenCodeSession session, String messageId) {
        return commandTransport.transcript(session, messageId);
    }
    @Override public boolean cancelCommand(OpenCodeSession session, String messageId) {
        return commandTransport.cancel(session, messageId);
    }
    @Override public List<AgentInfo> agents() { return commandTransport.agents(); }
    @Override public StructuredOutputCapability structuredOutputCapability(OpenCodeModel model) {
        OpenCodeConnectionDetails connection = connectionSupplier.get();
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
            OpenCodeConnectionDetails connection = connectionFor(session);
            JsonNode body = client(connection).post().uri(uri -> sessionUri(uri, "/session/{id}/fork", session))
                    .contentType(MediaType.APPLICATION_JSON).body(request).retrieve().body(JsonNode.class);
            String id = body == null ? null : body.path("id").asText(null);
            if (id == null && body != null) id = body.path("session").path("id").asText(null);
            if (id == null || id.isBlank()) throw new SessionFailure("OPENCODE_FORK_INVALID_RESPONSE", "OpenCode did not return a forked session id");
            OpenCodeSession fork = sessionConnections.created(id, session.worktree(), connection);
            OpenCodeModel sessionModel = sessionModels.get(session.id());
            if (sessionModel != null) sessionModels.put(id, sessionModel);
            SessionProfile profile = sessionProfiles.get(session.id());
            if (profile != null) sessionProfiles.put(id, profile);
            managedSessions.put(id, connection.managed());
            return fork;
        } catch (SessionFailure e) { throw e; }
        catch (RuntimeException e) { throw new SessionFailure("OPENCODE_FORK_FAILED", e.getMessage()); }
    }
    @Override public void revertSession(OpenCodeSession session, String messageId, String partId) {
        if (messageId == null || messageId.isBlank()) throw new SessionFailure("OPENCODE_REVERT_MESSAGE_REQUIRED", "Revert requires a message id");
        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("messageID", messageId);
            if (partId != null && !partId.isBlank()) request.put("partID", partId);
            client(session).post().uri(uri -> sessionUri(uri, "/session/{id}/revert", session))
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
            client(session).post().uri(uri -> sessionUri(uri, "/session/{id}/summarize", session))
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
            JsonNode body = client(session).get().uri(uri -> sessionUri(uri, "/session/{id}/message", session))
                    .retrieve().body(JsonNode.class);
            JsonNode messages = body != null && body.isArray() ? body : body == null ? null : body.path("data");
            if (messages == null || !messages.isArray()) throw new SessionFailure("OPENCODE_OUTPUT_MISSING", "OpenCode did not return a message list");
            return OpenCodeAccountingMessageFilter.filter(messages, storyAccounting == null
                    ? java.util.Set.of() : storyAccounting.accountingMessageIds(session.id()));
        } catch (RestClientResponseException failure) {
            if (Boolean.TRUE.equals(structuredPrompts.get(session.id()))
                    && OpenCodeHttpClientSemantics.formatRejected(failure)) {
                capabilities.transportUnsupported(connectionFor(session).baseUrl(), sessionModels.get(session.id()),
                        failure.getResponseBodyAsString());
                throw new SessionFailure("OPENCODE_STRUCTURED_FORMAT_UNSUPPORTED", failure.getMessage());
            }
            throw new SessionFailure("OPENCODE_MESSAGES_FAILED", failure.getMessage());
        }
    }
    private void inspectMachineResponseProgress(OpenCodeSession session) {
        if (!OpenCodeHttpClientSemantics.machineResponseProfile(sessionProfiles.get(session.id()))) return;
        inspectMachineResponseProgress(session, sessionMessages(session));
    }
    private void inspectMachineResponseProgress(OpenCodeSession session, JsonNode messages) {
        if (!OpenCodeHttpClientSemantics.machineResponseProfile(sessionProfiles.get(session.id()))) return;
        boolean structuredPrompt = Boolean.TRUE.equals(structuredPrompts.get(session.id()));
        URI endpoint = connectionFor(session).baseUrl();
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
    private SessionStatus observedStatus(OpenCodeSession session, SessionStatus status) {
        if (storyAccounting != null && (status.completed() || status.failed())) {
            storyAccounting.afterTerminalStatus(session, request -> executeCommand(session, request));
        }
        return status;
    }
    private SessionStatus accountingStatus(OpenCodeSession session, SessionStatus status) {
        var ids = storyAccounting == null ? java.util.Set.<String>of() : storyAccounting.accountingMessageIds(session.id());
        return commandTransport.accountingStatus(session, status, ids);
    }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
    @Override public String diff(OpenCodeSession session) {
        try {
            return client(session).get().uri(uri -> sessionUri(uri, "/session/{id}/diff", session))
                    .retrieve().body(String.class);
        } catch (RuntimeException e) { throw new SessionFailure("OPENCODE_DIFF_FAILED", e.getMessage()); }
    }
    @Override public void abort(OpenCodeSession session) { abortWithConfirmation(session); }
    @Override public AbortConfirmation abortWithConfirmation(OpenCodeSession session) {
        try {
            if (storyAccounting != null) {
                storyAccounting.beforeAbort(session);
            }
            Boolean acknowledged = client(session).post().uri(uri -> sessionUri(uri, "/session/{id}/abort", session))
                    .retrieve().body(Boolean.class);
            if (!Boolean.TRUE.equals(acknowledged)) {
                throw new SessionFailure("OPENCODE_ABORT_UNCONFIRMED",
                        "OpenCode abort endpoint did not acknowledge termination");
            }
            if (attachmentResources != null) attachmentResources.revoke(session.id());
            return AbortConfirmation.ACKNOWLEDGED;
        }
        catch (RestClientResponseException failure) {
            if (failure.getStatusCode().value() == 404) {
                if (attachmentResources != null) attachmentResources.revoke(session.id());
                return AbortConfirmation.ALREADY_ABSENT;
            }
            throw new SessionFailure("OPENCODE_ABORT_FAILED", failure.getMessage());
        } catch (SessionFailure failure) {
            throw failure;
        } catch (RuntimeException e) {
            throw new SessionFailure("OPENCODE_ABORT_FAILED", e.getMessage());
        }
    }
    private void cachePlan(String id, SessionCreationPlan plan) {
        if (plan.model() != null) sessionModels.put(id, plan.model());
        sessionProfiles.put(id, plan.profile());
        managedSessions.put(id, plan.managed());
    }
    private RestClient client() {
        return client(connectionSupplier.get());
    }
    private RestClient client(OpenCodeSession session) {
        return client(connectionFor(session));
    }
    private OpenCodeConnectionDetails connectionFor(OpenCodeSession session) {
        return sessionConnections.resolve(session);
    }
    private RestClient client(OpenCodeConnectionDetails connection) {
        return http.client(connection);
    }
    private record Timeouts(Duration connectTimeout, Duration requestTimeout) { }
}
