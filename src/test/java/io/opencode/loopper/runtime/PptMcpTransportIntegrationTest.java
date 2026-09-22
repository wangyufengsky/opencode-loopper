package io.opencode.loopper.runtime;

import com.sun.net.httpserver.HttpServer;
import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.persistence.PptAgentMapper;
import io.opencode.loopper.service.ppt.PptDocuments;
import io.opencode.loopper.service.ppt.agent.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

/** Real HTTP adapter, private MCP server and PPT business/engine; only OpenCode is simulated. */
@SpringBootTest(classes=LoopperApplication.class, webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"loopper.opencode.mode=fake", "loopper.scheduling.enabled=false", "loopper.startup-recovery.enabled=false"})
class PptMcpTransportIntegrationTest {
    private static final Path DATA = data();
    private static Path data() { try { return Files.createTempDirectory("ppt-mcp-wire-"); } catch(Exception e) { throw new IllegalStateException(e); } }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("loopper.data-dir", DATA::toString);
        r.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATA.resolve("test.db") + "?foreign_keys=on&journal_mode=WAL&transaction_mode=IMMEDIATE");
    }
    @LocalServerPort int port;
    @Autowired Flyway flyway;
    @Autowired PptDocuments documents;
    @Autowired PptAgentService agent;
    @Autowired PptAgentPersistence persistence;
    @Autowired PptAgentMapper mapper;
    @Autowired PptAgentWorkspace workspace;
    @Autowired PptAgentAuthority authority;
    @Autowired io.opencode.loopper.service.ppt.PptEvents events;
    @Autowired PptRuntimeSupport scopes;
    @Autowired InternalMcpRuntimeAccess runtime;
    @Autowired OpenCodeSessionRuntimeBindings bindings;
    @Autowired AssistRuntimeSupport assist;
    @Autowired LoopperProperties properties;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @MockitoBean PptAgentCoordinator scheduler;
    HttpServer server;
    PptAgentCoordinator coordinator;
    InternalMcpCredentialProvider.Credentials credentials;
    String document, runId, mcpSession;
    Path directory;
    final AtomicReference<JsonNode> prompt = new AtomicReference<>();
    final AtomicReference<JsonNode> creation = new AtomicReference<>();
    @BeforeEach void setup() throws Exception {
        flyway.clean(); flyway.migrate(); document = UUID.randomUUID().toString();
        documents.create(new PptDocuments.Create(document, "PPT MCP 实际通道", null, "fake/model"));
        jdbc.update("UPDATE ppt_document SET phase='DESIGN' WHERE id=?", document); // Fixture phase; all edits below use real business APIs.
        directory = workspace.workspace(document).root();
        credentials = new InternalMcpCredentialProvider(() -> port).issue(); runtime.activate(credentials);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            Object body; String path = exchange.getRequestURI().getPath();
            if (path.equals("/mcp")) body = Map.of(credentials.serverName(), Map.of("status", "connected"));
            else if (path.equals("/session") && exchange.getRequestMethod().equals("POST")) {
                creation.set(json.readTree(exchange.getRequestBody())); body = Map.of("id", "ses_ppt_wire", "directory", directory.toString());
            } else if (path.endsWith("/prompt_async")) { prompt.set(json.readTree(exchange.getRequestBody())); body = Map.of(); }
            else if (path.endsWith("/abort")) body = true;
            else { exchange.sendResponseHeaders(404, -1); exchange.close(); return; }
            byte[] bytes = json.writeValueAsBytes(body); exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start(); var endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        var remote = new HttpOpenCodeClient(RestClient.builder(), () -> new OpenCodeRuntimeManager.Connection(endpoint, null, null, true,
                credentials.generation(), credentials.serverName()), properties, new OpenCodeCapabilityRegistry(), bindings);
        remote.installAssist(assist); remote.installPpt(scopes);
        coordinator = new PptAgentCoordinator(mapper, persistence, remote, json, properties, workspace, authority, events);
        runId = agent.send(document, new PptAgentService.Send(UUID.randomUUID().toString(), "制作项目收益页", 0,
                json.valueToTree(Map.of("kind", "DOCUMENT")))).id();
        coordinator.tick(document); coordinator.tick(document);
        assertThat(persistence.require(runId).state()).isEqualTo("RUNNING");
    }
    @AfterEach void close() { if (coordinator != null) coordinator.close(); if (server != null) server.stop(0); }
    @Test void actualPptRoleScopeAndMcpCreateEditableObjectsThenCorrectRejectedOperation() throws Exception {
        String scope = transmittedScope(); assertThat(scope).startsWith("lpp_");
        assertThat(prompt.get().path("agent").asText()).isEqualTo(PptAgentProfile.AGENT);
        assertThat(persistence.require(runId).requestJson()).doesNotContain(scope, credentials.bearerToken());
        assertThat(creation.get().path("permission").valueStream().filter(v -> v.path("action").asText().equals("allow"))
                .map(v -> v.path("permission").asText())).allMatch(v -> v.startsWith(credentials.serverName() + "_ppt_"));
        var capability = call("ppt_get_capabilities", Map.of());
        assertThat(capability.path("isError").asBoolean()).isFalse();
        var rejected = call("ppt_apply_operations", Map.of("idempotencyKey", "invalid-batch", "expectedRevision", 0,
                "operations", List.of(Map.of("op", "remove_element", "slideId", "missing", "elementId", "missing"))));
        assertThat(rejected.path("isError").asBoolean()).isTrue();
        assertThat(rejected.path("structuredContent").path("action").asText()).isEqualTo("FIX_AND_RESUBMIT");
        assertThat(documents.get(document).revision()).isZero();
        var params = Map.<String,Object>of("idempotencyKey", "create-batch-1", "expectedRevision", 0,
                "operations", List.of(Map.of("op", "create_slide", "slide", Map.of("id", "s1", "title", "项目收益")),
                        Map.of("op", "add_element", "slideId", "s1", "element", Map.of("id", "e1", "type", "text", "x", 40, "y", 30,
                                "width", 800, "height", 60, "text", "减少重复劳动"))));
        var accepted = call("ppt_apply_operations", params);
        assertThat(accepted.path("isError").asBoolean()).as(accepted.toString()).isFalse();
        assertThat(documents.get(document).revision()).isEqualTo(1);
        assertThat(documents.deck(document, null).slides().getFirst().elements().getFirst().text()).isEqualTo("减少重复劳动");
        assertThat(call("ppt_apply_operations", params).path("structuredContent")).isEqualTo(accepted.path("structuredContent"));
        assertThat(documents.get(document).revision()).isEqualTo(1);
        agent.stop(document); coordinator.tick(document);
        assertThat(persistence.require(runId).state()).isEqualTo("STOPPED");
        assertThat(call("ppt_apply_operations", params).path("isError").asBoolean()).isFalse();
        assertThat(call("ppt_apply_operations", Map.of("idempotencyKey", "late-batch-1", "expectedRevision", 1,
                "operations", List.of(Map.of("op", "delete_slide", "slideId", "s1")))).path("isError").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task", Integer.class)).isZero();
    }
    @Test void capabilityCannotOperateAnotherDocumentOrReadAfterGenerationChange() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            initialize(client);
            var args = new HashMap<>(envelope(Map.of())); args.put("documentId", UUID.randomUUID().toString());
            var result = result(send(client, "tools/call", Map.of("name", "ppt_get_context", "arguments", args)));
            assertThat(result.path("isError").asBoolean()).isTrue();
            assertThat(result.path("structuredContent").path("errorCode").asText()).isEqualTo("PPT_SCOPE_DENIED");
            runtime.activate(new InternalMcpCredentialProvider(() -> port).issue());
            assertThat(send(client, "tools/call", Map.of("name", "ppt_get_context", "arguments", envelope(Map.of()))).statusCode()).isEqualTo(401);
        }
    }
    private String transmittedScope() {
        var matcher = Pattern.compile("scope=(lpp_[A-Za-z0-9_.-]+)").matcher(prompt.get().path("system").asText());
        return matcher.find() ? matcher.group(1) : "";
    }
    private Map<String,Object> envelope(Map<String,Object> args) {
        return Map.of("scope", transmittedScope(), "runId", runId, "documentId", document, "args", args);
    }
    private JsonNode call(String tool, Map<String,Object> args) throws Exception {
        try (var client = HttpClient.newHttpClient()) { initialize(client); return result(send(client, "tools/call", Map.of("name", tool, "arguments", envelope(args)))); }
    }
    private void initialize(HttpClient client) throws Exception {
        if (mcpSession != null) return;
        mcpSession = send(client, "initialize", Map.of("protocolVersion", "2025-03-26", "capabilities", Map.of(),
                "clientInfo", Map.of("name", "ppt-wire-test", "version", "1"))).headers().firstValue("Mcp-Session-Id").orElseThrow();
    }
    private JsonNode result(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(200); String body = response.body();
        if (!body.stripLeading().startsWith("{")) body = body.lines().filter(v -> v.startsWith("data:"))
                .map(v -> v.substring(5).strip()).filter(v -> v.contains("result")).findFirst().orElseThrow();
        return json.readTree(body).path("result");
    }
    private HttpResponse<String> send(HttpClient client, String method, Map<String,Object> params) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + InternalMcpContractCatalog.ENDPOINT_PATH))
                .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + credentials.bearerToken())
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream");
        if (mcpSession != null) request.header("Mcp-Session-Id", mcpSession);
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 1,
                "method", method, "params", params)), StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
