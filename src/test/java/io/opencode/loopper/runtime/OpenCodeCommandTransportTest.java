package io.opencode.loopper.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.sun.net.httpserver.HttpServer;
import io.opencode.loopper.domain.SessionFailure;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;

class OpenCodeCommandTransportTest {
    @TempDir Path root;

    @Test void discoversWithoutCreatingSessionsAndInvokesNativeCommandWithExactIdentity() throws Exception {
        var requests = new AtomicInteger();
        var body = new AtomicReference<String>();
        var commandBody = new AtomicReference<String>();
        var path = new AtomicReference<String>();
        var reply = new AtomicReference<>("[{\"name\":\"aicoding\"}]");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet(); path.set(exchange.getRequestURI().toString());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (exchange.getRequestURI().getPath().endsWith("/command")) commandBody.set(body.get());
            byte[] bytes = reply.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            var client = new HttpOpenCodeClient(RestClient.builder(), URI.create("http://127.0.0.1:" + server.getAddress().getPort()), null, null);
            assertThat(client.commandCapabilities(root).contains("aicoding")).isTrue();
            assertThat(requests.get()).isEqualTo(1);
            assertThat(path.get()).startsWith("/command?directory=");
            assertThat(body.get()).isEmpty();
            reply.set("{\"info\":{\"role\":\"assistant\"},\"parts\":[{\"text\":\"receipt {\\\"runId\\\":\\\"actual-run\\\"}\"}]}");
            var result = client.executeCommand(new OpenCodeClient.OpenCodeSession("session-1", root),
                    new OpenCodeClient.CommandRequest("aicoding", "start SYS-001 000123", "msg_loopper_aicoding_test"));
            assertThat(result.runId()).isEqualTo("actual-run");
            assertThat(path.get()).startsWith("/session/session-1/message?directory=");
            assertThat(commandBody.get()).contains("start SYS-001 000123", "msg_loopper_aicoding_test", "loopper-accounting");
            reply.set("{\"info\":{\"error\":{\"message\":\"plugin rejected arguments\"}}}");
            assertThatThrownBy(() -> client.executeCommand(new OpenCodeClient.OpenCodeSession("session-1", root),
                    new OpenCodeClient.CommandRequest("aicoding", "complete", "msg_loopper_aicoding_end")))
                    .isInstanceOf(SessionFailure.class).hasMessageContaining("plugin rejected arguments");
            reply.set("{}");
            assertThat(client.commandCapabilities(root).state()).isEqualTo(OpenCodeClient.CapabilityState.UNKNOWN);
        } finally { server.stop(0); }
    }
}
