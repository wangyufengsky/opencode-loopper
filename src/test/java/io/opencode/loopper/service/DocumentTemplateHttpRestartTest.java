package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.*;
import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.persistence.DocumentTemplateMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** Real loopback HTTP and two independent application lifetimes. No Provider or project commands. */
class DocumentTemplateHttpRestartTest {
    @TempDir Path temporary;
    private final ObjectMapper json = new ObjectMapper();
    @Test void uploadIdentitySourceAndCancellationSurviveServiceRestart() throws Exception {
        io.opencode.loopper.TestJvm.run(DocumentTemplateHttpRestartTest.class, temporary);
    }
    public static void main(String[] args) throws Exception {
        var fixture = new DocumentTemplateHttpRestartTest();
        fixture.temporary = Path.of(args[0]);
        fixture.exerciseRestart();
        System.exit(0);
    }
    private void exerciseRestart() throws Exception {
        var data = Files.createDirectory(temporary.resolve("data"));
        var source = Files.createDirectory(temporary.resolve("project")); Files.writeString(source.resolve("owned.txt"), "用户数据");
        String key = UUID.randomUUID().toString(), runId, projectId, fileId, sha; byte[] upload;
        try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            try (var app = start(data)) {
                projectId = app.getBean(ProjectService.class).create("HTTP 文档项目", source.toString(), "隔离验收").id();
                upload = multipart(projectId, key);
                var denied = send(http, app, "/api/template-tasks/document-runs", "POST", upload, false);
                assertThat(denied.statusCode()).isEqualTo(400);
                assertThat(app.getBean(JdbcTemplate.class).queryForObject("SELECT count(*) FROM document_template_run", Integer.class)).isZero();
                var created = send(http, app, "/api/template-tasks/document-runs", "POST", upload, true);
                assertThat(created.statusCode()).as(created.body()).isEqualTo(202);
                var body = json.readTree(created.body()); runId = body.path("id").asText();
                assertThat(body.path("uploadReady").asBoolean()).isTrue(); assertThat(body.has("contractJson")).isFalse();
                assertThat(body.path("files").size()).isEqualTo(2);
                fileId = body.path("files").get(0).path("id").asText(); sha = body.path("files").get(0).path("sha256").asText();
                var original = send(http, app, "/api/template-tasks/document-runs/" + runId + "/documents/" + fileId + "/sections/0?expectedSha=" + sha, "GET", null, false);
                assertThat(original.statusCode()).as(original.body()).isEqualTo(200); assertThat(original.body()).contains("付款必须鉴权");
                assertThat(app.getBean(JdbcTemplate.class).queryForObject("SELECT count(*) FROM task", Integer.class)).isZero();
            }
            try (var app = start(data)) {
                var restored = send(http, app, "/api/template-tasks/document-runs/by-request/" + key, "GET", null, false);
                assertThat(restored.statusCode()).as(restored.body()).isEqualTo(200);
                var body = json.readTree(restored.body()); assertThat(body.path("id").asText()).isEqualTo(runId);
                assertThat(body.path("files").get(0).path("id").asText()).isEqualTo(fileId);
                assertThat(body.path("files").get(0).path("sha256").asText()).isEqualTo(sha);
                var retry = send(http, app, "/api/template-tasks/document-runs", "POST", upload, true);
                assertThat(retry.statusCode()).as(retry.body()).isEqualTo(202);
                assertThat(json.readTree(retry.body()).path("id").asText()).isEqualTo(runId);
                assertThat(app.getBean(JdbcTemplate.class).queryForObject("SELECT count(*) FROM document_template_run", Integer.class)).isEqualTo(1);
                HttpResponse<String> response = null;
                for (int retryIndex = 0; retryIndex < 10; retryIndex++) {
                    var row = app.getBean(DocumentTemplateMapper.class).find(runId).orElseThrow();
                    var command = json.writeValueAsBytes(new DocumentTemplateControl.Command(UUID.randomUUID().toString(), row.version()));
                    response = http.send(HttpRequest.newBuilder(uri(app, "/api/template-tasks/document-runs/" + runId + "/cancel"))
                            .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json").header("X-Loopper-Local-UI", "1")
                            .POST(HttpRequest.BodyPublishers.ofByteArray(command)).build(), HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() != 409) break;
                    assertThat(response.body()).contains("DOCUMENT_COMMAND_CONFLICT");
                    Thread.sleep(50); // The upload dispatcher may advance the optimistic version before cancel.
                }
                assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
                for (int i = 0; i < 10; i++) {
                    var current = app.getBean(DocumentTemplateMapper.class).find(runId).orElseThrow();
                    if (current.state().equals("CANCELLED")) break;
                    app.getBean(DocumentTemplateCoordinator.class).dispatch(runId);
                    Thread.sleep(50);
                }
                assertThat(app.getBean(DocumentTemplateMapper.class).find(runId).orElseThrow().state()).isEqualTo("CANCELLED");
                assertThat(app.getBean(JdbcTemplate.class).queryForObject("SELECT count(*) FROM task", Integer.class)).isZero();
            }
        }
        assertThat(Files.readString(source.resolve("owned.txt"))).isEqualTo("用户数据");
        try (var files = Files.list(source)) { assertThat(files.map(path -> path.getFileName().toString())).containsExactly("owned.txt"); }
    }
    private ConfigurableApplicationContext start(Path data) {
        return new SpringApplicationBuilder(LoopperApplication.class).run("--server.address=127.0.0.1", "--server.port=0",
                "--loopper.data-dir=" + data, "--spring.datasource.url=jdbc:sqlite:" + data.resolve("loopper.db") + "?foreign_keys=on&busy_timeout=5000&journal_mode=WAL&transaction_mode=IMMEDIATE",
                "--loopper.opencode.mode=fake", "--loopper.opencode.model=fake/test-model", "--loopper.scheduling.enabled=false",
                "--loopper.startup-recovery.enabled=false", "--spring.main.banner-mode=off");
    }
    private URI uri(ConfigurableApplicationContext app, String path) { return URI.create("http://127.0.0.1:" + app.getEnvironment().getProperty("local.server.port") + path); }
    private HttpResponse<String> send(HttpClient http, ConfigurableApplicationContext app, String path, String method, byte[] data, boolean local) throws Exception {
        var request = HttpRequest.newBuilder(uri(app, path)).timeout(Duration.ofSeconds(15));
        if (local) request.header("X-Loopper-Local-UI", "1");
        if (data != null) request.header("Content-Type", "multipart/form-data; boundary=loopper-fixture");
        request.method(method, data == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(data));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private byte[] multipart(String project, String key) {
        String metadata = json.writeValueAsString(new DocumentTemplateService.Request(key, "REQUIREMENT_DEVELOPMENT", io.opencode.loopper.template.DocumentTemplateDefinition.VERSION, project, null));
        return ("--loopper-fixture\r\nContent-Disposition: form-data; name=\"metadata\"\r\nContent-Type: application/json\r\n\r\n" + metadata
                + "\r\n--loopper-fixture\r\nContent-Disposition: form-data; name=\"files\"; filename=\"payment.md\"\r\nContent-Type: text/markdown\r\n\r\n# 付款\n付款必须鉴权"
                + "\r\n--loopper-fixture\r\nContent-Disposition: form-data; name=\"files\"; filename=\"errors.md\"\r\nContent-Type: text/markdown\r\n\r\n# 异常\n权限不足时提示原因"
                + "\r\n--loopper-fixture--\r\n").getBytes(StandardCharsets.UTF_8);
    }
}
