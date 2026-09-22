package io.opencode.loopper.runtime;

import com.sun.net.httpserver.HttpServer;
import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.persistence.AssistMapper;
import io.opencode.loopper.persistence.KnowledgeMapper;
import io.opencode.loopper.service.KnowledgeEventHub;
import io.opencode.loopper.service.ProjectService;
import io.opencode.loopper.service.assist.AssistToolCatalog;
import io.opencode.loopper.service.knowledge.KnowledgeConversations;
import io.opencode.loopper.service.knowledge.KnowledgeCoordinator;
import io.opencode.loopper.service.knowledge.KnowledgeQuestions;
import io.opencode.loopper.service.knowledge.KnowledgePersistence;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** Real coordinator, HTTP adapter, persisted grants and MCP wire; only OpenCode is simulated. */
@SpringBootTest(classes = LoopperApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"loopper.opencode.mode=fake", "loopper.scheduling.enabled=false", "loopper.startup-recovery.enabled=false"})
class KnowledgeMcpTransportIntegrationTest {
    private static final Path DATA = data();
    private static Path data() {
        try { return Files.createTempDirectory("knowledge-mcp-transport-"); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("loopper.data-dir", DATA::toString);
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATA.resolve("test.db") + "?foreign_keys=on&journal_mode=WAL&transaction_mode=IMMEDIATE");
    }
    @LocalServerPort int port;
    @Autowired io.opencode.loopper.persistence.KnowledgeV2Mapper v2;
    @Autowired Flyway flyway;
    @Autowired ProjectService projects;
    @Autowired KnowledgeConversations conversations;
    @Autowired KnowledgePersistence persistence;
    @Autowired KnowledgeMapper knowledge;
    @Autowired io.opencode.loopper.persistence.KnowledgeResearchMapper researchMapper;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired AssistMapper assistMapper;
    @Autowired AssistRuntimeSupport assist;
    @Autowired InternalMcpRuntimeAccess runtime;
    @Autowired OpenCodeSessionRuntimeBindings bindings;
    @Autowired LoopperProperties properties;
    @Autowired KnowledgeEventHub events;
    @Autowired ObjectMapper json;
    @MockitoBean OpenCodeToolInventory inventory;
    @TempDir Path root;
    private HttpServer server;
    private HttpOpenCodeClient remote;
    private KnowledgeCoordinator coordinator;
    private InternalMcpCredentialProvider.Credentials credentials;
    private String project;
    private String mcpSession;
    private final AtomicBoolean nativeQuestion = new AtomicBoolean();
    private final AtomicInteger replyRequests = new AtomicInteger();
    private final AtomicReference<JsonNode> replyBody = new AtomicReference<>();
    private final AtomicReference<JsonNode> sentPrompt = new AtomicReference<>();
    private final AtomicReference<JsonNode> sessionRequest = new AtomicReference<>();
    private final AtomicBoolean loseCreateResponse = new AtomicBoolean();
    private final AtomicInteger createRequests = new AtomicInteger();

