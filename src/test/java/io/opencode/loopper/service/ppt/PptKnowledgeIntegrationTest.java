package io.opencode.loopper.service.ppt;

import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.service.ConflictException;
import io.opencode.loopper.service.ProjectService;
import io.opencode.loopper.service.assist.DatabaseConfig;
import io.opencode.loopper.service.assist.DatabaseQueryService;
import io.opencode.loopper.service.knowledge.KnowledgeSources;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = LoopperApplication.class, properties = {
        "loopper.opencode.mode=fake", "loopper.opencode.model=fake/model",
        "loopper.scheduling.enabled=false", "loopper.startup-recovery.enabled=false"})
@AutoConfigureMockMvc
class PptKnowledgeIntegrationTest {
    private static final Path DATA = data();
    private static Path data() {
        try { return Files.createTempDirectory("ppt-knowledge-it-"); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("loopper.data-dir", DATA::toString);
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATA.resolve("test.db")
                + "?foreign_keys=on&journal_mode=WAL&transaction_mode=IMMEDIATE");
    }
    @Autowired Flyway flyway;
    @Autowired ProjectService projects;
    @Autowired KnowledgeSources sources;
    @Autowired PptDocuments documents;
    @Autowired PptKnowledge knowledge;
    @Autowired PptKnowledgeTools tools;
    @Autowired PptKnowledgeEvidence evidence;
    @MockitoBean DatabaseQueryService databases;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc http;
    @TempDir Path root;
    Path firstRoot;
    Path secondRoot;
    String firstProject;
    String secondProject;

    @BeforeEach void prepare() throws Exception {
        flyway.clean(); flyway.migrate(); root = root.toRealPath();
        firstRoot = Files.createDirectory(root.resolve("first"));
        secondRoot = Files.createDirectory(root.resolve("second"));
        Files.writeString(firstRoot.resolve("进展.md"), "# 业务进展\n支付项目已完成第一阶段\n");
        Files.writeString(secondRoot.resolve("进展.md"), "# 业务进展\n第二项目独有内容\n");
        firstProject = projects.create("支付平台", firstRoot.toString(), "支付项目说明").id();
        secondProject = projects.create("独立项目", secondRoot.toString(), "另一个项目").id();
    }

    @Test void creationFreezesProjectSourcesAndReplayDoesNotAdmitLaterSources() throws Exception {
        Path external = Files.createDirectory(root.resolve("approved-documents"));
        Files.writeString(external.resolve("业务.md"), "# 核验材料\n外部目录已在项目中授权\n");
        String directory = sources.addDirectory(firstProject, external.toString()).id();
        String uploaded = upload(firstProject, "计划.md", "# 计划\n今年完成改造\n");
        var input = new PptDocuments.Create(key(), "项目汇报", firstProject, "fake/model");
        String document = documents.create(input).id();
        JsonNode frozen = json.valueToTree(knowledge.view(document));
        assertThat(frozen.path("project").path("id").asText()).isEqualTo(firstProject);
        assertThat(sourceIds(frozen)).contains("code", "documents", directory, uploaded);
        assertThat(frozen.toString()).doesNotContain("rootPath", "connectionsJson", "password", external.toString());

        String late = upload(firstProject, "新增.md", "# 新资料\n不得追溯加入旧作品\n");
        assertThat(documents.create(input).id()).isEqualTo(document);
        assertThat((JsonNode)json.valueToTree(knowledge.view(document))).isEqualTo(frozen);
        String run = run(document);
        assertThatThrownBy(() -> invoke(document, run, "ppt_read_knowledge_source", Map.of("sourceId", late, "section", 0)))
                .isInstanceOf(RuntimeException.class);
        JsonNode read = invoke(document, run, "ppt_read_knowledge_source", Map.of("sourceId", directory, "path", "业务.md", "section", 0));
        assertThat(read.path("text").asText()).contains("外部目录已在项目中授权");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_conversation", Integer.class)).isZero();
    }

