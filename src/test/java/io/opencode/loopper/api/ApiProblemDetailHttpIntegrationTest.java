package io.opencode.loopper.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.opencode.loopper.LoopperApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Exercises competing Boot MVC advice through real HTTP; never creates a project or calls a model. */
@SpringBootTest(classes = LoopperApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"loopper.opencode.mode=fake", "loopper.scheduling.enabled=false",
                "loopper.startup-recovery.enabled=false", "spring.mvc.problemdetails.enabled=true"})
class ApiProblemDetailHttpIntegrationTest {
    private static final Path DATA = temporaryData();
    @LocalServerPort int port;
    @Autowired ObjectMapper json;

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("loopper.data-dir", DATA::toString);
        properties.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATA.resolve("test.db")
                + "?foreign_keys=on&busy_timeout=5000&journal_mode=WAL&transaction_mode=IMMEDIATE");
    }

    @Test void beanValidationPreservesTheFieldDtoWithoutPersistingAnInvalidProject() throws Exception {
        JsonNode response = post("/api/projects", "{\"name\":\"\",\"rootPath\":\"\"}", true, 400);
        assertThat(response.path("errorCode").asText()).isEqualTo("FIELD_VALIDATION");
        assertThat(response.path("errorLayer").asText()).isEqualTo("FIELD");
        assertThat(response.path("fields").has("name")).isTrue();
        assertThat(response.path("fields").has("rootPath")).isTrue();
        try (var client = HttpClient.newHttpClient()) {
            var read = client.send(HttpRequest.newBuilder(URI.create(base() + "/api/projects"))
                    .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(read.statusCode()).isEqualTo(200);
            assertThat(json.readTree(read.body()).isEmpty()).isTrue();
        }
    }

    @Test void malformedJsonKeepsTheTypedDto() throws Exception {
        assertThat(post("/api/projects", "{", true, 400).path("errorCode").asText()).isEqualTo("INVALID_JSON");
    }

    @Test void domainAuthorizationAndFrameworkConflictBothKeepTheirExistingSemantics() throws Exception {
        assertThat(post("/api/runtime/opencode/start", "{}", false, 400).path("errorCode").asText())
                .isEqualTo("LOCAL_UI_HEADER_REQUIRED");
        JsonNode conflict = post("/api/runtime/opencode/start", "{}", true, 409);
        assertThat(conflict.path("status").asInt()).isEqualTo(409);
        assertThat(conflict.path("detail").asText()).contains("Only managed or auto mode");
    }

    private JsonNode post(String path, String body, boolean localUi, int expectedStatus) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base() + path)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json");
        if (localUi) request.header("X-Loopper-Local-UI", "1");
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as("%s response=%s", path, response.body()).isEqualTo(expectedStatus);
            return json.readTree(response.body());
        }
    }

    private String base() { return "http://127.0.0.1:" + port; }
    private static Path temporaryData() {
        try { return Files.createTempDirectory("loopper-http-errors-"); }
        catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }
}
