package io.opencode.loopper.api;

import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.runtime.FakeOpenCodeClient;
import io.opencode.loopper.runtime.OpenCodeClient;
import io.opencode.loopper.service.roles.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = LoopperApplication.class, properties = {"loopper.opencode.mode=fake", "loopper.monitor-delay=1h"})
class RoleApiIntegrationTest {
    @Autowired Flyway flyway;
    @Autowired RoleReadService roles;
    @Autowired RolePublishingService publishing;
    @Autowired RoleArchive archives;
    @Autowired JdbcTemplate jdbc;
    @Autowired OpenCodeClient client;
    MockMvc mvc;
    @BeforeEach void reset() {
        flyway.clean(); flyway.migrate(); publishing.seedBuiltin(); ((FakeOpenCodeClient) client).reset();
        mvc = MockMvcBuilders.standaloneSetup(new RoleController(roles, publishing), new RoleImportController(archives, publishing))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }
    @Test void readsAndValidationArePureAndWriteHeadersAreRequired() throws Exception {
        var tables = List.of("assist_tool_policy", "assist_catalog_registration", "assist_policy_audit", "role_revision", "role_binding", "role_session_snapshot");
        var before = tables.stream().map(this::count).toList();
        mvc.perform(get("/api/roles").param("limit", "5")).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(5));
        mvc.perform(post("/api/roles/builtin.implementation/preview").contentType("application/json")
                .content("{\"slot\":\"IMPLEMENTATION\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.complete").value(false));
        mvc.perform(get("/api/roles/builtin.router/preview").param("slot", "ROUTER_NO_TOOLS").param("projectId", "missing"))
                .andExpect(status().isNotFound());
        byte[] bytes = roles.export("builtin.router", null);
        var file = new MockMultipartFile("file", "router.zip", "application/zip", bytes);
        mvc.perform(multipart("/api/role-imports/validate").file(file))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("LOCAL_UI_HEADER_REQUIRED"));
        mvc.perform(multipart("/api/role-imports/validate").file(file).header("X-Loopper-Local-UI", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(true));
        var request = new MockMultipartFile("request", "", "application/json", "{}".getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/role-imports/publish").file(file).file(request))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("LOCAL_UI_HEADER_REQUIRED"));
        assertThat(tables.stream().map(this::count).toList()).isEqualTo(before);
        var fake = (FakeOpenCodeClient) client;
        assertThat(fake.createSessionCalls()).isZero(); assertThat(fake.createReadOnlySessionCalls()).isZero(); assertThat(fake.promptCalls()).isZero();
    }
    private long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class); }

    @Test void baselinePreviewsIncludeBundledAndNativeToolsWithoutInventingConnections() {
        var implementation = roles.preview("builtin.implementation", "IMPLEMENTATION", "");
        assertThat(implementation.mcpTools()).extracting(RoleReadService.PreviewTool::name)
                .contains("read", "bash", "@loopper-assist/get_execution_context",
                        "@loopper-internal/read_development_requirement", "@loopper-internal/read_source_development_file");
        var ppt = roles.preview("builtin.ppt", "PPT_AGENT", "");
        assertThat(ppt.mcpTools()).extracting(RoleReadService.PreviewTool::name)
                .contains("@loopper-internal/ppt_get_context", "@loopper-internal/ppt_export",
                        "@loopper-internal/ppt_search_project_knowledge").doesNotContain("read", "bash");
        var knowledge = roles.preview("builtin.knowledge", "KNOWLEDGE_RESEARCH_READ_ONLY", "");
        assertThat(knowledge.mcpTools()).extracting(RoleReadService.PreviewTool::name)
                .contains("read", "@loopper-assist/search_project_knowledge").doesNotContain("bash");
        assertThat(roles.preview("builtin.router", "ROUTER_NO_TOOLS", "").mcpTools()).isEmpty();
        assertThat(ppt.mcpTools()).allMatch(tool -> !tool.available());
        assertThat(implementation.mcpTools()).extracting(RoleReadService.PreviewTool::name).doesNotHaveDuplicates();
        assertThat(implementation.limitations()).isEmpty();
    }

    @Test void narrowedPreviewDoesNotReintroduceBaselineTools() {
        var definition = new RoleManifest.Role("custom.reader", "读取助手", "只读取文件", "general", "辅助",
                List.of("GENERAL_READ_ONLY"), "INTERSECT", List.of("read"), List.of(), List.of(),
                "INHERIT_WORKFLOW", "WORKFLOW_ADAPTER", Map.of());
        var parsed = new RoleArchive.Parsed("a".repeat(64),
                new RoleManifest.Document(1, List.of(), List.of(definition)), Map.of("custom.reader", Map.of()));
        var validation = publishing.validate(parsed);
        publishing.publish(parsed, new RolePublishingService.PublishRequest(parsed.sourceSha256(),
                "preview-narrowed-1234", validation.activations()));
        assertThat(roles.preview("custom.reader", "GENERAL_READ_ONLY", "").mcpTools())
                .extracting(RoleReadService.PreviewTool::name).containsExactly("read");
    }
}
