package io.opencode.loopper.runtime;

import com.sun.net.httpserver.HttpServer;
import io.opencode.loopper.service.NotFoundException;
import io.opencode.loopper.service.ServiceUnavailableException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OpenCodeSkillInventoryTest {
    @TempDir Path temp;
    private HttpServer http;
    private OpenCodeSkillInventory service;
    private final JsonMapper json = JsonMapper.builder().build();
    private final List<URI> requests = new ArrayList<>();
    private String response = "[]";
    private int status = 200;

    @BeforeEach void start() throws Exception {
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/", exchange -> {
            requests.add(exchange.getRequestURI());
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try { exchange.getResponseBody().write(bytes); } finally { exchange.close(); }
        });
        http.start();
        var runtime = mock(OpenCodeRuntimeManager.class);
        when(runtime.connectionForClient()).thenReturn(new OpenCodeRuntimeManager.Connection(
                URI.create("http://127.0.0.1:" + http.getAddress().getPort()), null, null, false, "generation", null));
        service = new OpenCodeSkillInventory(runtime);
    }

    @AfterEach void stop() { http.stop(0); }

    @Test void listsMetadataOnlyAndReturnsExactDiscoveredMarkdownOnDemand() {
        String markdown = "# 审查\n\n- 检查行为\n\n```java\nreturn true;\n```\n<script>not executable</script>";
        response = json.writeValueAsString(List.of(Map.of("name", "review", "description", "代码审查",
                "location", "/skills/review/SKILL.md", "content", markdown)));
        Path directory = temp.resolve("项目 & query");
        var inventory = service.inventory(directory);
        assertThat(inventory.complete()).isTrue();
        assertThat(inventory.skills()).singleElement().extracting(OpenCodeSkillInventory.Skill::name).isEqualTo("review");
        assertThat(json.writeValueAsString(inventory)).doesNotContain("content", "return true", "not executable");
        assertThat(service.document(directory, "review").content()).isEqualTo(markdown);
        assertThat(requests).allSatisfy(uri -> {
            assertThat(uri.getPath()).isEqualTo("/skill");
            assertThat(URLDecoder.decode(uri.getRawQuery(), StandardCharsets.UTF_8)).isEqualTo("directory=" + directory);
            assertThat(uri.getRawQuery()).contains("%26");
        });
        assertThatThrownBy(() -> service.document(directory, "../../secret.md")).isInstanceOf(NotFoundException.class);
        assertThat(requests).allSatisfy(uri -> assertThat(uri.getPath()).isEqualTo("/skill"));
    }

    @Test void doesNotConfuseUnavailableMalformedAndEmptyInventories() {
        assertThat(service.inventory(temp).skills()).isEmpty();
        for (String body : List.of("{}", "invalid-json", "[{\"location\":\"/somewhere\"}]")) {
            response = body;
            assertThatThrownBy(() -> service.inventory(temp)).isInstanceOf(ServiceUnavailableException.class)
                    .hasMessageContaining("无法读取");
        }
        response = "[]"; status = 404;
        assertThatThrownBy(() -> service.inventory(temp)).isInstanceOf(ServiceUnavailableException.class)
                .hasMessageNotContaining("404");
    }

    @Test void boundsInventoryAndDocumentWithoutSilentlyTruncatingMarkdown() {
        var entries = new ArrayList<Map<String, String>>();
        for (int i = 0; i <= OpenCodeSkillInventory.MAX_SKILLS; i++) {
            entries.add(Map.of("name", "skill-" + i, "location", "/skills/skill-" + i + "/SKILL.md", "content", "body"));
        }
        response = json.writeValueAsString(entries);
        assertThat(service.inventory(temp).skills()).hasSize(OpenCodeSkillInventory.MAX_SKILLS);
        assertThat(service.inventory(temp).complete()).isFalse();
        response = json.writeValueAsString(List.of(Map.of("name", "large", "location", "/skills/large/SKILL.md",
                "content", "x".repeat(OpenCodeSkillInventory.MAX_DOCUMENT_CHARS + 1))));
        assertThatThrownBy(() -> service.document(temp, "large")).isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("超过展示上限");
        response = " ".repeat(OpenCodeSkillInventory.MAX_RESPONSE_BYTES + 1);
        assertThatThrownBy(() -> service.inventory(temp)).isInstanceOf(ServiceUnavailableException.class);
    }

    @Test void doesNotFabricateAnEmptyDocumentWhenContentIsMissing() {
        response = "[{\"name\":\"review\",\"location\":\"/skills/review/SKILL.md\"}]";
        assertThatThrownBy(() -> service.document(temp, "review")).isInstanceOf(ServiceUnavailableException.class);
        assertThatThrownBy(() -> service.document(temp, "missing")).isInstanceOf(NotFoundException.class);
    }
}
