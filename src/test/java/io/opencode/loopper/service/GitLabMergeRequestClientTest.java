package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.opencode.loopper.config.LoopperProperties;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

class GitLabMergeRequestClientTest {
    private HttpServer server;

    @AfterEach void stop() { if (server != null) server.stop(0); }

    @Test
    void returnsTheSingleMergeRequestMatchingTheTaskCommitWithoutLeakingTheToken() throws Exception {
        AtomicReference<String> token = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v4/projects/group/project/merge_requests", exchange -> {
            token.set(exchange.getRequestHeaders().getFirst("PRIVATE-TOKEN"));
            byte[] body = """
                    [{"iid":1686,"state":"merged","web_url":"http://gitlab.test/group/project/-/merge_requests/1686",
                      "sha":"abc123","merge_commit_sha":"def456","created_at":"2026-08-12T01:00:00Z","merged_at":"2026-08-12T02:00:00Z"}]
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        LoopperProperties properties = properties("127.0.0.1", "secret-token");
        GitLabMergeRequestClient client = new GitLabMergeRequestClient(properties, new ObjectMapper());

        var result = client.lookup("127.0.0.1", "group/project", "loopper/task", "develop", "abc123");

        assertThat(result.mergeRequest().state()).isEqualTo("merged");
        assertThat(result.mergeRequest().mergeCommitSha()).isEqualTo("def456");
        assertThat(token).hasValue("secret-token");
    }

    @Test
    void refusesAHostMismatchBeforeSendingCredentials() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        GitLabMergeRequestClient client = new GitLabMergeRequestClient(properties("gitlab.example", "secret-token"), new ObjectMapper());

        assertThatThrownBy(() -> client.lookup("attacker.example", "group/project", "source", "main", "abc"))
                .isInstanceOf(GitLabMergeRequestClient.LookupException.class)
                .hasMessageContaining("未配置");
    }

    @Test
    void rejectsAmbiguousAndMalformedResponses() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v4/projects/group/project/merge_requests", exchange -> {
            byte[] body = "[{\"iid\":1,\"state\":\"opened\",\"sha\":\"abc\"},{\"iid\":2,\"state\":\"closed\",\"sha\":\"abc\"}]".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        GitLabMergeRequestClient client = new GitLabMergeRequestClient(properties("127.0.0.1", "token"), new ObjectMapper());

        assertThatThrownBy(() -> client.lookup("127.0.0.1", "group/project", "source", "main", "abc"))
                .isInstanceOf(GitLabMergeRequestClient.LookupException.class)
                .hasMessageContaining("多个");
    }

    @Test
    void acceptsSquashAndRebaseMergeEvidence() throws Exception {
        AtomicReference<String> body = new AtomicReference<>("[{\"iid\":2,\"state\":\"merged\",\"sha\":\"abc\",\"squash_commit_sha\":\"squash456\"}]");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v4/projects/group/project/merge_requests", exchange -> {
            byte[] response = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        GitLabMergeRequestClient client = new GitLabMergeRequestClient(properties("127.0.0.1", "token"), new ObjectMapper());

        assertThat(client.lookup("127.0.0.1", "group/project", "source", "main", "abc")
                .mergeRequest().mergeCommitSha()).isEqualTo("squash456");
        body.set("[{\"iid\":2,\"state\":\"merged\",\"sha\":\"abc\",\"merge_commit_sha\":null,\"squash_commit_sha\":null}]");
        assertThat(client.lookup("127.0.0.1", "group/project", "source", "main", "abc")
                .mergeRequest().state()).isEqualTo("merged");
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 404})
    void reportsGitLabHttpFailuresWithoutChangingEvidence(int status) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v4/projects/group/project/merge_requests", exchange -> {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        GitLabMergeRequestClient client = new GitLabMergeRequestClient(properties("127.0.0.1", "token"), new ObjectMapper());

        assertThatThrownBy(() -> client.lookup("127.0.0.1", "group/project", "source", "main", "abc"))
                .isInstanceOf(GitLabMergeRequestClient.LookupException.class)
                .hasMessageContaining("HTTP " + status);
    }

    @Test
    void boundsTimeoutAndMalformedOrOversizedResponses() throws Exception {
        AtomicReference<String> body = new AtomicReference<>("not-json");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v4/projects/group/project/merge_requests", exchange -> {
            byte[] response = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        LoopperProperties settings = properties("127.0.0.1", "token");
        GitLabMergeRequestClient client = new GitLabMergeRequestClient(settings, new ObjectMapper());

        assertThatThrownBy(() -> client.lookup("127.0.0.1", "group/project", "source", "main", "abc"))
                .isInstanceOf(GitLabMergeRequestClient.LookupException.class).hasMessageContaining("无法解析");
        body.set("[\"" + "x".repeat(1024 * 1024) + "\"]");
        assertThatThrownBy(() -> client.lookup("127.0.0.1", "group/project", "source", "main", "abc"))
                .isInstanceOf(GitLabMergeRequestClient.LookupException.class).hasMessageContaining("大小上限");

        server.removeContext("/api/v4/projects/group/project/merge_requests");
        server.createContext("/api/v4/projects/group/project/merge_requests", exchange -> {
            try { Thread.sleep(200); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            exchange.sendResponseHeaders(200, 2);
            exchange.getResponseBody().write("[]".getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        settings.getPublication().getGitlab().setRequestTimeout(Duration.ofMillis(30));
        assertThatThrownBy(() -> client.lookup("127.0.0.1", "group/project", "source", "main", "abc"))
                .isInstanceOf(GitLabMergeRequestClient.LookupException.class).hasMessageContaining("查询失败");
    }

    private LoopperProperties properties(String host, String token) {
        LoopperProperties properties = new LoopperProperties();
        properties.getPublication().getGitlab().setHost(host);
        properties.getPublication().getGitlab().setPrivateToken(token);
        properties.getPublication().getGitlab().setApiBaseUrl(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v4"));
        return properties;
    }
}
