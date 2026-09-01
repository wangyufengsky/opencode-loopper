package io.opencode.loopper.runtime;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.SessionFailure;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpOpenCodeClientTest {
    private HttpServer server;
    private final AtomicReference<String> statusBody = new AtomicReference<>("{}");
    private final AtomicReference<String> healthBody = new AtomicReference<>("{\"healthy\":true,\"version\":\"1.18.19\"}");
    private final AtomicReference<String> messageBody = new AtomicReference<>("[{\"info\":{\"role\":\"user\"}}]");
    private final AtomicInteger messageStatusCode = new AtomicInteger(200);
    private final AtomicReference<String> lastPathAndQuery = new AtomicReference<>();
    private final AtomicReference<String> createBody = new AtomicReference<>();
    private final AtomicReference<String> questionBody = new AtomicReference<>("[]");
    private final AtomicReference<String> questionActionBody = new AtomicReference<>();
    private final AtomicReference<String> permissionBody = new AtomicReference<>("[]");
    private final AtomicReference<String> permissionActionBody = new AtomicReference<>();
    private final AtomicReference<String> todoBody = new AtomicReference<>("[]");
    private final AtomicReference<String> sessionActionBody = new AtomicReference<>();
    private final AtomicReference<String> mcpBody = new AtomicReference<>("{}");
    private final AtomicInteger mcpRequests = new AtomicInteger();
    private final AtomicReference<String> promptBody = new AtomicReference<>();
    private final AtomicInteger promptRequests = new AtomicInteger();
    private final AtomicReference<String> createResponseDirectory = new AtomicReference<>();
    private final AtomicReference<String> sessionListBody = new AtomicReference<>("[]");
    private final AtomicInteger sessionListStatusCode = new AtomicInteger(200);
    private final AtomicInteger sessionListRequests = new AtomicInteger();
    private final AtomicReference<String> exactMessageBody = new AtomicReference<>("{}");
    private final AtomicInteger exactMessageStatusCode = new AtomicInteger(200);
    private final AtomicReference<String> abortBody = new AtomicReference<>("true");
    private final AtomicInteger abortStatusCode = new AtomicInteger(200);
    private final AtomicLong responseDelayMillis = new AtomicLong();
    private final AtomicInteger httpRequests = new AtomicInteger();
    @TempDir Path worktree;

    @BeforeEach
    void server() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        context("/session", this::session);
        context("/session/status", exchange -> reply(exchange, statusBody.get()));
        context("/global/health", exchange -> reply(exchange, healthBody.get()));
        context("/question", this::question);
        context("/permission", this::permission);
        context("/experimental/tool/ids", exchange -> reply(exchange, "[\"read\",\"todowrite\"]"));
        context("/mcp", exchange -> { mcpRequests.incrementAndGet(); reply(exchange, mcpBody.get()); });
        context("/agent", exchange -> reply(exchange,
                "[{\"name\":\"build\",\"mode\":\"primary\"},{\"name\":\"plan\",\"mode\":\"primary\"}]"));
        server.start();
    }

    private void context(String path, com.sun.net.httpserver.HttpHandler handler) {
        com.sun.net.httpserver.HttpContext context = server.createContext(path, handler);
        context.getFilters().add(new com.sun.net.httpserver.Filter() {
            @Override public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
                httpRequests.incrementAndGet();
                chain.doFilter(exchange);
            }
            @Override public String description() { return "count every HTTP request"; }
        });
    }
    @AfterEach void stop() { server.stop(0); }

    @Test
    void usesDirectoryForSessionTransportAndCompletesOnlyAfterObservedBusy() throws Exception {
        LoopperProperties properties = new LoopperProperties();
        properties.getOpenCode().setBaseUrl(new java.net.URI("http://127.0.0.1:" + server.getAddress().getPort()));
        HttpOpenCodeClient client = new HttpOpenCodeClient(RestClient.builder(), properties);
        OpenCodeClient.OpenCodeSession session = client.createSession(worktree, "fixture", new OpenCodeClient.OpenCodeModel("opencode", "deepseek-v4-flash-free", false));
        assertThat(lastPathAndQuery.get()).contains("/session").contains("directory=");
        assertThat(createBody.get()).contains("providerID").contains("opencode").contains("deepseek-v4-flash-free");
        assertThat(createBody.get()).contains("\"permission\"").contains("external_directory").contains("*git*commit*")
                .contains("*git*update-ref*").contains("*git*push*")
                .contains("git reset --hard*").contains("rm -rf*").contains("\"action\":\"deny\"");
        OpenCodeClient.OpenCodeSession judge = client.createReadOnlySession(worktree, "Requirement Judge", new OpenCodeClient.OpenCodeModel("opencode", "deepseek-v4-flash-free", false));
        assertThat(createBody.get()).contains("\"permission\":\"read\"")
                .contains("\"permission\":\"glob\"").contains("\"permission\":\"grep\"")
                .contains("\"action\":\"allow\"")
                .contains("\"permission\":\"*\"").contains("\"action\":\"deny\"")
                .contains("\"pattern\":\".env\"").contains("\"pattern\":\".env.example\"");
        client.promptAsync(session, "hello");
        assertThat(lastPathAndQuery.get()).contains("/session/s1/prompt_async").contains("directory=");
        assertThat(promptBody.get()).doesNotContain("\"variant\"");
        assertThat(client.sessionStatus(session).state()).isEqualTo("RUNNING");
        statusBody.set("{\"s1\":{\"status\":\"busy\"}}");
        assertThat(client.sessionStatus(session).state()).isEqualTo("busy");
        statusBody.set("{}"); messageBody.set("[{\"info\":{\"role\":\"assistant\",\"time\":{\"completed\":123}}},{\"info\":{\"role\":\"assistant\"}}]");
        assertThat(client.sessionStatus(session).state()).isEqualTo("RUNNING");
        messageBody.set("[{\"info\":{\"role\":\"assistant\",\"time\":{\"completed\":123},\"finish\":\"stop\"},\"parts\":[{\"type\":\"text\",\"text\":\"{\\\"verdict\\\":\\\"PASS\\\"}\"}]}]");
        assertThat(client.sessionStatus(session).completed()).isTrue();
        assertThat(client.sessionOutput(judge)).contains("PASS");
        statusBody.set("{\"s1\":{\"status\":\"error\"}}");
        assertThat(client.sessionStatus(session).failed()).isTrue();
        statusBody.set("{\"s1\":{\"type\":\"retry\",\"message\":\"Free usage exceeded\",\"action\":{\"reason\":\"free_tier_limit\"}}}");
        OpenCodeClient.SessionStatus retry = client.sessionStatus(session);
        assertThat(retry.failed()).isFalse();
        assertThat(retry.retrying()).isTrue();
        assertThat(retry.detail()).isEqualTo("Free usage exceeded");
        assertThat(client.diff(session)).isEqualTo("[]");
        assertThat(lastPathAndQuery.get()).contains("/session/s1/diff").contains("directory=");
        assertThat(client.abortWithConfirmation(session)).isEqualTo(OpenCodeClient.AbortConfirmation.ACKNOWLEDGED);
        assertThat(lastPathAndQuery.get()).contains("/session/s1/abort").contains("directory=");
    }

    @Test
    void requiresPositiveAbortAcknowledgementAndTreatsMissingSessionAsStopped() throws Exception {
        LoopperProperties properties = new LoopperProperties();
        properties.getOpenCode().setBaseUrl(new java.net.URI("http://127.0.0.1:" + server.getAddress().getPort()));
        HttpOpenCodeClient client = new HttpOpenCodeClient(RestClient.builder(), properties);
        OpenCodeClient.OpenCodeSession session = new OpenCodeClient.OpenCodeSession("s1", worktree);
        abortBody.set("false");

        assertThatThrownBy(() -> client.abortWithConfirmation(session))
                .isInstanceOfSatisfying(SessionFailure.class,
                        failure -> assertThat(failure.code()).isEqualTo("OPENCODE_ABORT_UNCONFIRMED"));

        abortStatusCode.set(404);
        assertThat(client.abortWithConfirmation(session))
                .isEqualTo(OpenCodeClient.AbortConfirmation.ALREADY_ABSENT);
    }

    @Test
    void routerHasNoToolsReviewerIsReadOnlyAndImplementationDeniesMaintenanceEscapes() throws Exception {
        LoopperProperties properties = new LoopperProperties();
        properties.getOpenCode().setBaseUrl(new java.net.URI("http://127.0.0.1:" + server.getAddress().getPort()));
        HttpOpenCodeClient client = new HttpOpenCodeClient(RestClient.builder(), properties);

        mcpBody.set("{\"gitlab internal\":{\"status\":\"connected\"}}");
        client.createSession(worktree, "router", null, OpenCodeClient.SessionProfile.ROUTER_NO_TOOLS);
        assertThat(createBody.get()).contains("\"permission\":\"*\"").contains("\"action\":\"deny\"")
                .doesNotContain("\"permission\":\"gitlab_internal_*\"")
                .doesNotContain("\"permission\":\"glob\"")
                .doesNotContain("\"permission\":\"grep\"")
                .doesNotContain("\"permission\":\"question\"");

        client.createSession(worktree, "compiler repair", null,
                OpenCodeClient.SessionProfile.COMPILER_REPAIR_NO_TOOLS);
        assertThat(createBody.get()).contains("\"permission\":\"*\"").contains("\"action\":\"deny\"")
                .contains("\"permission\":\"gitlab_internal_*\"")
                .doesNotContain("\"permission\":\"read\",\"action\":\"allow\",\"pattern\":\"*\"")
                .doesNotContain("\"permission\":\"glob\"")
                .doesNotContain("\"permission\":\"grep\"")
                .doesNotContain("\"permission\":\"question\"");

        client.createSession(worktree, "reviewer", null, OpenCodeClient.SessionProfile.REVIEWER_READ_ONLY);
        assertThat(createBody.get()).contains("\"permission\":\"read\"")
                .contains("\"permission\":\"glob\"")
                .contains("\"permission\":\"grep\"")
                .doesNotContain("\"permission\":\"question\"");

        client.createSession(worktree, "implementation", null, OpenCodeClient.SessionProfile.IMPLEMENTATION);
        assertThat(createBody.get()).contains("*systemctl*")
                .contains("*launchctl*")
                .contains("*brew*services*")
                .contains("rm *", "unlink *", "rmdir *");
    }

    @Test
    void candidateSessionRequiresExactConnectedInternalServerAndExposesNoUserMcp() throws Exception {
        String internal = "loopper_internal_generation1";
        AtomicReference<OpenCodeRuntimeManager.Connection> connection = new AtomicReference<>(
                new OpenCodeRuntimeManager.Connection(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "", "", true,
                        "generation-1", internal));
        HttpOpenCodeClient client = new HttpOpenCodeClient(RestClient.builder(), connection::get);
        mcpBody.set("{\"" + internal + "\":{\"status\":\"connected\"},"
                + "\"user_probe\":{\"status\":\"connected\"}}");

        OpenCodeClient.OpenCodeSession session = client.createSession(worktree, "candidate", null,
                OpenCodeClient.SessionProfile.DECOMPOSER_CANDIDATE_READ_ONLY);

        assertThat(session.generation()).isEqualTo("generation-1");
        assertThat(session.internalMcpServer()).isEqualTo(internal);
        assertThat(createBody.get()).contains("\"permission\":\"" + internal + "_submit_candidate\"")
                .doesNotContain("user_probe_*");

        connection.set(new OpenCodeRuntimeManager.Connection(
                new URI("http://127.0.0.1:" + server.getAddress().getPort()), "", "", true,
                "generation-2", "loopper_internal_generation2"));
        lastPathAndQuery.set(null);
        assertThatThrownBy(() -> client.sessionStatus(session))
                .isInstanceOfSatisfying(SessionFailure.class,
                        failure -> assertThat(failure.code()).isEqualTo("OPENCODE_SESSION_GENERATION_MISMATCH"));
        assertThat(lastPathAndQuery.get()).isNull();
        connection.set(new OpenCodeRuntimeManager.Connection(
                new URI("http://127.0.0.1:" + server.getAddress().getPort()), "", "", true,
                "generation-1", internal));

        client.createSession(worktree, "judge", null, OpenCodeClient.SessionProfile.JUDGE_READ_ONLY);
        assertThat(createBody.get()).contains("\"permission\":\"user_probe_*\"")
                .doesNotContain(internal + "_*")
                .doesNotContain(internal + "_submit_candidate");

        mcpBody.set("{\"" + internal + "\":{\"status\":\"failed\"}}");
        createBody.set(null);
        assertThatThrownBy(() -> client.createSession(worktree, "candidate", null,
                OpenCodeClient.SessionProfile.DECOMPOSER_CANDIDATE_READ_ONLY))
                .isInstanceOfSatisfying(SessionFailure.class,
                        failure -> assertThat(failure.code()).isEqualTo("OPENCODE_INTERNAL_MCP_NOT_READY"));
        assertThat(createBody.get()).isNull();
    }

    @Test
    void recoveredSessionCannotConsumeANewRuntime404AfterManagedGenerationRotation() throws Exception {
        String firstServer = "loopper_internal_first";
        AtomicReference<OpenCodeRuntimeManager.Connection> connection = new AtomicReference<>(
                new OpenCodeRuntimeManager.Connection(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "", "", true,
                        "generation-first", firstServer));
        InMemoryRuntimeBindings bindings = new InMemoryRuntimeBindings();
        LoopperProperties properties = new LoopperProperties();
        mcpBody.set("{\"" + firstServer + "\":{\"status\":\"connected\"}}");
        HttpOpenCodeClient firstJvm = new HttpOpenCodeClient(RestClient.builder(), connection::get,
                properties, new OpenCodeCapabilityRegistry(), bindings);
        OpenCodeClient.OpenCodeSession created = firstJvm.createSession(worktree, "writer", null);

        connection.set(new OpenCodeRuntimeManager.Connection(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "", "", true,
                "generation-second", "loopper_internal_second"));
        HttpOpenCodeClient restartedJvm = new HttpOpenCodeClient(RestClient.builder(), connection::get,
                properties, new OpenCodeCapabilityRegistry(), bindings);
        OpenCodeClient.OpenCodeSession recovered = new OpenCodeClient.OpenCodeSession(created.id(), worktree);
        abortStatusCode.set(404);
        lastPathAndQuery.set(null);

        assertThatThrownBy(() -> restartedJvm.sessionStatus(recovered))
                .isInstanceOfSatisfying(SessionFailure.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("OPENCODE_SESSION_GENERATION_MISMATCH"));
        assertThat(lastPathAndQuery.get()).isNull();
        assertThatThrownBy(() -> restartedJvm.abortWithConfirmation(recovered))
                .isInstanceOfSatisfying(SessionFailure.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("OPENCODE_SESSION_GENERATION_MISMATCH"));
        assertThat(lastPathAndQuery.get()).isNull();
    }

    @Test
    void recoveredLegacyOrUnboundSessionFailsBeforeAnyNetworkRequest() {
        AtomicReference<OpenCodeRuntimeManager.Connection> connection = new AtomicReference<>(
                new OpenCodeRuntimeManager.Connection(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "", "", true,
                        "generation-current", "loopper_internal_current"));
        InMemoryRuntimeBindings bindings = new InMemoryRuntimeBindings();
        bindings.register(new OpenCodeSessionRuntimeBindings.Binding("legacy", "legacy-unbound-legacy",
                OpenCodeSessionRuntimeBindings.OwnershipMode.LEGACY_UNKNOWN, "0".repeat(64), null));
        HttpOpenCodeClient restartedJvm = new HttpOpenCodeClient(RestClient.builder(), connection::get,
                new LoopperProperties(), new OpenCodeCapabilityRegistry(), bindings);
        lastPathAndQuery.set(null);

        assertThatThrownBy(() -> restartedJvm.abortWithConfirmation(
                new OpenCodeClient.OpenCodeSession("legacy", worktree)))
                .isInstanceOfSatisfying(SessionFailure.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("OPENCODE_SESSION_RUNTIME_BINDING_UNKNOWN"));
        assertThatThrownBy(() -> restartedJvm.abortWithConfirmation(
                new OpenCodeClient.OpenCodeSession("missing", worktree)))
                .isInstanceOfSatisfying(SessionFailure.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("OPENCODE_SESSION_RUNTIME_BINDING_MISSING"));
        assertThat(lastPathAndQuery.get()).isNull();
    }

    @Test
    void candidateReadinessFindsTheExactPrivateServerAfterTheBoundedUserMcpWindow() throws Exception {
        String internal = "loopper_internal_after_user_window";
        HttpOpenCodeClient client = new HttpOpenCodeClient(RestClient.builder(),
                () -> new OpenCodeRuntimeManager.Connection(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "", "", true,
                        "generation-after-window", internal));
        StringBuilder statuses = new StringBuilder("{");
        for (int index = 0; index < 70; index++) {
            if (index > 0) statuses.append(',');
            statuses.append('"').append("user_").append(index).append("\":{\"status\":\"connected\"}");
        }
        statuses.append(',').append('"').append(internal)
                .append("\":{\"status\":\"connected\"}}");
        mcpBody.set(statuses.toString());

        OpenCodeClient.OpenCodeSession session = client.createSession(worktree, "candidate", null,
                OpenCodeClient.SessionProfile.DECOMPOSER_CANDIDATE_READ_ONLY);

        assertThat(session.generation()).isEqualTo("generation-after-window");
        assertThat(createBody.get()).contains("\"permission\":\"" + internal + "_submit_candidate\"")
                .doesNotContain("user_0_*");
    }

    @Test
    void acceptanceCandidateHasOnlyTheExactInternalSubmissionToolAndRequiresReadiness() throws Exception {
        String internal = "loopper_internal_acceptance";
        HttpOpenCodeClient client = new HttpOpenCodeClient(RestClient.builder(),
                () -> new OpenCodeRuntimeManager.Connection(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "", "", true,
                        "generation-acceptance", internal));
        mcpBody.set("{\"" + internal + "\":{\"status\":\"connected\"},"
                + "\"user_probe\":{\"status\":\"connected\"}}");

        OpenCodeClient.OpenCodeSession session = client.createSession(worktree, "acceptance candidate", null,
                OpenCodeClient.SessionProfile.ACCEPTANCE_CLOSED_CHOICE_CANDIDATE_NO_TOOLS);

        assertThat(session.internalMcpServer()).isEqualTo(internal);
        assertThat(createBody.get()).contains("\"permission\":\"" + internal + "_submit_candidate\"")
                .doesNotContain("\"permission\":\"read\"")
                .doesNotContain("\"permission\":\"glob\"")
                .doesNotContain("\"permission\":\"grep\"")
                .doesNotContain("\"permission\":\"question\"")
                .doesNotContain("user_probe_*")
                .doesNotContain(internal + "_*");

        mcpBody.set("{\"" + internal + "\":{\"status\":\"failed\"}}");
        createBody.set(null);
        assertThatThrownBy(() -> client.createSession(worktree, "acceptance candidate", null,
                OpenCodeClient.SessionProfile.ACCEPTANCE_CLOSED_CHOICE_CANDIDATE_NO_TOOLS))
                .isInstanceOfSatisfying(SessionFailure.class,
                        failure -> assertThat(failure.code()).isEqualTo("OPENCODE_INTERNAL_MCP_NOT_READY"));
        assertThat(createBody.get()).isNull();
    }

    @Test
    void packageDesignCandidateProfilesRequirePrivateMcpAndNeverExposeUserMcp() throws Exception {
        String internal = "loopper_internal_package_design";
        HttpOpenCodeClient client = new HttpOpenCodeClient(RestClient.builder(),
                () -> new OpenCodeRuntimeManager.Connection(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "", "", true,
                        "generation-package-design", internal));
        mcpBody.set("{\"" + internal + "\":{\"status\":\"connected\"},"
                + "\"user_probe\":{\"status\":\"connected\"}}");

        client.createSession(worktree, "package design candidate", null,
                OpenCodeClient.SessionProfile.PACKAGE_DESIGN_CANDIDATE_READ_ONLY);
        assertThat(createBody.get()).contains(
                        "\"permission\":\"read\"",
                        "\"permission\":\"glob\"",
                        "\"permission\":\"grep\"",
                        "\"permission\":\"" + internal + "_submit_candidate\"")
                .doesNotContain("\"permission\":\"question\"")
                .doesNotContain("user_probe_*")
                .doesNotContain(internal + "_*");

        client.createSession(worktree, "interactive package design candidate", null,
                OpenCodeClient.SessionProfile.PACKAGE_DESIGN_CANDIDATE_INTERACTIVE_READ_ONLY);
        assertThat(createBody.get()).contains(
                        "\"permission\":\"read\"",
                        "\"permission\":\"glob\"",
                        "\"permission\":\"grep\"",
                        "\"permission\":\"question\"",
                        "\"permission\":\"" + internal + "_submit_candidate\"")
                .doesNotContain("user_probe_*")
                .doesNotContain(internal + "_*");

        mcpBody.set("{\"" + internal + "\":{\"status\":\"failed\"}}");
        createBody.set(null);
        assertThatThrownBy(() -> client.createSession(worktree, "package design candidate", null,
                OpenCodeClient.SessionProfile.PACKAGE_DESIGN_CANDIDATE_READ_ONLY))
                .isInstanceOfSatisfying(SessionFailure.class,
                        failure -> assertThat(failure.code()).isEqualTo("OPENCODE_INTERNAL_MCP_NOT_READY"));
        assertThat(createBody.get()).isNull();
    }

    @Test
    void routerSkipsInvalidMcpDiscoveryWhileEvidenceRolesStillFailClosed() throws Exception {
        LoopperProperties properties = new LoopperProperties();
        properties.getOpenCode().setBaseUrl(new java.net.URI("http://127.0.0.1:" + server.getAddress().getPort()));
        HttpOpenCodeClient client = new HttpOpenCodeClient(RestClient.builder(), properties);
        mcpBody.set("[]");

        assertThat(client.createSession(worktree, "router", null,
                OpenCodeClient.SessionProfile.ROUTER_NO_TOOLS).id()).isEqualTo("s1");
        assertThat(createBody.get()).doesNotContain("gitlab_internal_*");

        createBody.set(null);
        assertThatThrownBy(() -> client.createSession(worktree, "compiler", null,
                OpenCodeClient.SessionProfile.COMPILER_READ_ONLY))
                .isInstanceOfSatisfying(SessionFailure.class, failure -> {
                    assertThat(failure.code()).isEqualTo("OPENCODE_MCP_DISCOVERY_FAILED");
                    assertThat(failure.getMessage()).contains("MCP Server");
                });
        assertThat(createBody.get()).isNull();
    }

    @Test
    void mapsSchemaRejectionWhileReadingStructuredMessagesToFormatFallbackSignal() throws Exception {
        LoopperProperties properties = new LoopperProperties();
        properties.getOpenCode().setBaseUrl(new java.net.URI("http://127.0.0.1:" + server.getAddress().getPort()));
        HttpOpenCodeClient client = new HttpOpenCodeClient(RestClient.builder(), properties);
        OpenCodeClient.OpenCodeModel model = new OpenCodeClient.OpenCodeModel("deepseek", "deepseek-v4-flash", false);
        OpenCodeClient.OpenCodeSession session = client.createSession(worktree, "structured", model,
                OpenCodeClient.SessionProfile.DECOMPOSER_READ_ONLY);
        client.promptAsync(session, new OpenCodeClient.PromptRequest("decompose", null, null,
                OpenCodeStructuredSchemas.format(OpenCodeStructuredSchemas.DECOMPOSITION_FINAL_V1)));
        assertThat(client.structuredOutputCapability(model).transport()).isEqualTo(OpenCodeClient.CapabilityState.UNKNOWN);
        statusBody.set("{\"s1\":{\"type\":\"busy\"}}");
        messageStatusCode.set(400);
        messageBody.set("{\"name\":\"BadRequest\",\"data\":{\"message\":\"Expected OutputFormatJsonSchema, got json_schema\"}}");

        assertThatThrownBy(() -> client.sessionStatus(session))
                .isInstanceOf(SessionFailure.class)
                .extracting(failure -> ((SessionFailure) failure).code())
                .isEqualTo("OPENCODE_STRUCTURED_FORMAT_UNSUPPORTED");
        assertThat(client.structuredOutputCapability(model).transport()).isEqualTo(OpenCodeClient.CapabilityState.UNAVAILABLE);
    }

    @Test
    void quarantinesKnownOpenCodeStoredSchemaDecoderVersionsBeforePrompting() throws Exception {
        LoopperProperties properties = new LoopperProperties();
        properties.getOpenCode().setBaseUrl(new java.net.URI("http://127.0.0.1:" + server.getAddress().getPort()));
        HttpOpenCodeClient client = new HttpOpenCodeClient(RestClient.builder(), properties);
        OpenCodeClient.OpenCodeModel model = new OpenCodeClient.OpenCodeModel(
                "deepseek", "deepseek-v4-flash", false);
        healthBody.set("{\"healthy\":true,\"version\":\"1.18.18\"}");

        OpenCodeClient.StructuredOutputCapability capability = client.structuredOutputCapability(model);

        assertThat(capability.transport()).isEqualTo(OpenCodeClient.CapabilityState.UNAVAILABLE);
        assertThat(capability.detail()).contains("1.18.18", "marker compatibility mode");
        assertThat(promptBody.get()).isNull();
    }

    @Test
    void mapsBusyStructuredToolFailureToFreshMarkerFallbackSignal() throws Exception {
        LoopperProperties properties = new LoopperProperties();
        properties.getOpenCode().setBaseUrl(new java.net.URI("http://127.0.0.1:" + server.getAddress().getPort()));
        HttpOpenCodeClient client = new HttpOpenCodeClient(RestClient.builder(), properties);
        OpenCodeClient.OpenCodeSession session = client.createSession(worktree, "structured",
                new OpenCodeClient.OpenCodeModel("deepseek", "deepseek-v4-flash", false),
                OpenCodeClient.SessionProfile.DECOMPOSER_READ_ONLY);
        client.promptAsync(session, new OpenCodeClient.PromptRequest("decompose", null, null,
                OpenCodeStructuredSchemas.format(OpenCodeStructuredSchemas.DECOMPOSITION_FINAL_V1)));
        statusBody.set("{\"s1\":{\"type\":\"busy\"}}");
        messageBody.set("[{\"info\":{\"role\":\"user\"}},"
                + "{\"info\":{\"role\":\"assistant\"},\"parts\":[{\"type\":\"tool\","
                + "\"tool\":\"structured_output\",\"state\":{\"status\":\"error\","
                + "\"error\":\"DeepSeek did not produce the required object\"}}]}]");

        assertThatThrownBy(() -> client.sessionStatus(session))
                .isInstanceOf(SessionFailure.class)
                .extracting(failure -> ((SessionFailure) failure).code())
                .isEqualTo("OPENCODE_STRUCTURED_OUTPUT_FAILED");
    }

    @Test
    void enforcesMachineResponseStepLimitEvenWhenOpenCodeStillReportsBusy() throws Exception {
        LoopperProperties properties = new LoopperProperties();
        properties.getOpenCode().setBaseUrl(new java.net.URI("http://127.0.0.1:" + server.getAddress().getPort()));
        HttpOpenCodeClient client = new HttpOpenCodeClient(RestClient.builder(), properties);
        OpenCodeClient.OpenCodeSession session = client.createSession(worktree, "marker",
                new OpenCodeClient.OpenCodeModel("deepseek", "deepseek-v4-flash", false),
                OpenCodeClient.SessionProfile.DECOMPOSER_READ_ONLY);
        client.promptAsync(session, OpenCodeClient.PromptRequest.text("decompose"));
        statusBody.set("{\"s1\":{\"type\":\"busy\"}}");
        StringBuilder messages = new StringBuilder("[{\"info\":{\"role\":\"user\"}}");
        for (int step = 0; step <= OpenCodeClient.STRUCTURED_AGENT_STEPS; step++) {
            messages.append(",{").append("\"info\":{\"role\":\"assistant\"},")
                    .append("\"parts\":[{\"type\":\"step-start\"}]}");
        }
        messageBody.set(messages.append(']').toString());

        assertThatThrownBy(() -> client.sessionStatus(session))
                .isInstanceOf(SessionFailure.class)
                .extracting(failure -> ((SessionFailure) failure).code())
                .isEqualTo("OPENCODE_MACHINE_STEP_LIMIT_EXCEEDED");
    }

    @Test
    void stopsOnThirdConsecutiveEquivalentToolCallButAllowsDifferentArguments() throws Exception {
        LoopperProperties properties = new LoopperProperties();
        properties.getOpenCode().setBaseUrl(new java.net.URI("http://127.0.0.1:" + server.getAddress().getPort()));
        HttpOpenCodeClient client = new HttpOpenCodeClient(RestClient.builder(), properties);
        OpenCodeClient.OpenCodeSession session = client.createSession(worktree, "marker", null,
                OpenCodeClient.SessionProfile.COMPILER_READ_ONLY);
        client.promptAsync(session, OpenCodeClient.PromptRequest.text("compile"));
        statusBody.set("{\"s1\":{\"type\":\"busy\"}}");
        messageBody.set("[{\"info\":{\"role\":\"user\"}},"
                + toolMessage("Read", "{\"path\":\"pom.xml\",\"line\":1}") + ","
                + toolMessage("read", "{\"line\":1,\"path\":\"pom.xml\"}") + ","
                + toolMessage("READ", "{\"path\":\"pom.xml\",\"line\":1}") + "]");

        assertThatThrownBy(() -> client.sessionStatus(session))
                .isInstanceOf(SessionFailure.class)
                .extracting(failure -> ((SessionFailure) failure).code())
                .isEqualTo("OPENCODE_MACHINE_TOOL_LOOP");

        messageBody.set("[{\"info\":{\"role\":\"user\"}},"
                + toolMessage("read", "{\"path\":\"pom.xml\"}") + ","
                + toolMessage("read", "{\"path\":\"README.md\"}") + ","
                + toolMessage("read", "{\"path\":\"pom.xml\"}") + "]");
        assertThat(client.sessionStatus(session).state()).isEqualTo("busy");
    }

    private static String toolMessage(String tool, String input) {
        return "{\"info\":{\"role\":\"assistant\"},\"parts\":[{\"type\":\"tool\",\"tool\":\""
                + tool + "\",\"state\":{\"status\":\"completed\",\"input\":" + input + "}}]}";
    }

    @Test
    void selectsBoundedStructuredAgentOnlyForManagedMachineResponseSessions() throws Exception {
        java.net.URI endpoint = new java.net.URI("http://127.0.0.1:" + server.getAddress().getPort());
        HttpOpenCodeClient client = new HttpOpenCodeClient(RestClient.builder(),
                () -> new OpenCodeRuntimeManager.Connection(endpoint, "", "", true,
                        "generation-agent", "loopper_internal_agent"));
        OpenCodeClient.OpenCodeSession session = client.createSession(worktree, "decomposer", null,
                OpenCodeClient.SessionProfile.DECOMPOSER_READ_ONLY);

        client.promptAsync(session, OpenCodeClient.PromptRequest.text("decompose"));

        assertThat(promptBody.get()).contains("\"agent\":\"loopper-structured\"");
    }

    @Test
    void selectsSingleShotNoThinkingAgentForManagedRouter() throws Exception {
        java.net.URI endpoint = new java.net.URI("http://127.0.0.1:" + server.getAddress().getPort());
        HttpOpenCodeClient client = new HttpOpenCodeClient(RestClient.builder(),
                () -> new OpenCodeRuntimeManager.Connection(endpoint, "", "", true,
                        "generation-router", "loopper_internal_router"));
        OpenCodeClient.OpenCodeSession session = client.createSession(worktree, "router",
                new OpenCodeClient.OpenCodeModel("deepseek", "deepseek-v4-flash", true),
                OpenCodeClient.SessionProfile.ROUTER_NO_TOOLS);

        client.promptAsync(session, OpenCodeClient.PromptRequest.text("classify"));

        assertThat(promptBody.get()).contains("\"agent\":\"loopper-router\"")
                .contains("\"variant\":\"loopper-no-thinking\"");
    }

    @Test
    void percentEncodesPlusInEveryDirectoryQueryValue() throws Exception {
        LoopperProperties properties = new LoopperProperties();
        properties.getOpenCode().setBaseUrl(new java.net.URI("http://127.0.0.1:" + server.getAddress().getPort()));
        HttpOpenCodeClient client = new HttpOpenCodeClient(RestClient.builder(), properties);
        Path plusWorktree = Files.createDirectory(worktree.resolve("project+plus")).toRealPath();
        createResponseDirectory.set(plusWorktree.toString());

        OpenCodeClient.OpenCodeSession session = client.createSession(plusWorktree, "plus path", null);

        assertThat(session.worktree()).isEqualTo(plusWorktree);
        assertThat(lastPathAndQuery.get()).contains("project%2Bplus").doesNotContain("project+plus");
    }

    @Test
    void reusedSessionWaitsForAssistantReplyAfterItsLatestUserPrompt() throws Exception {
        LoopperProperties properties = new LoopperProperties();
        properties.getOpenCode().setBaseUrl(new java.net.URI("http://127.0.0.1:" + server.getAddress().getPort()));
        HttpOpenCodeClient client = new HttpOpenCodeClient(RestClient.builder(), properties);
        OpenCodeClient.OpenCodeSession session = client.createReadOnlySession(worktree, "Designer", null);
        client.promptAsync(session, "first request");
        statusBody.set("{}");
        messageBody.set("["
                + "{\"info\":{\"role\":\"user\"}},"
                + "{\"info\":{\"role\":\"assistant\",\"time\":{\"completed\":123}}},"
                + "{\"info\":{\"role\":\"user\"}},"
                + "{\"info\":{\"role\":\"assistant\"},\"parts\":[{\"type\":\"text\",\"text\":\"new partial reply\"}]}]");
        assertThat(client.sessionStatus(session).state()).isEqualTo("RUNNING");
        assertThat(client.sessionLiveOutput(session)).isEqualTo("new partial reply");
        messageBody.set("["
                + "{\"info\":{\"role\":\"user\"}},"
                + "{\"info\":{\"role\":\"assistant\",\"error\":{\"message\":\"provider timed out\"}}}]");
        OpenCodeClient.SessionStatus failed = client.sessionStatus(session);
        assertThat(failed.failed()).isTrue();
        assertThat(failed.detail()).isEqualTo("provider timed out");
        messageBody.set("["
                + "{\"info\":{\"role\":\"user\"}},"
                + "{\"info\":{\"role\":\"assistant\",\"time\":{\"completed\":123}}},"
                + "{\"info\":{\"role\":\"user\"}},"
                + "{\"info\":{\"role\":\"assistant\",\"time\":{\"completed\":456}}}]");
        assertThat(client.sessionStatus(session).completed()).isTrue();
    }

    @Test
    void rejectsMissingOrMismatchedSessionDirectoryBeforePrompting() throws Exception {
        LoopperProperties properties = new LoopperProperties();
        properties.getOpenCode().setBaseUrl(new java.net.URI("http://127.0.0.1:" + server.getAddress().getPort()));
        HttpOpenCodeClient client = new HttpOpenCodeClient(RestClient.builder(), properties);
        Path outside = Files.createDirectory(worktree.resolve("outside"));

        createResponseDirectory.set(outside.toString());
        assertThatThrownBy(() -> client.createSession(worktree, "wrong directory", null))
                .isInstanceOf(SessionFailure.class)
                .hasMessageContaining("outside the requested execution workspace");

        createResponseDirectory.set("");
        assertThatThrownBy(() -> client.createSession(worktree, "missing directory", null))
                .isInstanceOf(SessionFailure.class)
                .hasMessageContaining("did not confirm the execution directory");
    }

    @Test
    void exposesIncrementalThinkingOutputAndToolPartsForLiveMonitoring() throws Exception {
        LoopperProperties properties = new LoopperProperties();
        properties.getOpenCode().setBaseUrl(new java.net.URI("http://127.0.0.1:" + server.getAddress().getPort()));
        HttpOpenCodeClient client = new HttpOpenCodeClient(RestClient.builder(), properties);
        OpenCodeClient.OpenCodeSession session = client.createSession(worktree, "monitor", null);
        messageBody.set("["
                + "{\"info\":{\"id\":\"message-user\",\"role\":\"user\"},\"parts\":[{\"type\":\"text\",\"text\":\"prompt\"}]},"
                + "{\"info\":{\"id\":\"message-assistant\",\"role\":\"assistant\",\"time\":{\"created\":1785836900057,\"completed\":1785836902200},\"tokens\":{\"input\":64,\"output\":32,\"total\":96}},\"parts\":["
                + "{\"id\":\"reason-1\",\"type\":\"reasoning\",\"text\":\"Inspecting the project\",\"time\":{\"start\":1785836901408}},"
                + "{\"id\":\"tool-1\",\"type\":\"tool\",\"tool\":\"read\",\"state\":{\"status\":\"completed\",\"title\":\"Read pom.xml\",\"input\":{\"filePath\":\"pom.xml\"},\"output\":\"project source\",\"time\":{\"start\":1785836902020}}},"
                + "{\"id\":\"text-1\",\"type\":\"text\",\"text\":\"Implementation is in progress\",\"time\":{\"start\":1785836902100}}]}]");

        OpenCodeClient.SessionTranscript transcript = client.sessionTranscript(session);

        assertThat(transcript.parts()).extracting(OpenCodeClient.SessionPart::type)
                .containsExactly("THINKING", "TOOL", "OUTPUT");
        assertThat(transcript.parts().get(0).content()).isEqualTo("Inspecting the project");
        assertThat(transcript.parts().get(1).label()).isEqualTo("read");
        assertThat(transcript.parts().get(1).content()).contains("参数", "pom.xml", "输出", "project source");
        assertThat(transcript.parts().get(2).content()).isEqualTo("Implementation is in progress");
        assertThat(transcript.parts()).extracting(OpenCodeClient.SessionPart::startedAt)
                .containsExactly("2026-08-04T09:48:21.408Z", "2026-08-04T09:48:22.020Z", "2026-08-04T09:48:22.100Z");
        assertThat(transcript.usage()).singleElement().satisfies(usage -> {
            assertThat(usage.inputTokens()).isEqualTo(64L);
            assertThat(usage.outputTokens()).isEqualTo(32L);
            assertThat(usage.totalTokens()).isEqualTo(96L);
        });
        assertThat(client.sessionMessageRefs(session)).extracting(OpenCodeClient.SessionMessageRef::id)
                .containsExactly("message-user", "message-assistant")
                .doesNotContain("reason-1", "tool-1", "text-1");
    }

    @Test
    void listsOnlyQuestionsForTheSelectedSessionAndForwardsReplyAndReject() throws Exception {
        LoopperProperties properties = new LoopperProperties();
        properties.getOpenCode().setBaseUrl(new java.net.URI("http://127.0.0.1:" + server.getAddress().getPort()));
        HttpOpenCodeClient client = new HttpOpenCodeClient(RestClient.builder(), properties);
        OpenCodeClient.OpenCodeSession session = client.createSession(worktree, "question", null);
        questionBody.set("["
                + "{\"id\":\"que-other\",\"sessionID\":\"other\",\"questions\":[]},"
                + "{\"id\":\"que-1\",\"sessionID\":\"s1\",\"questions\":[{\"question\":\"How?\",\"header\":\"Choice\",\"options\":[{\"label\":\"Option A\",\"description\":\"Use A\"}],\"multiple\":false,\"custom\":true}]}]");

        java.util.List<OpenCodeClient.PendingQuestion> pending = client.pendingQuestions(session);

        assertThat(pending).hasSize(1);
        assertThat(pending.getFirst().id()).isEqualTo("que-1");
        assertThat(pending.getFirst().questions().getFirst().options().getFirst().label()).isEqualTo("Option A");
        assertThat(pending.getFirst().questions().getFirst().custom()).isTrue();
        assertThat(lastPathAndQuery.get()).contains("/question").contains("directory=");

        client.replyQuestion(session, "que-1", java.util.List.of(java.util.List.of("Option A")));
        assertThat(lastPathAndQuery.get()).contains("/question/que-1/reply").contains("directory=");
        assertThat(questionActionBody.get()).isEqualTo("{\"answers\":[[\"Option A\"]]}");

        client.rejectQuestion(session, "que-1");
        assertThat(lastPathAndQuery.get()).contains("/question/que-1/reject").contains("directory=");
    }

    @Test
    void supportsPermissionTodoSessionControlAndNullableUsageContracts() throws Exception {
        LoopperProperties properties = new LoopperProperties();
        properties.getOpenCode().setBaseUrl(new java.net.URI("http://127.0.0.1:" + server.getAddress().getPort()));
        HttpOpenCodeClient client = new HttpOpenCodeClient(RestClient.builder(), properties);
        OpenCodeClient.OpenCodeSession session = client.createSession(worktree, "runtime", null);
        permissionBody.set("["
                + "{\"id\":\"perm-other\",\"sessionID\":\"other\",\"permission\":\"bash\",\"patterns\":[]},"
                + "{\"id\":\"perm-1\",\"sessionID\":\"s1\",\"permission\":\"bash\",\"patterns\":[\"git push\"],\"metadata\":{\"title\":\"Publish\",\"nested\":{\"safe\":true}}}]");
        todoBody.set("[{\"content\":\"Read docs\",\"status\":\"in_progress\",\"priority\":\"high\","
                + "\"metadata\":{\"note\":null}}]");

        assertThat(client.pendingPermissions(session)).singleElement().satisfies(permission -> {
            assertThat(permission.id()).isEqualTo("perm-1");
            assertThat(permission.patterns()).containsExactly("git push");
            assertThat(permission.metadata()).containsEntry("title", "Publish");
            assertThat(permission.title()).isEqualTo("Publish");
        });
        assertThat(lastPathAndQuery.get()).contains("/permission").contains("directory=");
        client.replyPermission(session, "perm-1", OpenCodeClient.PermissionReply.SESSION, "approved for this session");
        assertThat(lastPathAndQuery.get()).contains("/permission/perm-1/reply").contains("directory=");
        assertThat(permissionActionBody.get()).isEqualTo("{\"reply\":\"always\",\"message\":\"approved for this session\"}");

        assertThat(client.sessionTodos(session)).singleElement().satisfies(todo -> {
            assertThat(todo.id()).matches("todo-v2:[0-9a-f]{64}:1");
            assertThat(todo.content()).isEqualTo("Read docs");
            assertThat(todo.status()).isEqualTo("IN_PROGRESS");
            assertThat(todo.priority()).isEqualTo("HIGH");
            assertThat(todo.ordinal()).isZero();
            assertThat(todo.metadata()).containsEntry("note", null);
        });
        assertThat(lastPathAndQuery.get()).contains("/session/s1/todo").contains("directory=");
        OpenCodeClient.OpenCodeSession fork = client.forkSession(session, "msg-1");
        assertThat(fork.id()).isEqualTo("fork-1");
        assertThat(lastPathAndQuery.get()).contains("/session/s1/fork").contains("directory=");
        assertThat(sessionActionBody.get()).isEqualTo("{\"messageID\":\"msg-1\"}");
        client.revertSession(session, "msg-1", "part-1");
        assertThat(lastPathAndQuery.get()).contains("/session/s1/revert").contains("directory=");
        assertThat(sessionActionBody.get()).isEqualTo("{\"messageID\":\"msg-1\",\"partID\":\"part-1\"}");
        client.summarizeSession(session, new OpenCodeClient.OpenCodeModel("opencode", "model-1", null), true);
        assertThat(lastPathAndQuery.get()).contains("/session/s1/summarize").contains("directory=");
        assertThat(sessionActionBody.get()).isEqualTo("{\"providerID\":\"opencode\",\"modelID\":\"model-1\",\"auto\":true}");

        messageBody.set("[{\"info\":{\"id\":\"msg-usage\",\"role\":\"assistant\",\"providerID\":\"opencode\",\"modelID\":\"model-1\",\"tokens\":{\"input\":11,\"output\":13}}}]");
        assertThat(client.sessionUsage(session)).singleElement().satisfies(usage -> {
            assertThat(usage.messageId()).isEqualTo("msg-usage");
            assertThat(usage.inputTokens()).isEqualTo(11L);
            assertThat(usage.outputTokens()).isEqualTo(13L);
            assertThat(usage.totalTokens()).isNull();
            assertThat(usage.costAmount()).isNull();
        });
        messageBody.set("[{\"info\":{\"id\":\"msg-without-usage\",\"role\":\"assistant\"}}]");
        assertThat(client.sessionUsage(session)).singleElement().satisfies(usage -> {
            assertThat(usage.messageId()).isEqualTo("msg-without-usage");
            assertThat(usage.inputTokens()).isNull();
            assertThat(usage.outputTokens()).isNull();
            assertThat(usage.totalTokens()).isNull();
            assertThat(usage.costAmount()).isNull();
            assertThat(usage.reliable()).isFalse();
        });
    }

    @Test
    void requestTimeoutBoundsStalledOpenCodeTransport() throws Exception {
        LoopperProperties properties = new LoopperProperties();
        properties.getOpenCode().setBaseUrl(new java.net.URI("http://127.0.0.1:" + server.getAddress().getPort()));
        properties.getOpenCode().setConnectTimeout(Duration.ofMillis(100));
        properties.getOpenCode().setRequestTimeout(Duration.ofMillis(100));
        responseDelayMillis.set(3_000);
        HttpOpenCodeClient client = new HttpOpenCodeClient(RestClient.builder(), properties);

        long started = System.nanoTime();
        assertThatThrownBy(() -> client.createSession(worktree, "timeout", null))
                .isInstanceOf(SessionFailure.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void timeoutNormalizationCanNeverSelectHttpUrlConnectionsInfiniteZeroValue() {
        assertThat(OpenCodeHttpTransport.boundedTimeoutMillis(Duration.ofNanos(1), Duration.ofSeconds(5))).isEqualTo(1);
        assertThat(OpenCodeHttpTransport.boundedTimeoutMillis(Duration.ofMillis((long) Integer.MAX_VALUE + 1), Duration.ofSeconds(5)))
                .isEqualTo(Integer.MAX_VALUE);
        assertThat(OpenCodeHttpTransport.boundedTimeoutMillis(Duration.ZERO, Duration.ofSeconds(5))).isEqualTo(5_000);
    }

    @Test
    void sendsTypedJsonSchemaPromptsAndDiscoversNativeAgentsAndTools() throws Exception {
        LoopperProperties properties = new LoopperProperties();
        properties.getOpenCode().setBaseUrl(new java.net.URI("http://127.0.0.1:" + server.getAddress().getPort()));
        HttpOpenCodeClient client = new HttpOpenCodeClient(RestClient.builder(), properties);
        OpenCodeClient.OpenCodeSession session = client.createSession(worktree, "structured",
                new OpenCodeClient.OpenCodeModel("deepseek", "deepseek-v4-flash", false),
                OpenCodeClient.SessionProfile.COMPILER_READ_ONLY);

        client.promptAsync(session, new OpenCodeClient.PromptRequest("compile", "system", "build",
                OpenCodeStructuredSchemas.format(OpenCodeStructuredSchemas.JUDGE_DECISION_V1)));

        assertThat(promptBody.get()).contains("\"system\":\"system\"", "\"agent\":\"build\"",
                "\"type\":\"json_schema\"", "\"retryCount\":0", "\"verdict\"",
                "\"variant\":\"loopper-no-thinking\"")
                .doesNotContain("\"tools\"");
        client.promptAsync(session, OpenCodeClient.PromptRequest.text("marker"));
        assertThat(promptBody.get()).doesNotContain("\"format\"", "\"variant\":\"loopper-no-thinking\"");
        messageBody.set("[{\"info\":{\"role\":\"user\"}},{\"info\":{\"role\":\"assistant\",\"structured\":{\"verdict\":\"PASS\",\"reason\":\"ok\"}}}]");
        assertThat(client.sessionResult(session).structured()).containsEntry("verdict", "PASS");
        assertThat(client.structuredOutputCapability(new OpenCodeClient.OpenCodeModel(
                "deepseek", "deepseek-v4-flash", false)).selectedModel())
                .isEqualTo(OpenCodeClient.CapabilityState.AVAILABLE);
        assertThat(client.toolCapabilities(worktree).contains("todowrite")).isTrue();
        assertThat(client.agents()).extracting(OpenCodeClient.AgentInfo::name).containsExactly("build", "plan");
    }

    @Test
    void sendsStableMessageIdentityAndOrderedFileParts() throws Exception {
        LoopperProperties properties = new LoopperProperties();
        properties.getOpenCode().setBaseUrl(new URI("http://127.0.0.1:" + server.getAddress().getPort()));
        HttpOpenCodeClient client = new HttpOpenCodeClient(RestClient.builder(), properties);
        OpenCodeClient.OpenCodeSession session = client.createReadOnlySession(worktree, "Designer", null);
        Path context = Files.writeString(worktree.resolve("requirements.txt"), "exact attachment context");

        client.promptAsync(session, new OpenCodeClient.PromptRequest(
                "Use the attached reference.", null, null, new OpenCodeClient.ResponseFormat.Text(),
                "msg-designer-1", List.of(new OpenCodeClient.FilePart(
                        "requirements.txt", "text/plain", context.toUri(), "sha-256-value"))));

        assertThat(promptBody.get()).contains(
                "\"messageID\":\"msg-designer-1\"",
                "\"type\":\"text\"",
                "\"text\":\"Use the attached reference.\"",
                "\"type\":\"file\"",
                "\"mime\":\"text/plain\"",
                "\"filename\":\"requirements.txt\"",
                "\"url\":\"" + context.toUri() + "\"");
        assertThat(promptBody.get().indexOf("\"type\":\"text\""))
                .isLessThan(promptBody.get().indexOf("\"type\":\"file\""));
        assertThat(promptBody.get()).doesNotContain("sha-256-value");
    }

    @Test
    void candidatePlanPreparationIsLocalAndReadinessIsTheFirstRemoteBoundary() throws Exception {
        AtomicInteger remoteConnectionResolutions = new AtomicInteger();
        AtomicReference<OpenCodeRuntimeManager.Connection> connection = new AtomicReference<>(
                new OpenCodeRuntimeManager.Connection(endpoint(), null, null,
                        true, "generation-7", "loopper-private-7"));
        HttpOpenCodeClient client = new HttpOpenCodeClient(RestClient.builder(), () -> {
            remoteConnectionResolutions.incrementAndGet();
            return connection.get();
        }, () -> new OpenCodeRuntimeManager.RuntimeIdentity(
                endpoint(), true, "generation-7", "loopper-private-7"),
                properties(), new OpenCodeCapabilityRegistry(), new InMemoryRuntimeBindings());
        OpenCodeClient.OpenCodeModel model = new OpenCodeClient.OpenCodeModel(
                "opencode-go", "deepseek-v4-flash", false);
        String credential = "0123456789abcdefghijklmnopqrstuvwxyz_ABCD12";

        OpenCodeClient.SessionCreationPlan first = client.prepareCandidateSessionCreationLocally(
                worktree, "Acceptance internal", model,
                OpenCodeClient.SessionProfile.ACCEPTANCE_CLOSED_CHOICE_CANDIDATE_NO_TOOLS,
                credential);
        OpenCodeClient.SessionCreationPlan replay = client.prepareCandidateSessionCreationLocally(
                worktree, "Acceptance internal", model,
                OpenCodeClient.SessionProfile.ACCEPTANCE_CLOSED_CHOICE_CANDIDATE_NO_TOOLS,
                credential);

        assertThat(remoteConnectionResolutions).hasValue(0);
        assertThat(httpRequests).hasValue(0);
        assertThat(mcpRequests).hasValue(0);
        assertThat(sessionListRequests).hasValue(0);
        assertThat(first).isEqualTo(replay);
        assertThat(first.permissionPolicy()).containsExactly(
                new OpenCodeClient.SessionPermissionRule("*", "*", "deny"),
                new OpenCodeClient.SessionPermissionRule("external_directory", "*", "deny"),
                new OpenCodeClient.SessionPermissionRule(
                        "loopper-private-7_submit_candidate", "*", "allow"));
        assertThat(first.permissionPolicyDigest()).hasSize(64);
        assertThat(first.createRequestSha256()).hasSize(64);

        mcpBody.set("{\"loopper-private-7\":{\"status\":\"connected\"}}");
        client.requireCandidateSessionReady(first);

        assertThat(remoteConnectionResolutions).hasValue(1);
        assertThat(httpRequests).hasValue(1);
        assertThat(mcpRequests).hasValue(1);
        assertThat(first).isEqualTo(replay);

        connection.set(new OpenCodeRuntimeManager.Connection(endpoint(), null, null,
                true, "generation-8", "loopper-private-8"));
        assertThatThrownBy(() -> client.requireCandidateSessionReady(first))
                .isInstanceOfSatisfying(SessionFailure.class, failure -> assertThat(failure.code())
                        .isEqualTo("OPENCODE_SESSION_CREATION_PLAN_STALE"));
        assertThat(mcpRequests).hasValue(1);
        assertThat(httpRequests).hasValue(1);
    }

    @Test
    void candidateLocalPlanRejectsWrongProfileAndIncompleteOrExternalRuntimeIdentity() throws Exception {
        AtomicInteger remoteConnectionResolutions = new AtomicInteger();
        AtomicReference<OpenCodeRuntimeManager.RuntimeIdentity> identity = new AtomicReference<>(
                new OpenCodeRuntimeManager.RuntimeIdentity(
                        endpoint(), true, "generation-7", "loopper-private-7"));
        HttpOpenCodeClient client = new HttpOpenCodeClient(RestClient.builder(), () -> {
            remoteConnectionResolutions.incrementAndGet();
            return new OpenCodeRuntimeManager.Connection(endpoint(), null, null,
                    true, "generation-7", "loopper-private-7");
        }, identity::get, properties(), new OpenCodeCapabilityRegistry(), new InMemoryRuntimeBindings());
        String credential = "0123456789abcdefghijklmnopqrstuvwxyz_ABCD12";

        assertThatThrownBy(() -> client.prepareCandidateSessionCreationLocally(worktree,
                "Acceptance internal", null, OpenCodeClient.SessionProfile.COMPILER_BINDING_NO_TOOLS,
                credential)).isInstanceOfSatisfying(SessionFailure.class, failure -> assertThat(failure.code())
                .isEqualTo("OPENCODE_CANDIDATE_PROFILE_INVALID"));

        identity.set(new OpenCodeRuntimeManager.RuntimeIdentity(endpoint(), false, null, null));
        assertThatThrownBy(() -> client.prepareCandidateSessionCreationLocally(worktree,
                "Acceptance internal", null,
                OpenCodeClient.SessionProfile.ACCEPTANCE_CLOSED_CHOICE_CANDIDATE_NO_TOOLS,
                credential)).isInstanceOfSatisfying(SessionFailure.class, failure -> assertThat(failure.code())
                .isEqualTo("CANDIDATE_MANAGED_RUNTIME_REQUIRED"));

        identity.set(new OpenCodeRuntimeManager.RuntimeIdentity(
                endpoint(), true, null, "loopper-private-7"));
        assertThatThrownBy(() -> client.prepareCandidateSessionCreationLocally(worktree,
                "Acceptance internal", null,
                OpenCodeClient.SessionProfile.ACCEPTANCE_CLOSED_CHOICE_CANDIDATE_NO_TOOLS,
                credential)).isInstanceOfSatisfying(SessionFailure.class, failure -> assertThat(failure.code())
                .isEqualTo("OPENCODE_CANDIDATE_RUNTIME_IDENTITY_INCOMPLETE"));

        OpenCodeClient.SessionProfile candidate =
                OpenCodeClient.SessionProfile.ACCEPTANCE_CLOSED_CHOICE_CANDIDATE_NO_TOOLS;
        List<OpenCodeClient.SessionPermissionRule> externalPermissions = List.of(
                new OpenCodeClient.SessionPermissionRule("*", "*", "deny"),
                new OpenCodeClient.SessionPermissionRule("external_directory", "*", "deny"));
        String permissionDigest = OpenCodeClient.permissionPolicyDigest(externalPermissions);
        String fingerprint = OpenCodeSessionConnectionGuard.endpointFingerprint(endpoint());
        String title = OpenCodeClient.recoveryTitle("Acceptance external", credential);
        String requestDigest = OpenCodeClient.sessionCreationRequestSha256(worktree.toRealPath(), title,
                "external-" + fingerprint, false, null, fingerprint, null, candidate,
                permissionDigest, credential);
        OpenCodeClient.SessionCreationPlan unmanaged = new OpenCodeClient.SessionCreationPlan(
                worktree.toRealPath(), title, "external-" + fingerprint, false, null,
                fingerprint, null, candidate, externalPermissions, permissionDigest,
                credential, requestDigest);
        assertThatThrownBy(() -> client.requireCandidateSessionReady(unmanaged))
                .isInstanceOfSatisfying(SessionFailure.class, failure -> assertThat(failure.code())
                        .isEqualTo("CANDIDATE_MANAGED_RUNTIME_REQUIRED"));

        assertThat(remoteConnectionResolutions).hasValue(0);
        assertThat(httpRequests).hasValue(0);
        assertThat(mcpRequests).hasValue(0);
    }

    @Test
    void attestedCreateAndExactTitleLookupFailClosedOnMalformedMatches() throws Exception {
        AtomicReference<OpenCodeRuntimeManager.Connection> connection = new AtomicReference<>(
                new OpenCodeRuntimeManager.Connection(endpoint(), null, null, false));
        InMemoryRuntimeBindings bindings = new InMemoryRuntimeBindings();
        HttpOpenCodeClient client = new HttpOpenCodeClient(RestClient.builder(), connection::get,
                properties(), new OpenCodeCapabilityRegistry(), bindings);
        OpenCodeClient.OpenCodeModel model = new OpenCodeClient.OpenCodeModel(
                "opencode-go", "deepseek-v4-flash", false);
        mcpBody.set("{\"project evidence\":{\"status\":\"connected\"}}");
        OpenCodeClient.SessionCreationPlan plan = client.prepareSessionCreation(worktree,
                "Acceptance legacy", model, OpenCodeClient.SessionProfile.COMPILER_BINDING_NO_TOOLS,
                "0123456789abcdefghijklmnopqrstuvwxyz_ABCD12");
        mcpBody.set("{\"changed after checkpoint\":{\"status\":\"connected\"}}");
        sessionListBody.set("null");
        sessionListStatusCode.set(500);

        OpenCodeClient.SessionAttestation created = client.createSession(plan);

        assertThat(created.remoteId()).isEqualTo("s1");
        assertThat(created.plan()).isEqualTo(plan);
        assertThat(created.attestationKind())
                .isEqualTo(OpenCodeClient.SessionAttestationKind.LOCAL_REQUEST_ATTESTED);
        assertThat(mcpRequests.get()).isEqualTo(1);
        assertThat(sessionListRequests.get()).isZero();
        assertThat(createBody.get()).contains("project_evidence_*")
                .doesNotContain("changed_after_checkpoint_*");
        sessionListStatusCode.set(200);
        sessionListBody.set(sessionList(plan.exactTitle(), "s1", worktree.toRealPath().toString()));
        assertThat(client.findSessionsByExactTitle(plan).matches()).containsExactly(created);

        Path otherDirectory = Files.createDirectory(worktree.resolve("other"));
        List<String> malformed = List.of(
                "null",
                "{}",
                "[1]",
                "[{\"title\":1}]",
                "[{\"title\":\"" + json(plan.exactTitle()) + "\",\"directory\":\""
                        + json(worktree.toRealPath().toString()) + "\"}]",
                "[{\"title\":\"" + json(plan.exactTitle()) + "\",\"id\":1,\"directory\":\""
                        + json(worktree.toRealPath().toString()) + "\"}]",
                "[{\"title\":\"" + json(plan.exactTitle()) + "\",\"id\":\"s1\"}]",
                "[{\"title\":\"" + json(plan.exactTitle()) + "\",\"id\":\"s1\",\"directory\":1}]",
                sessionList(plan.exactTitle(), "s1", otherDirectory.toRealPath().toString()),
                "[{\"title\":\"" + json(plan.exactTitle()) + "\",\"id\":\"s1\",\"directory\":\"\\u0000\"}]"
        );
        for (String body : malformed) {
            sessionListBody.set(body);
            assertThatThrownBy(() -> client.findSessionsByExactTitle(plan))
                    .as("body %s", body)
                    .isInstanceOfSatisfying(SessionFailure.class, failure -> assertThat(failure.code())
                            .isEqualTo("OPENCODE_SESSION_LOOKUP_INVALID_RESPONSE"));
        }

        connection.set(new OpenCodeRuntimeManager.Connection(endpoint(), null, null,
                true, "generation-2", "loopper-private-2"));
        assertThatThrownBy(() -> client.findSessionsByExactTitle(plan))
                .isInstanceOfSatisfying(SessionFailure.class, failure -> assertThat(failure.code())
                        .isEqualTo("OPENCODE_SESSION_CREATION_PLAN_STALE"));
    }

    @Test
    void exactPromptLookupTreatsOnly404AsAbsentAndRejectsMalformedOrDrifted200() throws Exception {
        AtomicReference<OpenCodeRuntimeManager.Connection> connection = new AtomicReference<>(
                new OpenCodeRuntimeManager.Connection(endpoint(), null, null, false));
        HttpOpenCodeClient client = new HttpOpenCodeClient(RestClient.builder(), connection::get,
                properties(), new OpenCodeCapabilityRegistry(), new InMemoryRuntimeBindings());
        OpenCodeClient.SessionCreationPlan plan = client.prepareSessionCreation(worktree,
                "Acceptance legacy", new OpenCodeClient.OpenCodeModel(
                        "opencode-go", "deepseek-v4-flash", false),
                OpenCodeClient.SessionProfile.COMPILER_BINDING_NO_TOOLS,
                "0123456789abcdefghijklmnopqrstuvwxyz_ABCD12");
        sessionListBody.set(sessionList(plan.exactTitle(), "s1", worktree.toRealPath().toString()));
        OpenCodeClient.OpenCodeSession session = client.createSession(plan).session();
        OpenCodeClient.PromptRequest expected = new OpenCodeClient.PromptRequest(
                "Choose candidate 1", null, null, new OpenCodeClient.ResponseFormat.Text(),
                "message-1", List.of());
        String requestSha256 = OpenCodeClient.promptRequestSha256(expected);
        exactMessageBody.set("{\"info\":{\"id\":\"message-1\",\"role\":\"user\"},"
                + "\"parts\":[{\"type\":\"text\",\"text\":\"Choose candidate 1\"}]}");

        assertThat(client.findPromptMessage(session, expected, requestSha256))
                .isEqualTo(new OpenCodeClient.MessageLookup(true, true, requestSha256));

        exactMessageStatusCode.set(404);
        assertThat(client.findPromptMessage(session, expected, requestSha256))
                .isEqualTo(new OpenCodeClient.MessageLookup(true, false, null));
        exactMessageStatusCode.set(410);
        assertThatThrownBy(() -> client.findPromptMessage(session, expected, requestSha256))
                .isInstanceOfSatisfying(SessionFailure.class, failure -> assertThat(failure.code())
                        .isEqualTo("OPENCODE_PROMPT_LOOKUP_FAILED"));

        exactMessageStatusCode.set(200);
        List<String> malformed = List.of(
                "null",
                "[]",
                "{}",
                "{\"info\":{\"id\":1,\"role\":\"user\"},\"parts\":[]}",
                "{\"info\":{\"id\":\"other\",\"role\":\"user\"},\"parts\":[]}",
                "{\"info\":{\"id\":\"message-1\",\"role\":\"assistant\"},\"parts\":[]}",
                "{\"info\":{\"id\":\"message-1\",\"role\":\"user\"},\"parts\":{}}",
                "{\"info\":{\"id\":\"message-1\",\"role\":\"user\"},"
                        + "\"parts\":[{\"type\":\"text\",\"text\":\"Choose candidate 2\"}]}"
        );
        int promptsBefore = promptRequests.get();
        for (String body : malformed) {
            exactMessageBody.set(body);
            assertThatThrownBy(() -> client.findPromptMessage(session, expected, requestSha256))
                    .as("body %s", body)
                    .isInstanceOfSatisfying(SessionFailure.class, failure -> assertThat(failure.code())
                            .isEqualTo("OPENCODE_PROMPT_LOOKUP_INVALID_RESPONSE"));
        }
        assertThatThrownBy(() -> client.findPromptMessage(session, expected, "f".repeat(64)))
                .isInstanceOfSatisfying(SessionFailure.class, failure -> assertThat(failure.code())
                        .isEqualTo("OPENCODE_PROMPT_REQUEST_HASH_MISMATCH"));
        assertThat(promptRequests.get()).isEqualTo(promptsBefore);
    }

    private void session(HttpExchange exchange) throws IOException {
        lastPathAndQuery.set(exchange.getRequestURI().getPath() + "?" + exchange.getRequestURI().getRawQuery());
        sleep(responseDelayMillis.get());
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/session") && "GET".equals(exchange.getRequestMethod())) {
            sessionListRequests.incrementAndGet();
            reply(exchange, sessionListStatusCode.get(), sessionListBody.get());
        }
        else if (path.equals("/session")) {
            createBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String directory = createResponseDirectory.get();
            if (directory == null) directory = worktree.toRealPath().toString();
            if (directory.isEmpty()) reply(exchange, "{\"id\":\"s1\"}");
            else reply(exchange, "{\"id\":\"s1\",\"directory\":\"" + json(directory) + "\"}");
        }
        else if (path.matches("/session/[^/]+/message/[^/]+")) {
            reply(exchange, exactMessageStatusCode.get(), exactMessageBody.get());
        }
        else if (path.endsWith("/message")) reply(exchange, messageStatusCode.get(), messageBody.get());
        else if (path.endsWith("/prompt_async")) { promptRequests.incrementAndGet(); promptBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)); reply(exchange, "true"); }
        else if (path.endsWith("/abort")) reply(exchange, abortStatusCode.get(), abortBody.get());
        else if (path.endsWith("/todo")) reply(exchange, todoBody.get());
        else if (path.endsWith("/fork")) { sessionActionBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)); reply(exchange, "{\"id\":\"fork-1\"}"); }
        else if (path.endsWith("/revert") || path.endsWith("/summarize")) { sessionActionBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)); reply(exchange, "true"); }
        else if (path.endsWith("/diff")) reply(exchange, "[]");
        else reply(exchange, "{}");
    }
    private void question(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/question")) reply(exchange, questionBody.get());
        else {
            questionActionBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            reply(exchange, "true");
        }
    }
    private void permission(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/permission")) reply(exchange, permissionBody.get());
        else {
            permissionActionBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            reply(exchange, "true");
        }
    }
    private void reply(HttpExchange exchange, String body) throws IOException {
        reply(exchange, 200, body);
    }
    private void reply(HttpExchange exchange, int status, String body) throws IOException {
        lastPathAndQuery.set(exchange.getRequestURI().getPath() + "?" + exchange.getRequestURI().getRawQuery());
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes); exchange.close();
    }
    private String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private URI endpoint() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private LoopperProperties properties() {
        LoopperProperties properties = new LoopperProperties();
        properties.getOpenCode().setBaseUrl(endpoint());
        return properties;
    }

    private String sessionList(String title, String id, String directory) {
        return "[{\"id\":\"" + json(id) + "\",\"title\":\"" + json(title)
                + "\",\"directory\":\"" + json(directory) + "\"}]";
    }

    private static void sleep(long delayMillis) throws IOException {
        if (delayMillis <= 0) return;
        try { Thread.sleep(delayMillis); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("test server interrupted", interrupted); }
    }

    private static final class InMemoryRuntimeBindings implements OpenCodeSessionRuntimeBindings {
        private final Map<String, Binding> values = new ConcurrentHashMap<>();

        @Override public void register(Binding binding) { values.put(binding.externalSessionId(), binding); }
        @Override public Optional<Binding> find(String externalSessionId) {
            return Optional.ofNullable(values.get(externalSessionId));
        }
    }
}
