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
    private final AtomicReference<String> messageBody = new AtomicReference<>("[{\"info\":{\"role\":\"user\"}}]");
    private final AtomicReference<String> lastPathAndQuery = new AtomicReference<>();
    private final AtomicReference<String> createBody = new AtomicReference<>();
    private final AtomicLong responseDelayMillis = new AtomicLong();
    @TempDir Path worktree;

    @BeforeEach
    void server() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/session", this::session);
        server.createContext("/session/status", exchange -> reply(exchange, statusBody.get()));
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
        assertThat(createBody.get()).contains("\"permission\"").contains("external_directory").contains("git push *")
                .contains("git reset --hard*").contains("rm -rf*").contains("\"action\":\"deny\"");
        OpenCodeClient.OpenCodeSession judge = client.createReadOnlySession(worktree, "Requirement Judge", new OpenCodeClient.OpenCodeModel("opencode", "deepseek-v4-flash-free", false));
        assertThat(createBody.get()).contains("\"permission\":\"edit\"").contains("\"permission\":\"write\"")
                .contains("\"permission\":\"bash\"").contains("\"permission\":\"task\"").contains("\"pattern\":\"*\"");
        client.promptAsync(session, "hello");
        assertThat(lastPathAndQuery.get()).contains("/session/s1/prompt_async").contains("directory=");
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
        assertThat(retry.failed()).isTrue();
        assertThat(retry.detail()).isEqualTo("Free usage exceeded");
        assertThat(client.diff(session)).isEqualTo("[]");
        assertThat(lastPathAndQuery.get()).contains("/session/s1/diff").contains("directory=");
        client.abort(session);
        assertThat(lastPathAndQuery.get()).contains("/session/s1/abort").contains("directory=");
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
    void exposesIncrementalThinkingOutputAndToolPartsForLiveMonitoring() throws Exception {
        LoopperProperties properties = new LoopperProperties();
        properties.getOpenCode().setBaseUrl(new java.net.URI("http://127.0.0.1:" + server.getAddress().getPort()));
        HttpOpenCodeClient client = new HttpOpenCodeClient(RestClient.builder(), properties);
        OpenCodeClient.OpenCodeSession session = client.createSession(worktree, "monitor", null);
        messageBody.set("["
                + "{\"info\":{\"role\":\"user\"},\"parts\":[{\"type\":\"text\",\"text\":\"prompt\"}]},"
                + "{\"info\":{\"role\":\"assistant\",\"time\":{\"created\":1785836900057}},\"parts\":["
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

    private void session(HttpExchange exchange) throws IOException {
        lastPathAndQuery.set(exchange.getRequestURI().getPath() + "?" + exchange.getRequestURI().getQuery());
        sleep(responseDelayMillis.get());
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/session")) { createBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)); reply(exchange, "{\"id\":\"s1\"}"); }
        else if (path.endsWith("/message")) reply(exchange, messageBody.get());
        else if (path.endsWith("/diff")) reply(exchange, "[]");
        else reply(exchange, "{}");
    }
    private void reply(HttpExchange exchange, String body) throws IOException {
        lastPathAndQuery.set(exchange.getRequestURI().getPath() + "?" + exchange.getRequestURI().getQuery());
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes); exchange.close();
    }

    private static void sleep(long delayMillis) throws IOException {
        if (delayMillis <= 0) return;
        try { Thread.sleep(delayMillis); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("test server interrupted", interrupted); }
    }
}
