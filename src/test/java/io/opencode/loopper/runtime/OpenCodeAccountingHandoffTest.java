package io.opencode.loopper.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;

class OpenCodeAccountingHandoffTest {
    @TempDir Path root;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void businessCleanupWaitsForCompleteUnlessStatisticsWereExplicitlyCancelled(boolean cancel) throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        var events = new CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(workers);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                String reply = "true";
                if (path.endsWith("/command")) {
                    events.add("complete entered"); entered.countDown();
                    try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    events.add("complete returned");
                    reply = "{\"info\":{\"role\":\"assistant\"},\"parts\":[{\"text\":\"receipt\"}]}";
                } else if (path.endsWith("/message")) {
                    reply = "[{\"info\":{\"role\":\"user\",\"id\":\"msg_loopper_aicoding_complete\"}}]";
                } else events.add(path);
                byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes); exchange.close();
            });
            server.start();
            try {
                var client = new HttpOpenCodeClient(RestClient.builder(), URI.create("http://127.0.0.1:" + server.getAddress().getPort()), null, null);
                var remote = new OpenCodeClient.OpenCodeSession("old", root);
                var command = workers.submit(() -> client.executeCommand(remote,
                        new OpenCodeClient.CommandRequest("aicoding", "complete", "msg_loopper_aicoding_complete")));
                assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
                CountDownLatch cleanupStarted = new CountDownLatch(1);
                var cleanup = workers.submit(() -> { cleanupStarted.countDown(); return client.abortWithConfirmation(remote); });
                assertThat(cleanupStarted.await(3, TimeUnit.SECONDS)).isTrue();
                // A different Session remains independent while the old complete is held.
                assertThat(client.abortWithConfirmation(new OpenCodeClient.OpenCodeSession("other", root)))
                        .isEqualTo(OpenCodeClient.AbortConfirmation.ACKNOWLEDGED);
                assertThatThrownTimeout(cleanup);
                assertThat(events).doesNotContain("/session/old/abort");
                // Only an explicit statistics cancellation bypasses the cleanup wait.
                if (cancel) {
                    assertThat(client.cancelCommand(remote, "msg_loopper_aicoding_complete")).isTrue();
                    cleanup.get(3, TimeUnit.SECONDS); // Late HTTP must not hold cleanup after explicit cancellation.
                }
                release.countDown(); command.get(3, TimeUnit.SECONDS); cleanup.get(3, TimeUnit.SECONDS);
                assertThat(events.stream().filter(event -> event.equals("/session/old/abort"))).hasSize(cancel ? 2 : 1);
                if (!cancel) assertThat(events.indexOf("/session/old/abort")).isGreaterThan(events.indexOf("complete returned"));
            } finally { release.countDown(); server.stop(0); }
        }
    }

    private void assertThatThrownTimeout(Future<?> future) {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> future.get(200, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);
    }
}
