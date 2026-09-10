package io.opencode.loopper.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.sun.net.httpserver.HttpServer;
import io.opencode.loopper.domain.SessionFailure;
import io.opencode.loopper.service.StoryAccountingCoordinator;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

class OpenCodeBusinessPromptCancellationTest {
    @TempDir Path root;

    @Test void abortDuringAccountingWaitInvalidatesOnlyTheAlreadyWaitingBusinessPrompt() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var received = new CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            received.add(exchange.getRequestURI().getPath());
            byte[] bytes = "true".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var client = new HttpOpenCodeClient(RestClient.builder(), URI.create("http://127.0.0.1:" + server.getAddress().getPort()), null, null);
            var accounting = mock(StoryAccountingCoordinator.class);
            ReflectionTestUtils.setField(client, "storyAccounting", accounting);
            doAnswer(invocation -> { entered.countDown(); assertThat(release.await(3, TimeUnit.SECONDS)).isTrue(); return null; })
                    .when(accounting).beforeBusinessPrompt(any(), any());
            var remote = new OpenCodeClient.OpenCodeSession("stopping-designer", root);
            var prompt = workers.submit(() -> client.promptAsync(remote, "business turn"));
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(client.abortWithConfirmation(remote)).isEqualTo(OpenCodeClient.AbortConfirmation.ACKNOWLEDGED);
            release.countDown();
            assertThatThrownBy(() -> prompt.get(3, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(SessionFailure.class);
            assertThat(received).containsExactly("/session/stopping-designer/abort");
            // A deliberate resume is a new prompt, not a permanently disabled remote Session.
            client.promptAsync(remote, "explicitly resumed turn");
            assertThat(received).containsExactly("/session/stopping-designer/abort", "/session/stopping-designer/prompt_async");
        } finally { release.countDown(); server.stop(0); }
    }
}
