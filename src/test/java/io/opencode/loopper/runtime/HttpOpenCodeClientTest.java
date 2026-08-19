package io.opencode.loopper.runtime;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.SessionFailure;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
    private final AtomicReference<String> promptBody = new AtomicReference<>();
    private final AtomicReference<String> createResponseDirectory = new AtomicReference<>();
    private final AtomicLong responseDelayMillis = new AtomicLong();
    @TempDir Path worktree;

    @BeforeEach
    void server() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/session", this::session);
        server.createContext("/session/status", exchange -> reply(exchange, statusBody.get()));
        server.createContext("/global/health", exchange -> reply(exchange, healthBody.get()));
        server.createContext("/question", this::question);
        server.createContext("/permission", this::permission);
        server.createContext("/experimental/tool/ids", exchange -> reply(exchange, "[\"read\",\"todowrite\"]"));
        server.createContext("/agent", exchange -> reply(exchange,
                "[{\"name\":\"build\",\"mode\":\"primary\"},{\"name\":\"plan\",\"mode\":\"primary\"}]"));
        server.start();
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
        client.abort(session);
        assertThat(lastPathAndQuery.get()).contains("/session/s1/abort").contains("directory=");
    }

    @Test
    void routerHasNoToolsReviewerIsReadOnlyAndImplementationDeniesMaintenanceEscapes() throws Exception {
        LoopperProperties properties = new LoopperProperties();
        properties.getOpenCode().setBaseUrl(new java.net.URI("http://127.0.0.1:" + server.getAddress().getPort()));
        HttpOpenCodeClient client = new HttpOpenCodeClient(RestClient.builder(), properties);

        client.createSession(worktree, "router", null, OpenCodeClient.SessionProfile.ROUTER_NO_TOOLS);
        assertThat(createBody.get()).contains("\"permission\":\"*\"").contains("\"action\":\"deny\"")
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
                () -> new OpenCodeRuntimeManager.Connection(endpoint, "", "", true));
        OpenCodeClient.OpenCodeSession session = client.createSession(worktree, "decomposer", null,
                OpenCodeClient.SessionProfile.DECOMPOSER_READ_ONLY);

        client.promptAsync(session, OpenCodeClient.PromptRequest.text("decompose"));

        assertThat(promptBody.get()).contains("\"agent\":\"loopper-structured\"");
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
                + "{\"info\":{\"id\":\"message-assistant\",\"role\":\"assistant\",\"time\":{\"created\":1785836900057,\"completed\":1785836902200}},\"parts\":["
                + "{\"id\":\"reason-1\",\"type\":\"reasoning\",\"text\":\"Inspecting the project\",\"time\":{\"start\":1785836901408}},"
                + "{\"id\":\"tool-1\",\"type\":\"tool\",\"tool\":\"read\",\"state\":{\"status\":\"completed\",\"title\":\"Read pom.xml\",\"time\":{\"start\":1785836902020}}},"
                + "{\"id\":\"text-1\",\"type\":\"text\",\"text\":\"Implementation is in progress\",\"time\":{\"start\":1785836902100}}]}]");

        OpenCodeClient.SessionTranscript transcript = client.sessionTranscript(session);

        assertThat(transcript.parts()).extracting(OpenCodeClient.SessionPart::type)
                .containsExactly("THINKING", "TOOL", "OUTPUT");
        assertThat(transcript.parts().get(0).content()).isEqualTo("Inspecting the project");
        assertThat(transcript.parts().get(1).label()).isEqualTo("read");
        assertThat(transcript.parts().get(2).content()).isEqualTo("Implementation is in progress");
        assertThat(transcript.parts()).extracting(OpenCodeClient.SessionPart::startedAt)
                .containsExactly("2026-08-04T09:48:21.408Z", "2026-08-04T09:48:22.020Z", "2026-08-04T09:48:22.100Z");
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

    private void session(HttpExchange exchange) throws IOException {
        lastPathAndQuery.set(exchange.getRequestURI().getPath() + "?" + exchange.getRequestURI().getRawQuery());
        sleep(responseDelayMillis.get());
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/session")) {
            createBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String directory = createResponseDirectory.get();
            if (directory == null) directory = worktree.toRealPath().toString();
            if (directory.isEmpty()) reply(exchange, "{\"id\":\"s1\"}");
            else reply(exchange, "{\"id\":\"s1\",\"directory\":\"" + json(directory) + "\"}");
        }
        else if (path.endsWith("/message")) reply(exchange, messageStatusCode.get(), messageBody.get());
        else if (path.endsWith("/prompt_async")) { promptBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)); reply(exchange, "true"); }
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

    private static void sleep(long delayMillis) throws IOException {
        if (delayMillis <= 0) return;
        try { Thread.sleep(delayMillis); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("test server interrupted", interrupted); }
    }
}
