package io.opencode.loopper.service;

import com.sun.net.httpserver.HttpServer;
import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.runtime.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes = LoopperApplication.class, properties = {"loopper.opencode.mode=fake", "loopper.monitor-delay=1h"})
class GitCredentialIntegrationTest {
    @Autowired Flyway flyway;
    @Autowired GitCredentialMapper mapper;
    @Autowired LoopperMapper projects;
    @Autowired ProjectService projectService;
    @Autowired PlatformTransactionManager transactions;
    @Autowired SafeProcessRunner runner;
    @TempDir Path temp;
    GitCredentialService service;
    EncryptedSecretStore secrets;
    GitEvidenceProcess git;
    @BeforeEach void setup() throws Exception {
        temp = temp.toRealPath();
        flyway.clean(); flyway.migrate();
        secrets = new EncryptedSecretStore(temp.resolve("secrets"), temp.resolve("keys/master"), null);
        service = new GitCredentialService(mapper, projects, secrets, transactions);
        git = new GitEvidenceProcess(runner); git.credentialProvider(service);
    }
    GitCredentialService.Request request(String mode, String host, String user, String secret, long version) {
        return new GitCredentialService.Request(mode, host, user, "TOKEN", secret, version, null);
    }
    ProjectRow project(String name) throws Exception {
        Path root = Files.createDirectory(temp.resolve(name));
        git.read(root, "init", "-b", "main");
        return projectService.create(name, root.toString());
    }
    @Test void globalInheritanceOverrideRestoreAndOptimisticConflict() throws Exception {
        var first = project("one"); var second = project("two");
        assertThat(service.get(first.id()).mode()).isEqualTo("INHERIT");
        service.save(null, request("CUSTOM", "https://GITLAB.example:443/", "shared", "test-global-secret", 0));
        assertThat(service.get(first.id()).source()).isEqualTo("GLOBAL");
        service.save(first.id(), request("CUSTOM", "https://gitlab.example", "own", "test-project-secret", 0));
        assertThat(service.environment(Path.of(first.rootPath()), "https://gitlab.example/a.git"))
                .isEqualTo(GitHttpAuthentication.environment("https://gitlab.example", "own", "test-project-secret", "https://gitlab.example/a.git"));
        assertThat(service.get(second.id()).username()).isEqualTo("shared");
        assertThatThrownBy(() -> service.save(first.id(), request("INHERIT", null, null, null, 0))).isInstanceOf(ConflictException.class);
        service.save(first.id(), request("INHERIT", null, null, null, 1));
        assertThat(service.get(first.id()).username()).isEqualTo("shared");
        service.save(null, request("DISABLED", null, null, null, 1));
        assertThat(service.get(first.id()).configured()).isFalse();
    }
    @Test void encryptedRestartNoSecretReadbackAndFailClosedWhenKeyMissing() throws Exception {
        service.save(null, request("CUSTOM", "https://gitlab.example", "shared", "test-sensitive-token", 0));
        var row = mapper.find("global").orElseThrow();
        assertThat(new String(Files.readAllBytes(temp.resolve("secrets").resolve(row.secretRef())), StandardCharsets.ISO_8859_1))
                .doesNotContain("test-sensitive-token");
        var restarted = new EncryptedSecretStore(temp.resolve("secrets"), temp.resolve("keys/master"), null);
        assertThat(restarted.read(row.secretRef())).isEqualTo("test-sensitive-token");
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(service.get(null)))
                .doesNotContain("secret", "test-sensitive-token", row.secretRef());
        service.save(null, request("CUSTOM", "https://gitlab.example", "shared", null, 1));
        assertThat(mapper.find("global").orElseThrow().secretRef()).isEqualTo(row.secretRef());
        assertThatThrownBy(() -> service.save(null, request("CUSTOM", "https://gitlab.example", "different", null, 2)))
                .isInstanceOf(BadRequestException.class);
        Files.delete(temp.resolve("keys/master"));
        assertThatThrownBy(() -> restarted.read(row.secretRef())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> restarted.save("replacement")).isInstanceOf(IllegalStateException.class);
        assertThat(temp.resolve("keys/master")).doesNotExist();
    }
    @Test void neverUsesCredentialsForAnotherOriginOrUnregisteredProject() throws Exception {
        var project = project("project");
        service.save(null, request("CUSTOM", "https://gitlab.example", "shared", "test-global-secret", 0));
        assertThat(service.environment(Path.of(project.rootPath()), "https://other.example/a.git")).isEmpty();
        assertThat(service.environment(temp, "https://gitlab.example/a.git")).isEmpty();
        service.save(project.id(), request("CUSTOM", "https://gitlab.example", "own", "test-project-secret", 0));
        assertThatThrownBy(() -> service.environment(Path.of(project.rootPath()), "https://other.example/a.git"))
                .hasMessageContaining("服务器不匹配");
        assertThatThrownBy(() -> service.testEnvironment(project.id(), request("CUSTOM", "https://gitlab.example", "own", null, 1), "http://gitlab.example/a.git"))
                .hasMessageContaining("服务器不一致");
    }
    @Test void actualGitAuthenticatesFetchFromPrivateSnapshotWithProjectOverrideAndNoConfigWrites() throws Exception {
        var project = project("source"); Path source = Path.of(project.rootPath());
        git.read(source, "config", "user.name", "Fixture"); git.read(source, "config", "user.email", "fixture@example.invalid");
        Files.writeString(source.resolve("file.txt"), "fixture\n"); git.read(source, "add", "."); git.read(source, "commit", "-m", "fixture");
        Path bare = temp.resolve("remote.git"); git.read(temp, "clone", "--bare", source.toString(), bare.toString());
        git.read(bare, "update-server-info");
        var expected = new AtomicReference<>(basic("global", "global-fixture"));
        var observed = new ArrayList<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            synchronized (observed) { observed.add(auth == null ? "missing" : auth); }
            if (!expected.get().equals(auth)) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Basic realm=fixture"); exchange.sendResponseHeaders(401, -1);
            } else {
                Path file = bare.resolve(exchange.getRequestURI().getPath().substring(1)).normalize();
                if (file.startsWith(bare) && Files.isRegularFile(file)) {
                    byte[] bytes = Files.readAllBytes(file); exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
                } else exchange.sendResponseHeaders(404, -1);
            }
            exchange.close();
        }); server.start();
        try {
            String host = "http://127.0.0.1:" + server.getAddress().getPort();
            git.read(source, "remote", "add", "origin", host + "/");
            git.read(source, "remote", "add", "push", host + "/");
            git.read(source, "remote", "set-url", "--push", "push", "http://other.example/repository.git");
            String config = Files.readString(source.resolve(".git/config"));
            service.save(null, request("CUSTOM", host, "global", "global-fixture", 0));
            var refs = git.remote(source, source, Duration.ofSeconds(10), List.of("ls-remote", "origin", "HEAD"), "origin");
            assertThat(refs.exitCode()).isZero(); assertThat(refs.output()).contains("HEAD");
            assertThat(git.remote(source, source, Duration.ofSeconds(10), List.of("ls-remote", "push", "HEAD"), "push").exitCode()).isZero();
            service.save(project.id(), request("CUSTOM", host, "own", "override-fixture", 0)); expected.set(basic("own", "override-fixture"));
            Path snapshot = Files.createDirectory(temp.resolve("snapshot")); git.read(snapshot, "init", "--bare");
            var fetched = git.remote(snapshot, source, Duration.ofSeconds(10), List.of("fetch", host + "/", "refs/heads/main:refs/heads/frozen"), host + "/");
            assertThat(fetched.exitCode()).isZero(); assertThat(git.read(snapshot, "show", "frozen:file.txt")).isEqualTo("fixture\n");
            assertThat(Files.readString(source.resolve(".git/config"))).isEqualTo(config);
            assertThat(observed).contains(basic("global", "global-fixture"), basic("own", "override-fixture"));
        } finally { server.stop(0); }
    }
    @Test void redirectIsNotFollowedAndDraftValidationDoesNotSave() throws Exception {
        var project = project("redirect");
        var received = new java.util.concurrent.atomic.AtomicInteger();
        HttpServer destination = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        destination.createContext("/", e -> { received.incrementAndGet(); e.sendResponseHeaders(200, -1); e.close(); }); destination.start();
        HttpServer origin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        origin.createContext("/", e -> { e.getResponseHeaders().set("Location", "http://127.0.0.1:" + destination.getAddress().getPort() + "/"); e.sendResponseHeaders(302, -1); e.close(); }); origin.start();
        try {
            String host = "http://127.0.0.1:" + origin.getAddress().getPort();
            var env = service.testEnvironment(project.id(), request("CUSTOM", host, "draft", "draft-fixture", 0), host + "/");
            var result = git.run(Path.of(project.rootPath()), Duration.ofSeconds(10), List.of("ls-remote", host + "/"), env);
            assertThat(result.exitCode()).isNotZero(); assertThat(received.get()).isZero();
            assertThat(service.get(project.id()).configured()).isFalse();
            assertThat(result.diagnostic().message()).doesNotContain("draft-fixture", basic("draft", "draft-fixture"));
        } finally { origin.stop(0); destination.stop(0); }
    }
    private static String basic(String user, String secret) { return "Basic " + Base64.getEncoder().encodeToString((user + ":" + secret).getBytes(StandardCharsets.UTF_8)); }
}