    @BeforeEach void prepare() throws Exception {
        flyway.clean(); flyway.migrate(); root = root.toRealPath();
        Files.writeString(root.resolve("README.md"), "# 项目模块\n包含知识库与任务模块。\n");
        project = projects.create("知识库 MCP 传输验收", root.toString()).id();
        credentials = new InternalMcpCredentialProvider(() -> port).issue(); runtime.activate(credentials);
        when(inventory.inventory(any())).thenReturn(new OpenCodeToolInventory.Inventory(
                List.of(new OpenCodeToolInventory.Server(AssistToolCatalog.SERVER, "辅助工具", "connected", "remote")), "", true));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            Object response;
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/mcp")) response = Map.of(credentials.serverName(), Map.of("status", "connected"),
                    AssistToolCatalog.serverName(credentials.serverName()), Map.of("status", "connected"));
            else if (path.equals("/experimental/tool/ids")) response = nativeQuestion.get() ? List.of("question") : List.of();
            else if (path.equals("/question")) response = nativeQuestion.get() ? List.of(
                    Map.of("id", "question-current", "sessionID", "ses_knowledge_transport", "tool", Map.of("messageID", "assistant-current"),
                        "questions", List.of(Map.of("question", "请选择作者", "options", List.of(Map.of("label", "张三", "description", "研发"))))),
                    Map.of("id", "question-old", "sessionID", "ses_knowledge_transport", "tool", Map.of("messageID", "assistant-old"),
                        "questions", List.of(Map.of("question", "旧轮次问题", "options", List.of())))) : List.of();
            else if (path.endsWith("/message")) response = List.of(
                    Map.of("info", Map.of("id", "assistant-current", "role", "assistant", "parentID", sentPrompt.get().path("messageID").asText()), "parts", List.of()),
                    Map.of("info", Map.of("id", "assistant-old", "role", "assistant", "parentID", "old-prompt"), "parts", List.of()));
            else if (path.equals("/question/question-current/reply")) { replyRequests.incrementAndGet(); replyBody.set(json.readTree(exchange.getRequestBody())); nativeQuestion.set(false); response = Map.of(); }
            else if (path.equals("/session") && exchange.getRequestMethod().equals("POST")) {
                createRequests.incrementAndGet();
                sessionRequest.set(json.readTree(exchange.getRequestBody()));
                response = Map.of("id", "ses_knowledge_transport", "directory", root.toString());
                if (loseCreateResponse.getAndSet(false)) { exchange.sendResponseHeaders(503, -1); exchange.close(); return; }
            } else if (path.equals("/session") && exchange.getRequestMethod().equals("GET")) {
                response = List.of(Map.of("id", "ses_knowledge_transport", "directory", root.toString(),
                        "title", sessionRequest.get().path("title").asText(), "permission", sessionRequest.get().path("permission")));
            } else if (path.endsWith("/prompt_async")) {
                sentPrompt.set(json.readTree(exchange.getRequestBody())); response = Map.of();
            } else throw new AssertionError("Unexpected OpenCode request: " + path);
            byte[] bytes = json.writeValueAsBytes(response);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        var endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        remote = new HttpOpenCodeClient(RestClient.builder(), () -> new OpenCodeRuntimeManager.Connection(
                endpoint, null, null, true, credentials.generation(), credentials.serverName()), properties,
                new OpenCodeCapabilityRegistry(), bindings);
        remote.installAssist(assist);
        coordinator = new KnowledgeCoordinator(knowledge, persistence, remote, json, properties, events, new KnowledgeQuestions(v2, knowledge, remote, json, events), v2, new io.opencode.loopper.service.knowledge.KnowledgeResearch(researchMapper, knowledge, remote, json));
    }
    @AfterEach void close() {
        if (coordinator != null) coordinator.close();
        if (server != null) server.stop(0);
        if (credentials != null) runtime.clear(credentials.generation());
    }

    @Test void freshAttestedKnowledgeSessionCanReadSourcesThroughMcpWithTransportIssuedScope() throws Exception {
        var chat = create();
        persistence.begin(chat.id(), UUID.randomUUID().toString(), "这个项目有几个模块？");
        coordinator.tick(chat.id()); coordinator.tick(chat.id());
        assertTransportRead(chat);
        assertThat(sentPrompt.get().path("system").asText()).contains("先使用已授权 MCP", "必须先用已授权 MCP 查询并读取原文", "只有 MCP 查不到")
                .doesNotContain("偏好而非必经流程", "可自主选用更直接");
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/projects/" + project
                    + "/knowledge-sources/search?conversationId=" + chat.id() + "&query=README&mode=AUTO")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200); var body = json.readTree(response.body());
            assertThat(body.path("matches").size()).isEqualTo(1); assertThat(body.path("coverage").size()).isEqualTo(2);
        }
    }

    @Test void researchSessionCanUseNativeToolsWhenKnowledgeMcpIsDisconnected() {
        var chat = conversations.create(new KnowledgeConversations.Create(UUID.randomUUID().toString(), project,
                "原生调查", "fake/model", List.of("code")));
        when(inventory.inventory(any())).thenReturn(new OpenCodeToolInventory.Inventory(List.of(), "offline", true));
        persistence.begin(chat.id(), UUID.randomUUID().toString(), "直接使用原生工具调查项目实现");
        coordinator.tick(chat.id()); coordinator.tick(chat.id());
        assertThat(sentPrompt.get()).isNotNull();
        assertThat(sentPrompt.get().path("system").asText()).contains("原生 read", "不要因此停止整个调查");
        assertThat(sentPrompt.get().path("system").asText()).doesNotContain("先使用 list_knowledge_sources");
        assertThat(sessionRequest.get().path("permission").valueStream().filter(n -> n.path("action").asText().equals("allow"))
                .map(n -> n.path("permission").asText()).toList()).contains("read", "glob", "grep", "todowrite");
        assertThat(sentPrompt.get().path("tools").valueStream().allMatch(n -> n.isBoolean() && !n.asBoolean())).isTrue();
        assertThat(knowledge.active(chat.id()).orElseThrow().state()).isEqualTo("RUNNING");
    }
    @Test void exactTitleRecoveryRegistersTheSameKnowledgeScopeBeforeDispatch() throws Exception {
        var chat = create();
        loseCreateResponse.set(true);
        persistence.begin(chat.id(), UUID.randomUUID().toString(), "这个项目有几个模块？");
        coordinator.tick(chat.id());
        assertThat(knowledge.active(chat.id()).orElseThrow().state()).isEqualTo("CREATE_UNKNOWN");
        coordinator.tick(chat.id());
        coordinator.tick(chat.id());
        assertThat(createRequests).hasValue(1);
        assertTransportRead(chat);
    }
    @Test void missingSnapshotStopsBeforeModelDispatchAndDoesNotLeaveAnUnknownTurn() {
        var chat = create();
        persistence.begin(chat.id(), UUID.randomUUID().toString(), "问题"); coordinator.tick(chat.id());
        jdbc.update("DELETE FROM assist_session WHERE external_session_id=?", "ses_knowledge_transport");
        coordinator.tick(chat.id());
        assertThat(sentPrompt.get()).isNull();
        assertThat(knowledge.active(chat.id())).isEmpty();
        assertThat(conversations.get(chat.id()).state()).isEqualTo("IDLE");
        assertThat(conversations.messages(chat.id(), null, 50).items()).singleElement().satisfies(message -> {
            assertThat(message.state()).isEqualTo("FAILED"); assertThat(message.detail()).contains("问题未发送");
        });
    }
    @Test void issuedScopeCannotOutliveStopOrGenerationOrAccessUnrelatedTools() throws Exception {
        var chat = create();
        persistence.begin(chat.id(), UUID.randomUUID().toString(), "问题"); coordinator.tick(chat.id()); coordinator.tick(chat.id());
        String scope = transmittedScope();
        assertThat(scope).isNotBlank();
        assertThat(call("list_knowledge_sources", Map.of("scope", scope)).path("isError").asBoolean()).isFalse();
        assertThat(call("get_execution_context", Map.of("scope", scope)).path("structuredContent").path("code").asText()).isEqualTo("ASSIST_SCOPE_DENIED");
        assertThat(call("list_knowledge_sources", Map.of("scope", scope + "tampered")).path("structuredContent").path("code").asText()).isEqualTo("ASSIST_SCOPE_DENIED");
        persistence.stop(chat.id());
        assertThat(call("search_project_knowledge", Map.of("scope", scope, "query", "知识库")).path("structuredContent").path("code").asText()).isEqualTo("ASSIST_SCOPE_DENIED");
        assertThat(call("list_knowledge_sources", Map.of("scope", scope)).path("structuredContent").path("code").asText()).isEqualTo("ASSIST_SCOPE_DENIED");
        persistence.finish(knowledge.active(chat.id()).orElseThrow(), "STOPPED", "已停止");
        assertThat(call("list_knowledge_sources", Map.of("scope", scope)).path("structuredContent").path("code").asText()).isEqualTo("ASSIST_SCOPE_DENIED");
        persistence.begin(chat.id(), UUID.randomUUID().toString(), "追问"); coordinator.tick(chat.id());
        assertThat(call("list_knowledge_sources", Map.of("scope", transmittedScope())).path("isError").asBoolean()).isFalse();
        credentials = new InternalMcpCredentialProvider(() -> port).issue(); runtime.activate(credentials); mcpSession = null;
        assertThat(call("list_knowledge_sources", Map.of("scope", scope)).path("structuredContent").path("code").asText()).isEqualTo("ASSIST_SCOPE_DENIED");
    }
    @Test void unifiedToolNeverExpandsAnExistingFrozenToolGrant() throws Exception {
        var chat = create(); persistence.begin(chat.id(), UUID.randomUUID().toString(), "问题"); coordinator.tick(chat.id()); coordinator.tick(chat.id());
        var old = AssistToolCatalog.allowed("KNOWLEDGE_READ_ONLY").stream().filter(t -> !t.equals("search_project_knowledge")).toList();
        jdbc.update("UPDATE assist_session SET tools_json=? WHERE external_session_id=?", json.writeValueAsString(old), "ses_knowledge_transport");
        assertThat(call("search_project_knowledge", Map.of("scope", transmittedScope(), "query", "知识库")).path("structuredContent").path("code").asText()).isEqualTo("ASSIST_SCOPE_DENIED");
        assertThat(call("search_knowledge", Map.of("scope", transmittedScope(), "sourceId", "code", "query", "知识库")).path("isError").asBoolean()).isFalse();
    }
    @Test void disconnectedAuxiliaryMcpIsAnUnsentFailureInsteadOfAnUnknownModelRequest() {
        var chat = create();
        persistence.begin(chat.id(), UUID.randomUUID().toString(), "问题"); coordinator.tick(chat.id());
        when(inventory.inventory(any())).thenReturn(new OpenCodeToolInventory.Inventory(List.of(), "", true));
        coordinator.tick(chat.id());
        assertThat(sentPrompt.get()).isNull();
        assertThat(knowledge.active(chat.id())).isEmpty();
        assertThat(conversations.get(chat.id()).state()).isEqualTo("IDLE");
        assertThat(conversations.messages(chat.id(), null, 50).items()).singleElement().satisfies(message -> {
            assertThat(message.state()).isEqualTo("FAILED"); assertThat(message.detail()).contains("MCP 尚未连接");
        });
    }
    @Test void nativeQuestionUsesExactCurrentMessageAndReplyTransportAfterDurableReceipt() throws Exception {
        nativeQuestion.set(true);
        var chat = create(); persistence.begin(chat.id(), UUID.randomUUID().toString(), "张三昨天做了什么？");
        coordinator.tick(chat.id()); coordinator.tick(chat.id()); coordinator.tick(chat.id());
        assertThat(assistMapper.session("ses_knowledge_transport").profile()).isEqualTo("KNOWLEDGE_INTERACTIVE_READ_ONLY");
        var turn = knowledge.active(chat.id()).orElseThrow();
        var pending = conversations.message(turn).questions();
        assertThat(pending).singleElement().satisfies(q -> assertThat(q.questions().getFirst().question()).isEqualTo("请选择作者"));
        assertThat(sessionRequest.get().path("permission").toString()).contains("question").doesNotContain("bash");
        var reply = new KnowledgeQuestions.Reply(UUID.randomUUID().toString(), List.of(List.of("张三")), pending.getFirst().version());
        var interactions = new KnowledgeQuestions(v2, knowledge, remote, json, events);
        interactions.reply(chat.id(), pending.getFirst().id(), reply);
        assertThat(replyRequests).hasValue(0);
        coordinator.tick(chat.id()); interactions.reply(chat.id(), pending.getFirst().id(), reply);
        assertThat(replyRequests).hasValue(1); assertThat(replyBody.get().path("answers").get(0).get(0).asText()).isEqualTo("张三");
        assertThat(conversations.message(turn).questions().getFirst().state()).isEqualTo("ANSWERED");
        assertThat(call("list_knowledge_sources", Map.of("scope", transmittedScope())).path("isError").asBoolean()).isFalse();
    }
    @Test void frozenGitSourceIsAvailableThroughTheRealScopedMcpTransport() throws Exception {
        for (var args : List.of(List.of("git", "init", "-q"), List.of("git", "add", "README.md"),
                List.of("git", "-c", "user.name=测试作者", "-c", "user.email=fixture@example.test", "commit", "-q", "-m", "新增知识模块"))) {
            var process = new ProcessBuilder(args).directory(root.toFile()).redirectErrorStream(true).start();
            assertThat(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); assertThat(process.exitValue()).isZero();
        }
        var chat = conversations.create(new KnowledgeConversations.Create(UUID.randomUUID().toString(), project, "Git 查询", "fake/model", List.of("git")));
        persistence.begin(chat.id(), UUID.randomUUID().toString(), "谁提交了知识模块？"); coordinator.tick(chat.id()); coordinator.tick(chat.id());
        var result = call("search_knowledge_git_commits", Map.of("scope", transmittedScope(), "sourceId", "git", "author", "测试作者"));
        assertThat(result.path("isError").asBoolean()).isFalse();
        String citation = result.path("structuredContent").path("citationId").asText();
        assertThat(citation).isNotBlank(); assertThat(conversations.citation(chat.id(), citation).toString()).contains("新增知识模块");
    }
    private void assertTransportRead(KnowledgeConversations.View chat) throws Exception {
        assertThat(sentPrompt.get()).as("question reached the real HTTP adapter").isNotNull();
        String scope = transmittedScope();
        var sources = call("list_knowledge_sources", Map.of("scope", scope));
        assertThat(sources.path("isError").asBoolean()).as("MCP rejection: " + sources.path("structuredContent").path("code").asText()).isFalse();
        var search = call("search_project_knowledge", Map.of("scope", scope, "query", "知识库", "mode", "AUTO"));
        assertThat(search.path("isError").asBoolean()).as(search.toString()).isFalse();
        var body = search.path("structuredContent"); assertThat(body.path("matches").size()).isEqualTo(1);
        assertThat(body.path("coverage").size()).isEqualTo(2); assertThat(body.has("citationId")).isFalse();
        assertThat(knowledge.citationCount(knowledge.active(chat.id()).orElseThrow().id())).isZero();
        var arguments = json.convertValue(body.path("matches").get(0).path("read").path("arguments"), new tools.jackson.core.type.TypeReference<Map<String,Object>>() { });
        arguments.put("scope", scope);
        var read = call(body.path("matches").get(0).path("read").path("tool").asText(), arguments);
        assertThat(read.path("isError").asBoolean()).isFalse();
        String citation = read.path("structuredContent").path("citationId").asText();
        assertThat(citation).isNotBlank();
        assertThat(conversations.citation(chat.id(), citation).toString()).contains("知识库与任务模块");
        assertThat(assistMapper.session("ses_knowledge_transport").profile()).isEqualTo("KNOWLEDGE_READ_ONLY");
        assertThat(knowledge.active(chat.id()).orElseThrow().requestJson()).doesNotContain(scope);
    }
    private KnowledgeConversations.View create() {
        var view = conversations.create(new KnowledgeConversations.Create(UUID.randomUUID().toString(), project,
                "模块问题", "fake/model", List.of("code", "documents")));
        jdbc.update("UPDATE knowledge_conversation_options SET contract_version=2 WHERE conversation_id=?", view.id());
        return conversations.get(view.id());
    }
    private String transmittedScope() {
        var matcher = Pattern.compile("scope=(lpa_[A-Za-z0-9_.-]+)").matcher(sentPrompt.get().path("system").asText());
        return matcher.find() ? matcher.group(1) : "";
    }
    private JsonNode call(String tool, Map<String, Object> arguments) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            if (mcpSession == null) {
                var initialized = send(client, "initialize", Map.of("protocolVersion", "2025-03-26", "capabilities", Map.of(),
                        "clientInfo", Map.of("name", "knowledge-transport-regression", "version", "1")));
                mcpSession = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
            }
            var response = send(client, "tools/call", Map.of("name", tool, "arguments", arguments));
            assertThat(response.statusCode()).isEqualTo(200);
            String body = response.body();
            if (!body.stripLeading().startsWith("{")) body = body.lines().filter(line -> line.startsWith("data:"))
                    .map(line -> line.substring(5).strip()).filter(line -> line.contains("result")).findFirst().orElseThrow();
            return json.readTree(body).path("result");
        }
    }
    private HttpResponse<String> send(HttpClient client, String method, Map<String, Object> params) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + AssistToolCatalog.ENDPOINT))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + credentials.bearerToken())
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream");
        if (mcpSession != null) request.header("Mcp-Session-Id", mcpSession);
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(
                Map.of("jsonrpc", "2.0", "id", 1, "method", method, "params", params)), StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