    @Test void repeatedCodeSourceIdStillReadsOnlyTheFrozenProjectAndRejectsOtherProjectSources() {
        String foreign = upload(secondProject, "其他.md", "# 其他\n不属于支付平台\n");
        String firstDocument = create(firstProject), secondDocument = create(secondProject);
        String firstRun = run(firstDocument), secondRun = run(secondDocument);
        var args = Map.<String,Object>of("sourceId", "code", "path", "进展.md", "section", 0);
        assertThat(invoke(firstDocument, firstRun, "ppt_read_knowledge_source", args).path("text").asText())
                .contains("支付项目已完成第一阶段").doesNotContain("第二项目独有内容");
        assertThat(invoke(secondDocument, secondRun, "ppt_read_knowledge_source", args).path("text").asText())
                .contains("第二项目独有内容").doesNotContain("支付项目已完成第一阶段");
        assertThatThrownBy(() -> invoke(firstDocument, firstRun, "ppt_read_knowledge_source", Map.of("sourceId", foreign, "section", 0)))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> invoke(firstDocument, firstRun, "ppt_search_project_knowledge",
                Map.of("query", "业务", "sourceIds", List.of(foreign))))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> invoke(firstDocument, secondRun, "ppt_read_knowledge_source", args))
                .isInstanceOf(RuntimeException.class);
    }

    @Test void unassociatedAndLegacyProjectMetadataDoNotGrantKnowledgeAccess() {
        String unassociated = create(null), historical = create(null);
        jdbc.update("UPDATE ppt_document SET project_id=? WHERE id=?", firstProject, historical);
        for (String document : List.of(unassociated, historical)) {
            assertThat(sourceIds(json.valueToTree(knowledge.view(document)))).isEmpty();
            String run = run(document);
            assertThatThrownBy(() -> invoke(document, run, "ppt_read_knowledge_source",
                    Map.of("sourceId", "code", "path", "进展.md", "section", 0)))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    @Test void frozenSourcesRejectTraversalAbsoluteForeignPathsSymlinksAndSecrets() throws Exception {
        String document = create(firstProject), run = run(document);
        Files.writeString(firstRoot.resolve(".env"), "DO_NOT_READ=private");
        Files.createSymbolicLink(firstRoot.resolve("outside.md"), secondRoot.resolve("进展.md"));
        for (String path : List.of("../second/进展.md", secondRoot.resolve("进展.md").toString(), ".env", "outside.md")) {
            assertThatThrownBy(() -> invoke(document, run, "ppt_read_knowledge_source",
                    Map.of("sourceId", "code", "path", path, "section", 0)))
                    .as("PPT knowledge must reject %s", path).isInstanceOf(RuntimeException.class);
        }
    }

    @Test void searchResultsPointToPptToolsAndCanBeReadWithoutChangingScope() {
        String document = create(firstProject), run = run(document);
        JsonNode result = invoke(document, run, "ppt_search_project_knowledge",
                Map.of("query", "支付项目", "mode", "EXACT", "sourceIds", List.of("documents")));
        assertThat(result.path("matches").size()).isEqualTo(1);
        JsonNode read = result.path("matches").get(0).path("read");
        assertThat(read.path("tool").asText()).isEqualTo("ppt_read_knowledge_source");
        ObjectNode args = (ObjectNode) read.path("arguments").deepCopy(); args.put("agentRunId", run);
        JsonNode full = json.valueToTree(tools.invoke(document, read.path("tool").asText(), args, () -> { }));
        assertThat(full.path("text").asText()).contains("支付项目已完成第一阶段");
        assertThat(full.path("sha256").asText()).matches("[a-f0-9]{64}");
        assertThat(full.path("evidenceId").asText()).isNotBlank();
    }

    @Test void capturedEvidenceReplaysAndSurvivesFileChangesButCannotBeBorrowedByAnotherWork() throws Exception {
        String document = create(firstProject), other = create(firstProject), run = run(document);
        var args = Map.<String,Object>of("sourceId", "documents", "path", "进展.md", "section", 0);
        JsonNode first = invoke(document, run, "ppt_read_knowledge_source", args);
        String firstId = first.path("evidenceId").asText();
        assertThat(invoke(document, run, "ppt_read_knowledge_source", args)).isEqualTo(first);
        assertThat(evidenceCount(document)).isEqualTo(1);
        assertThat(evidence.read(document, firstId)).isEqualTo(first);
        assertThatThrownBy(() -> evidence.read(other, firstId)).hasMessageContaining("不属于");
        http.perform(get("/api/ppt/documents/{id}/knowledge/evidence/{evidence}", other, firstId))
                .andExpect(status().isNotFound());

        Files.writeString(firstRoot.resolve("进展.md"), "# 业务进展\n支付项目已完成第二阶段\n");
        JsonNode next = invoke(document, run, "ppt_read_knowledge_source", args);
        assertThat(next.path("evidenceId").asText()).isNotEqualTo(firstId);
        assertThat(next.path("sha256").asText()).isNotEqualTo(first.path("sha256").asText());
        assertThat(next.path("text").asText()).contains("第二阶段");
        assertThat(evidence.read(document, firstId).path("text").asText()).contains("第一阶段").doesNotContain("第二阶段");
        jdbc.update("UPDATE ppt_agent_run SET state='STOPPED',stop_proof='ACKNOWLEDGED' WHERE id=?", run);
        assertThat(evidence.read(document, firstId)).isEqualTo(first);
        assertThatThrownBy(() -> invoke(document, run, "ppt_read_knowledge_source", args)).isInstanceOf(ConflictException.class);
        assertThat(evidenceCount(document)).isEqualTo(2);
        String nextRun = run(document), otherRun = run(other);
        assertThat(invoke(document, nextRun, "ppt_read_knowledge_source", Map.of("evidenceId", firstId)))
                .isEqualTo(first);
        assertThatThrownBy(() -> invoke(other, otherRun, "ppt_read_knowledge_source", Map.of("evidenceId", firstId)))
                .hasMessageContaining("不属于");
        assertThat(evidenceCount(document)).isEqualTo(2);
    }

    @Test void onlyThisWorksCapturedEvidenceCanBeUsedAsPlanSources() {
        String document = create(firstProject), other = create(firstProject), run = run(document);
        String id = invoke(document, run, "ppt_read_knowledge_source",
                Map.of("sourceId", "documents", "path", "进展.md", "section", 0)).path("evidenceId").asText();
        var plan = (ObjectNode) json.readTree(documents.snapshot(document, null).planJson());
        plan.putArray("slides").addObject().put("id", "p1").put("title", "项目进展").putArray("sourceIds").add(id);
        documents.savePlan(document, new PptDocuments.PlanEdit(key(), 0, plan), false, () -> { });
        assertThat(json.readTree(documents.snapshot(document, null).planJson()).path("slides").get(0).path("sourceIds").get(0).asText())
                .isEqualTo(id);
        assertThatThrownBy(() -> documents.savePlan(other, new PptDocuments.PlanEdit(key(), 0, plan), false, () -> { }))
                .isInstanceOf(RuntimeException.class);
        assertThat(documents.get(other).revision()).isZero();
    }

    @Test void directoryReadsDoNotCreateEvidenceAndLateGuardCannotCommitEvidence() {
        String document = create(firstProject), run = run(document);
        JsonNode directory = invoke(document, run, "ppt_read_knowledge_source",
                Map.of("sourceId", "documents", "path", "进展.md", "section", -1));
        assertThat(directory.path("sections").size()).isEqualTo(1);
        assertThat(directory.has("evidenceId")).isFalse();
        assertThat(evidenceCount(document)).isZero();
        AtomicInteger checks = new AtomicInteger();
        var args = json.createObjectNode().put("agentRunId", run).put("sourceId", "documents").put("path", "进展.md").put("section", 0);
        assertThatThrownBy(() -> tools.invoke(document, "ppt_read_knowledge_source", args, () -> {
            if (checks.incrementAndGet() == 2) throw new ConflictException("PPT_SCOPE_STALE", "助手已经停止");
        })).isInstanceOf(ConflictException.class).hasMessageContaining("已经停止");
        assertThat(checks.get()).isEqualTo(2);
        assertThat(evidenceCount(document)).isZero();
    }

    @Test void gitReadsUseFrozenRepositoryAndPersistEditablePresentationSourceEvidence() throws Exception {
        git("init"); git("config", "user.name", "PPT Test"); git("config", "user.email", "ppt-test@example.invalid");
        git("add", "进展.md"); git("-c", "commit.gpgsign=false", "commit", "-m", "first milestone");
        String commit = git("rev-parse", "HEAD").strip();
        String document = create(firstProject), run = run(document);
        assertThat(sourceIds(json.valueToTree(knowledge.view(document)))).contains("git");
        JsonNode captured = invoke(document, run, "ppt_read_knowledge_git",
                Map.of("sourceId", "git", "operation", "file", "commit", commit, "path", "进展.md"));
        assertThat(captured.path("text").asText()).contains("支付项目已完成第一阶段");
        assertThat(captured.path("commit").asText()).isEqualTo(commit);
        assertThat(captured.path("kind").asText()).isEqualTo("GIT");
        assertThat(evidence.read(document, captured.path("evidenceId").asText())).isEqualTo(captured);
        assertThatThrownBy(() -> invoke(document, run, "ppt_read_knowledge_git",
                Map.of("sourceId", "git", "operation", "file", "commit", commit, "path", "../second/进展.md")))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> invoke(document, run, "ppt_read_knowledge_git", Map.of("sourceId", "git", "operation", "checkout")))
                .isInstanceOf(RuntimeException.class);
        assertThat(git("status", "--porcelain")).isEmpty();
        assertThat(evidenceCount(document)).isEqualTo(1);
    }

    @Test void databasesRequireFrozenProjectBindingBeforeAnyQueryAndCaptureTheBoundConfiguration() {
        String owned = database(firstProject, "支付只读库"), foreign = database(secondProject, "其他项目库");
        String document = create(firstProject), run = run(document);
        assertThat(sourceIds(json.valueToTree(knowledge.view(document)))).contains("database:" + owned).doesNotContain("database:" + foreign);
        assertThatThrownBy(() -> invoke(document, run, "ppt_query_knowledge_database", Map.of("connectionId", foreign, "sql", "SELECT 1")))
                .hasMessageContaining("不属于");
        assertThatThrownBy(() -> invoke(document, run, "ppt_inspect_knowledge_database", Map.of("connectionId", foreign, "schema", "app", "table", "totals")))
                .hasMessageContaining("不属于");
        verifyNoInteractions(databases);
        when(databases.query(any(), eq("SELECT total FROM app.totals")))
                .thenReturn(Map.of("columns", List.of("total"), "rows", List.of(List.of(18)), "truncated", false));
        JsonNode captured = invoke(document, run, "ppt_query_knowledge_database",
                Map.of("connectionId", owned, "sql", "SELECT total FROM app.totals"));
        verify(databases).query(argThat(bound -> bound.id().equals(owned) && bound.config().schemas().equals(List.of("app"))),
                eq("SELECT total FROM app.totals"));
        assertThat(captured.path("kind").asText()).isEqualTo("DATABASE");
        assertThat(captured.path("sourceId").asText()).isEqualTo("database:" + owned);
        assertThat(captured.path("rows").get(0).get(0).asInt()).isEqualTo(18);
        assertThat(evidence.read(document, captured.path("evidenceId").asText())).isEqualTo(captured);
        assertThat(captured.toString()).doesNotContain("credentialRef", "fixture-secret-reference");
    }

    @Test void projectSelectorIsCursorPagedAndOnlyReturnsPublicManagedProjectSummaries() throws Exception {
        for (int i = 0; i < 3; i++) {
            projects.create("更多项目" + i, Files.createDirectory(root.resolve("more-" + i)).toString(), "用于分页");
        }
        projects.cancelManagement(secondProject);
        List<String> ids = new ArrayList<>(); String cursor = null;
        do {
            var request = get("/api/ppt/projects").param("limit", "2");
            if (cursor != null) request.param("cursor", cursor);
            JsonNode page = json.readTree(http.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertThat(page.path("items").size()).isBetween(1, 2);
            for (JsonNode item : page.path("items")) {
                assertThat(item.size()).isEqualTo(3);
                assertThat(item.has("id") && item.has("name") && item.has("description")).isTrue();
                ids.add(item.path("id").asText());
            }
            cursor = page.path("nextCursor").isNull() || page.path("nextCursor").isMissingNode()
                    ? null : page.path("nextCursor").asText();
            assertThat(ids.size()).isLessThanOrEqualTo(4);
        } while (cursor != null);
        assertThat(ids).hasSize(4).doesNotHaveDuplicates().contains(firstProject).doesNotContain(secondProject);
    }

    private String create(String project) {
        return documents.create(new PptDocuments.Create(key(), "项目汇报", project, "fake/model")).id();
    }
    private String upload(String project, String name, String text) {
        var source = sources.upload(project, List.of(new KnowledgeSources.Incoming(name, text.getBytes(StandardCharsets.UTF_8)))).getFirst();
        assertThat(source.state()).isEqualTo("READY"); return source.id();
    }
    private String run(String document) {
        String id = key();
        jdbc.update("INSERT INTO ppt_agent_run(id,document_id,idempotency_key,input_sha,user_text,scope_json,source_revision,phase,model_json,root_path,context_json,state,message_id,created_at,updated_at) "
                        + "VALUES(?,?,?,?,?,'{}',?,'BRIEFING','{}',?,'{}','RUNNING',?,'now','now')",
                id, document, key(), "a".repeat(64), "读取项目资料用于汇报", documents.get(document).revision(), root.toString(), "msg_" + id);
        return id;
    }
    private JsonNode invoke(String document, String run, String tool, Map<String,Object> arguments) {
        ObjectNode args = json.valueToTree(arguments); args.put("agentRunId", run);
        return json.valueToTree(tools.invoke(document, tool, args, () -> { }));
    }
    private List<String> sourceIds(JsonNode view) {
        var result = new ArrayList<String>(); view.path("sources").forEach(source -> result.add(source.path("id").asText())); return result;
    }
    private int evidenceCount(String document) {
        return jdbc.queryForObject("SELECT count(*) FROM ppt_knowledge_evidence WHERE document_id=?", Integer.class, document);
    }
    private String database(String project, String name) {
        String id = key();
        var config = new DatabaseConfig(DatabaseConfig.Type.MYSQL, "localhost", 3306, "app", "reader", "fixture.jar", "fixture.Driver",
                List.of("app"), Map.of(), 1, 100);
        jdbc.update("INSERT INTO database_connection(id,name,config_json,credential_ref,enabled,archived,version,created_at,updated_at) VALUES(?,?,?,?,1,0,0,'now','now')",
                id, name, json.writeValueAsString(config), "fixture-secret-reference");
        jdbc.update("INSERT INTO database_connection_project(connection_id,project_id) VALUES(?,?)", id, project); return id;
    }
    private String git(String... args) throws Exception {
        var command = new ArrayList<>(List.of("git", "-c", "core.hooksPath=" + root.resolve("no-hooks")));
        command.addAll(List.of(args));
        var process = new ProcessBuilder(command).directory(firstRoot.toFile()).redirectErrorStream(true).start();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly(); assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
            fail("Git fixture did not finish within 10 seconds");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.exitValue()).as(output).isZero(); return output;
    }
    private String key() { return UUID.randomUUID().toString(); }
}
