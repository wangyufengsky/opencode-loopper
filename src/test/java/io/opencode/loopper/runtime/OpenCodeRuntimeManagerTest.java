package io.opencode.loopper.runtime;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.opencode.loopper.config.LoopperProperties;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenCodeRuntimeManagerTest {
    private final List<HttpServer> servers = new ArrayList<>();
    @TempDir Path temporaryDirectory;

    @AfterEach
    void stopServers() { servers.forEach(server -> server.stop(0)); }

    @Test
    void autoReusesHealthyLoopbackWithoutTakingOwnership() throws Exception {
        HttpServer server = healthServer(0, null);
        LoopperProperties properties = properties("auto", URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
        AtomicInteger starts = new AtomicInteger();
        OpenCodeRuntimeManager manager = new OpenCodeRuntimeManager(properties, (command, environment) -> {
            starts.incrementAndGet(); throw new AssertionError("healthy configured loopback must be reused");
        }, Clock.systemUTC());

        OpenCodeRuntimeManager.RuntimeSnapshot status = manager.status();

        assertThat(status.status()).isEqualTo("AVAILABLE");
        assertThat(status.managed()).isFalse();
        assertThat(status.version()).isEqualTo("1.18.12");
        assertThat(starts).hasValue(0);
        assertThat(manager.restartable()).isFalse();
        manager.close();
    }

    @Test
    void autoStartsAuthenticatedOwnedProcessAndOnlyStopsThatProcess() throws Exception {
        Path executable = executable();
        LoopperProperties properties = properties("auto", URI.create("http://127.0.0.1:1"));
        properties.getOpenCode().setExecutable(executable.toString());
        properties.getOpenCode().setModel("deepseek/deepseek-v4-flash");
        AtomicReference<Map<String, String>> capturedEnvironment = new AtomicReference<>();
        AtomicReference<List<String>> capturedCommand = new AtomicReference<>();
        List<FakeProcess> processes = new ArrayList<>();
        OpenCodeRuntimeManager manager = new OpenCodeRuntimeManager(properties, (command, environment) -> {
            capturedCommand.set(command); capturedEnvironment.set(Map.copyOf(environment));
            int port = Integer.parseInt(command.get(command.indexOf("--port") + 1));
            String expectedAuthorization = "Basic " + java.util.Base64.getEncoder().encodeToString((environment.get("OPENCODE_SERVER_USERNAME") + ":" + environment.get("OPENCODE_SERVER_PASSWORD")).getBytes(StandardCharsets.UTF_8));
            healthServer(port, expectedAuthorization);
            FakeProcess process = new FakeProcess(6200 + processes.size());
            processes.add(process);
            return process;
        }, Clock.systemUTC());

        OpenCodeRuntimeManager.Connection connection = manager.connectionForClient();
        OpenCodeRuntimeManager.RuntimeSnapshot started = manager.status();

        assertThat(connection.managed()).isTrue();
        assertThat(started.status()).isEqualTo("AVAILABLE");
        assertThat(started.managed()).isTrue();
        assertThat(started.pid()).isEqualTo(6200L);
        assertThat(capturedCommand.get()).containsExactly(executable.toString(), "serve", "--hostname", "127.0.0.1", "--port", capturedCommand.get().get(5));
        assertThat(capturedEnvironment.get())
                .containsEntry("OPENCODE_ENABLE_QUESTION_TOOL", "true")
                .containsKeys("OPENCODE_SERVER_USERNAME", "OPENCODE_SERVER_PASSWORD", "OPENCODE_CONFIG_CONTENT");
        assertThat(capturedEnvironment.get().get("OPENCODE_CONFIG_CONTENT"))
                .contains("external_directory", "git push *", "deepseek-v4-flash",
                        "loopper-no-thinking", "\"thinking\":{\"type\":\"disabled\"}",
                        "loopper-structured", "\"steps\":24", "\"temperature\":0.0",
                        "Never retry the same tool call", "loopper-router", "\"steps\":1",
                        "Classify the supplied requirement in one response");
        assertThat(capturedCommand.get().toString()).doesNotContain(capturedEnvironment.get().get("OPENCODE_SERVER_PASSWORD"));

        HttpOpenCodeClient client = new HttpOpenCodeClient(org.springframework.web.client.RestClient.builder(), manager::connectionForClient);
        assertThat(client.healthy()).isTrue();

        OpenCodeRuntimeManager.RuntimeSnapshot restarted = manager.restartOwned();
        assertThat(processes).hasSize(2);
        assertThat(processes.getFirst().destroyed).isTrue();
        assertThat(restarted.managed()).isTrue();
        assertThat(client.healthy()).isTrue();
        manager.close();
        assertThat(processes.getLast().destroyed).isTrue();
    }

    @Test
    void autoReportsTheActualAttemptInsteadOfTheDefaultProbeWhenLaunchFails() throws Exception {
        Path executable = executable();
        LoopperProperties properties = properties("auto", URI.create("http://127.0.0.1:4096"));
        properties.getOpenCode().setExecutable(executable.toString());
        OpenCodeRuntimeManager manager = new OpenCodeRuntimeManager(properties, (command, environment) -> {
            throw new IOException("exec failed");
        }, Clock.systemUTC());

        OpenCodeRuntimeManager.RuntimeSnapshot status = manager.status();

        assertThat(status.status()).isEqualTo("OFFLINE");
        assertThat(status.endpoint()).startsWith("http://127.0.0.1:").doesNotEndWith(":4096");
        assertThat(status.startupFailure()).isEqualTo("Unable to start local OpenCode executable");
        assertThat(status.managed()).isFalse();
        assertThat(status.pid()).isNull();
        assertThat(manager.connectionForClient().endpoint()).isEqualTo(URI.create("http://127.0.0.1:9"));
        manager.close();
    }

    @Test
    void failedAutoLaunchWaitsForAnExplicitStartAndChecksTheNewConnection() throws Exception {
        Path executable = executable();
        LoopperProperties properties = properties("auto", URI.create("http://127.0.0.1:4096"));
        properties.getOpenCode().setExecutable(executable.toString());
        AtomicInteger starts = new AtomicInteger();
        List<FakeProcess> processes = new ArrayList<>();
        OpenCodeRuntimeManager manager = new OpenCodeRuntimeManager(properties, (command, environment) -> {
            if (starts.incrementAndGet() == 1) throw new IOException("first launch failed");
            int port = Integer.parseInt(command.get(command.indexOf("--port") + 1));
            String expectedAuthorization = "Basic " + java.util.Base64.getEncoder().encodeToString(
                    (environment.get("OPENCODE_SERVER_USERNAME") + ":" + environment.get("OPENCODE_SERVER_PASSWORD")).getBytes(StandardCharsets.UTF_8));
            healthServer(port, expectedAuthorization);
            FakeProcess process = new FakeProcess(6400);
            processes.add(process);
            return process;
        }, Clock.systemUTC());

        assertThat(manager.status().status()).isEqualTo("OFFLINE");
        assertThat(starts).hasValue(1);

        assertThat(manager.status().status()).isEqualTo("OFFLINE");
        assertThat(manager.connectionForClient().endpoint()).isEqualTo(URI.create("http://127.0.0.1:9"));
        assertThat(starts).hasValue(1);

        OpenCodeRuntimeManager.RuntimeSnapshot started = manager.startAndCheck();

        assertThat(starts).hasValue(2);
        assertThat(started.status()).isEqualTo("AVAILABLE");
        assertThat(started.managed()).isTrue();
        assertThat(started.pid()).isEqualTo(6400L);
        assertThat(started.startupFailure()).isNull();
        manager.close();
        assertThat(processes.getFirst().destroyed).isTrue();
    }

    @Test
    void autoRetriesAStartupProbeWithoutLettingOneRequestConsumeTheWholeBudget() throws Exception {
        Path executable = executable();
        LoopperProperties properties = properties("auto", URI.create("http://127.0.0.1:1"));
        properties.getOpenCode().setExecutable(executable.toString());
        properties.getOpenCode().setStartupTimeout(java.time.Duration.ofSeconds(3));
        List<FakeProcess> processes = new ArrayList<>();
        AtomicInteger healthRequests = new AtomicInteger();
        OpenCodeRuntimeManager manager = new OpenCodeRuntimeManager(properties, (command, environment) -> {
            int port = Integer.parseInt(command.get(command.indexOf("--port") + 1));
            String expectedAuthorization = "Basic " + java.util.Base64.getEncoder().encodeToString(
                    (environment.get("OPENCODE_SERVER_USERNAME") + ":" + environment.get("OPENCODE_SERVER_PASSWORD")).getBytes(StandardCharsets.UTF_8));
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            server.createContext("/global/health", exchange -> {
                if (healthRequests.incrementAndGet() == 1) {
                    try { Thread.sleep(1_200); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }
                health(exchange, expectedAuthorization);
            });
            server.start();
            servers.add(server);
            FakeProcess process = new FakeProcess(6300);
            processes.add(process);
            return process;
        }, Clock.systemUTC());

        OpenCodeRuntimeManager.RuntimeSnapshot status = manager.status();

        assertThat(status.status()).isEqualTo("AVAILABLE");
        assertThat(status.managed()).isTrue();
        assertThat(healthRequests).hasValueGreaterThanOrEqualTo(2);
        manager.close();
        assertThat(processes.getFirst().destroyed).isTrue();
    }

    @Test
    void httpModeNeverStartsOrStopsAnExternalRuntime() {
        LoopperProperties properties = properties("http", URI.create("http://127.0.0.1:1"));
        AtomicInteger starts = new AtomicInteger();
        OpenCodeRuntimeManager manager = new OpenCodeRuntimeManager(properties, (command, environment) -> {
            starts.incrementAndGet(); throw new AssertionError("http mode must not launch a process");
        }, Clock.systemUTC());

        assertThat(manager.connectionForClient().managed()).isFalse();
        assertThat(manager.status().status()).isEqualTo("OFFLINE");
        assertThat(starts).hasValue(0);
        assertThat(manager.restartable()).isFalse();
        manager.close();
    }

    @Test
    void httpModeFailsClosedForNonLoopbackEndpoint() {
        LoopperProperties properties = properties("http", URI.create("https://example.invalid:443"));
        OpenCodeRuntimeManager manager = new OpenCodeRuntimeManager(properties, (command, environment) -> {
            throw new AssertionError("remote http endpoint must never cause a launch");
        }, Clock.systemUTC());

        assertThat(manager.status().status()).isEqualTo("OFFLINE");
        assertThat(manager.status().endpoint()).isEqualTo("loopback-host-required");
        assertThatThrownBy(manager::connectionForClient).hasMessageContaining("loopback host");
        manager.close();
    }

    private LoopperProperties properties(String mode, URI endpoint) {
        LoopperProperties properties = new LoopperProperties();
        properties.getOpenCode().setMode(mode);
        properties.getOpenCode().setBaseUrl(endpoint);
        properties.getOpenCode().setStartupTimeout(java.time.Duration.ofSeconds(2));
        return properties;
    }

    private Path executable() throws IOException {
        Path executable = temporaryDirectory.resolve("opencode-test");
        Files.writeString(executable, "#!/bin/sh\nexit 0\n", StandardCharsets.UTF_8);
        executable.toFile().setExecutable(true);
        return executable;
    }

    private HttpServer healthServer(int port, String expectedAuthorization) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/global/health", exchange -> health(exchange, expectedAuthorization));
        server.start(); servers.add(server);
        return server;
    }

    private static void health(HttpExchange exchange, String expectedAuthorization) throws IOException {
        if (expectedAuthorization != null && !expectedAuthorization.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            exchange.sendResponseHeaders(401, -1); exchange.close(); return;
        }
        byte[] payload = "{\"healthy\":true,\"version\":\"1.18.12\"}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, payload.length); exchange.getResponseBody().write(payload); exchange.close();
    }

    private static final class FakeProcess extends Process {
        private final long fakePid;
        private boolean alive = true;
        private boolean destroyed;
        private FakeProcess(long fakePid) { this.fakePid = fakePid; }
        @Override public java.io.OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public java.io.InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public java.io.InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() { alive = false; return 0; }
        @Override public boolean waitFor(long timeout, java.util.concurrent.TimeUnit unit) { alive = false; return true; }
        @Override public int exitValue() { if (alive) throw new IllegalThreadStateException(); return 0; }
        @Override public void destroy() { destroyed = true; alive = false; }
        @Override public Process destroyForcibly() { destroy(); return this; }
        @Override public boolean isAlive() { return alive; }
        @Override public long pid() { return fakePid; }
    }
}
