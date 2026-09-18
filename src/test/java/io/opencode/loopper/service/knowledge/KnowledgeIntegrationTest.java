package io.opencode.loopper.service.knowledge;

import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.runtime.*;
import io.opencode.loopper.service.*;
import io.opencode.loopper.service.assist.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(classes=LoopperApplication.class, properties={"loopper.opencode.mode=fake", "loopper.scheduling.enabled=false", "loopper.startup-recovery.enabled=false"})
class KnowledgeIntegrationTest {
    private static final Path DATA = data();
    private static Path data() { try { return Files.createTempDirectory("knowledge-it-"); } catch (Exception e) { throw new IllegalStateException(e); } }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("loopper.data-dir", DATA::toString);
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATA.resolve("test.db") + "?foreign_keys=on&journal_mode=WAL&transaction_mode=IMMEDIATE");
    }
    @Autowired io.opencode.loopper.persistence.KnowledgeV2Mapper v2;
    @Autowired Flyway flyway; @Autowired ProjectService projects; @Autowired KnowledgeSources sources;
    @Autowired KnowledgeConversations conversations; @Autowired KnowledgePersistence persistence; @Autowired KnowledgeMapper mapper;
    @Autowired KnowledgeReader reader; @Autowired ObjectMapper json; @Autowired LoopperProperties properties;
    @Autowired KnowledgeSearchService search;
    @Autowired KnowledgeResearchMapper researchMapper;
    @Autowired KnowledgeEventHub events; @Autowired AssistMapper assist; @Autowired AssistScopeService scopes;
    @Autowired AssistToolService tools; @Autowired InternalMcpRuntimeAccess runtime; @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @org.springframework.test.context.bean.override.mockito.MockitoBean OpenCodeModelCatalogService catalog;
    @TempDir Path root;
    FakeOpenCodeClient remote; KnowledgeCoordinator coordinator; String project;
    @BeforeEach void prepare() throws Exception {
        flyway.clean(); flyway.migrate(); root = root.toRealPath(); Files.writeString(root.resolve("当前.md"), "# 业务\n付款必须审批\n");
        project = projects.create("知识项目", root.toString()).id(); remote = spy(new FakeOpenCodeClient());
        remote.holdProfileOpen(OpenCodeClient.SessionProfile.KNOWLEDGE_READ_ONLY, true);
        remote.holdProfileOpen(OpenCodeClient.SessionProfile.KNOWLEDGE_INTERACTIVE_READ_ONLY, true);
        remote.holdProfileOpen(OpenCodeClient.SessionProfile.KNOWLEDGE_RESEARCH_READ_ONLY, true);
        remote.holdProfileOpen(OpenCodeClient.SessionProfile.KNOWLEDGE_RESEARCH_INTERACTIVE_READ_ONLY, true);
        coordinator = new KnowledgeCoordinator(mapper, persistence, remote, json, properties, events, new KnowledgeQuestions(v2, mapper, remote, json, events), v2, new KnowledgeResearch(researchMapper, mapper, remote, json));
    }
    @AfterEach void close() { coordinator.close(); }
    KnowledgeConversations.View create(List<String> ids) {
        var view = conversations.create(new KnowledgeConversations.Create(UUID.randomUUID().toString(), project, "如何付款", "fake/model", ids));
        jdbc.update("UPDATE knowledge_conversation_options SET contract_version=2 WHERE conversation_id=?", view.id());
        return conversations.get(view.id());
    }
    KnowledgeRows.Turn run(String id) {
        var turn = persistence.begin(id, UUID.randomUUID().toString(), "付款逻辑？"); coordinator.tick(id); coordinator.tick(id);
        return mapper.turn(turn.id()).orElseThrow();
    }
    @Test void defaultModelDoesNotDiscoverCatalogButExplicitAlternativesAreValidated() {
        String oldModel = properties.getOpenCode().getModel(), oldMode = properties.getOpenCode().getMode();
        try {
            properties.getOpenCode().setMode("managed"); properties.getOpenCode().setModel("global/default");
            for (String selection : new String[]{null, "global/default"}) {
                var view = conversations.create(new KnowledgeConversations.Create(UUID.randomUUID().toString(), project, "默认模型", selection, List.of("code")));
                assertThat(view.model()).isEqualTo("global/default");
            }
            verifyNoInteractions(catalog);
            when(catalog.discover(any())).thenReturn(List.of(new OpenCodeModelCatalogService.AvailableModel("other/selected", "other", "selected", "other")));
            var input = new KnowledgeConversations.Create(UUID.randomUUID().toString(), project, "切换模型", "other/selected", List.of("code"));
            assertThat(conversations.create(input).model()).isEqualTo("other/selected");
            assertThatThrownBy(() -> conversations.create(new KnowledgeConversations.Create(UUID.randomUUID().toString(), project, "非法选择", "unknown/model", List.of("code"))))
                    .isInstanceOf(BadRequestException.class);
            assertThat(conversations.create(input).model()).isEqualTo("other/selected");
            verify(catalog, times(2)).discover(any());
        } finally { properties.getOpenCode().setMode(oldMode); properties.getOpenCode().setModel(oldModel); }
    }

    private KnowledgeConversations.View researchChat() {
        return conversations.create(new KnowledgeConversations.Create(UUID.randomUUID().toString(), project, "调查实现", "fake/model", List.of("code")));
    }
    private void completedAnswer() {
        doReturn(new OpenCodeClient.SessionStatus("COMPLETED")).when(remote).sessionStatus(any());
        doReturn("已读取代码的回答").when(remote).sessionLiveOutput(any());
        doReturn(new OpenCodeClient.SessionResult("已读取代码的回答", Map.of(), null, null, 0)).when(remote).sessionResult(any());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"empty", "pending", "truncated", "unavailable"})
    void nativeResearchCompletesWithoutAutomaticPromptsRegardlessOfTodoState(String todoState) {
        var chat = researchChat(); var turn = run(chat.id());
        assertThat(chat.options().contractVersion()).isEqualTo(3);
        assertThat(json.readTree(mapper.conversation(chat.id()).orElseThrow().planJson()).path("profile").asText()).startsWith("KNOWLEDGE_RESEARCH_");
        completedAnswer();
        doReturn(new OpenCodeClient.SessionTranscript(List.of(new OpenCodeClient.SessionPart("native-read", "TOOL", "read", "实际源码", "completed"))))
                .when(remote).sessionTranscript(any());
        var todos = todoState.equals("pending")
                ? List.of(new OpenCodeClient.SessionTodo("todo", "追踪数据库分支", "pending", "high", 0, Map.of())) : List.<OpenCodeClient.SessionTodo>of();
        doReturn(todoState.equals("unavailable") ? null : new OpenCodeClient.SessionTodoSnapshot(todos, todoState.equals("truncated"), null))
                .when(remote).sessionTodoSnapshot(any());
        coordinator.tick(chat.id());
        assertThat(mapper.turn(turn.id()).orElseThrow().state()).isEqualTo("COMPLETED");
        assertThat(mapper.turn(turn.id()).orElseThrow().answer()).isEqualTo("已读取代码的回答");
        assertThat(researchMapper.latest(turn.id())).isNull();
        assertThat(mapper.calls(chat.id(), turn.id())).anyMatch(c -> c.tool().equals("read") && c.state().equals("SUCCEEDED"));
        assertThat(mapper.citationCount(turn.id())).isZero();
        coordinator.tick(chat.id()); coordinator.tick(chat.id());
        verify(remote, times(1)).promptAsync(any(), any(OpenCodeClient.PromptRequest.class));
        verify(remote, never()).sessionTodoSnapshot(any());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task", Integer.class)).isZero();
    }
    private void prepareLegacyContinuation(KnowledgeRows.Turn turn) {
        var request = new OpenCodeClient.PromptRequest("升级前已登记的自查请求", json.readTree(turn.requestJson()).path("system").asText(),
                "build", new OpenCodeClient.ResponseFormat.Text(), "msg_legacy_" + UUID.randomUUID(), List.of());
        String now = Instant.now().toString();
        assertThat(researchMapper.prepare(new KnowledgeResearchMapper.Round(turn.id(), 1, request.messageId(), "PREPARED",
                json.writeValueAsString(request), OpenCodeClient.promptRequestSha256(request), "", now, now, 0), turn.version(), 0)).isEqualTo(1);
    }
    @Test void uncertainLegacyContinuationRecoversTheExactMessageAfterRestartWithoutResendOrFurtherPrompts() {
        var chat = researchChat(); var turn = run(chat.id()); prepareLegacyContinuation(turn); completedAnswer();
        String continuation = researchMapper.latest(turn.id()).messageId();
        doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("lost follow-up acknowledgement"); })
                .when(remote).promptAsync(any(), any(OpenCodeClient.PromptRequest.class));
        coordinator.tick(chat.id()); assertThat(researchMapper.latest(turn.id()).state()).isEqualTo("UNKNOWN");
        coordinator.close(); coordinator = new KnowledgeCoordinator(mapper, persistence, remote, json, properties, events,
                new KnowledgeQuestions(v2, mapper, remote, json, events), v2, new KnowledgeResearch(researchMapper, mapper, remote, json));
        coordinator.tick(chat.id());
        assertThat(researchMapper.latest(turn.id()).messageId()).isEqualTo(continuation);
        assertThat(researchMapper.latest(turn.id()).state()).isEqualTo("RUNNING");
        verify(remote, times(2)).promptAsync(any(), any(OpenCodeClient.PromptRequest.class));
        coordinator.tick(chat.id()); assertThat(mapper.turn(turn.id()).orElseThrow().state()).isEqualTo("COMPLETED");
        assertThat(researchMapper.latest(turn.id()).state()).isEqualTo("COMPLETED");
        assertThat(researchMapper.latest(turn.id()).ordinal()).isEqualTo(1);
        coordinator.tick(chat.id());
        verify(remote, times(2)).promptAsync(any(), any(OpenCodeClient.PromptRequest.class));
        verify(remote, never()).sessionTodoSnapshot(any());
    }
    @Test void stoppingPreparedLegacyContinuationPreventsDispatchAndRequiresOriginalSessionStopProof() {
        var chat = researchChat(); var turn = run(chat.id()); prepareLegacyContinuation(turn); completedAnswer();
        assertThat(researchMapper.latest(turn.id()).state()).isEqualTo("PREPARED");
        persistence.stop(chat.id());
        doThrow(new IllegalStateException("unconfirmed")).when(remote).abortWithConfirmation(any()); coordinator.tick(chat.id());
        assertThat(mapper.turn(turn.id()).orElseThrow().state()).isEqualTo("STOPPING");
        verify(remote, times(1)).promptAsync(any(), any(OpenCodeClient.PromptRequest.class));
        doReturn(OpenCodeClient.AbortConfirmation.ACKNOWLEDGED).when(remote).abortWithConfirmation(any()); coordinator.tick(chat.id());
        assertThat(mapper.turn(turn.id()).orElseThrow().state()).isEqualTo("STOPPED");
    }

    @Test void requestingPastEndOfFileReturnsAvailableLinesSoInvestigationCanContinue() throws Exception {
        Files.writeString(root.resolve("Small.java"), "class Small {\n  void run() {}\n}\n");
        var chat = researchChat(); var source = sources.selected(project, chat.id(), "code");
        var read = reader.readRange(source, "Small.java", -1, 1, 200, null, 0);
        assertThat(read.get("text")).asString().contains("void run()");
        assertThat(((Number) read.get("endLine")).intValue()).isLessThan(200);
    }
    @Test void unifiedSearchUsesFrozenSourcesDeduplicatesDocumentsAndReadsTheExactVersion() throws Exception {
        Files.writeString(root.resolve("Customer.java"), "class Customer { String customer_id; }\n");
        Files.writeString(root.resolve("字段.md"), "# 约定\ncustomerId 是客户编号\n");
        var chat = create(List.of("code", "documents"));
        var selection = sources.selection(project, chat.id(), null);
        var result = search.search("integration", selection, new KnowledgeSearchContracts.Request("customerId", "AUTO", null, null, null, 20, null));
        var node = json.valueToTree(result); assertThat(node.path("matches").size()).isEqualTo(2);
        assertThat(node.path("incomplete").asBoolean()).isFalse();
        var match = node.path("matches").get(0); var args = match.path("read").path("arguments");
        var bound = sources.selected(project, chat.id(), args.path("sourceId").asText());
        assertThat(reader.read(bound, args.path("path").asText(), args.path("section").asInt(-1), args.path("startLine").asInt(1), args.path("expectedSha").asText()).get("text")).isNotNull();
        Files.writeString(root.resolve(args.path("path").asText()), "changed");
        assertThatThrownBy(() -> reader.read(bound, args.path("path").asText(), args.path("section").asInt(-1), 1, args.path("expectedSha").asText())).isInstanceOf(AssistFailure.class);
        var extra = sources.addDirectory(project, Files.createDirectory(root.resolve("external")).toString());
        assertThatThrownBy(() -> sources.selection(project, chat.id(), List.of(extra.id()))).hasMessageContaining("授权");
        assertThatThrownBy(() -> sources.selection("other-project", chat.id(), null)).hasMessageContaining("不属于");
    }
    @Test void thinkingStreamsPersistsAcrossReadAndStopAndRejectsLateSnapshots() {
        var chat = create(List.of("code")); var turn = run(chat.id());
        doReturn(new OpenCodeClient.SessionTranscript(List.of(
                new OpenCodeClient.SessionPart("r1", "THINKING", "Thinking", "检查当前项目", null),
                new OpenCodeClient.SessionPart("t1", "TOOL", "read", "不应混入思考的工具参数", null),
                new OpenCodeClient.SessionPart("r2", "THINKING", "Thinking", "核对实际代码", null)))).when(remote).sessionTranscript(any());
        doReturn("部分回答").when(remote).sessionLiveOutput(any()); coordinator.tick(chat.id());
        var saved = mapper.turn(turn.id()).orElseThrow();
        assertThat(saved.thinking()).isEqualTo("检查当前项目\n\n核对实际代码");
        assertThat(conversations.messages(chat.id(), null, 50).items().getFirst().thinking()).isEqualTo(saved.thinking());
        doThrow(new IllegalStateException("temporary transcript failure")).when(remote).sessionTranscript(any());
        doReturn("更新回答").when(remote).sessionLiveOutput(any()); coordinator.tick(chat.id());
        assertThat(mapper.turn(turn.id()).orElseThrow().answer()).isEqualTo("更新回答");
        assertThat(mapper.turn(turn.id()).orElseThrow().thinking()).isEqualTo(saved.thinking());
        persistence.stop(chat.id()); coordinator.tick(chat.id());
        var stopped = mapper.turn(turn.id()).orElseThrow(); assertThat(stopped.state()).isEqualTo("STOPPED");
        assertThat(mapper.output(turn.id(), saved.version(), "迟到回答", "迟到思考", Instant.now().toString())).isZero();
        assertThat(mapper.output(turn.id(), stopped.version(), "终态改写", "终态思考", Instant.now().toString())).isZero();
        assertThat(conversations.messages(chat.id(), null, 50).items().getFirst().thinking()).isEqualTo(saved.thinking());
        var next = run(chat.id()); assertThat(next.thinking()).isEmpty();
    }
    @Test void isolatedMultiTurnAndRestartRecoveryNeverCreatesTaskOrResendsPrompt() {
        var chat = create(List.of("code", "documents")); var first = run(chat.id());
        assertThat(first.state()).isEqualTo("RUNNING");
        assertThat(conversations.receipt(chat.id(), first.idempotencyKey()).get("accepted")).isEqualTo(true);
        assertThat(conversations.receipt(chat.id(), UUID.randomUUID().toString()).get("accepted")).isEqualTo(false);
        mapper.usage(first.id(), 2400L, 600L); assertThat(conversations.get(chat.id()).usage().inputTokens()).isEqualTo(2400L);
        mapper.usage(first.id(), 1200L, null); assertThat(conversations.get(chat.id()).usage().inputTokens()).isEqualTo(2400L);
        assertThat(conversations.get(chat.id()).usage().outputTokens()).isEqualTo(600L);
        assertThat(persistence.begin(chat.id(), first.idempotencyKey(), first.userText()).id()).isEqualTo(first.id());
        assertThatThrownBy(() -> persistence.begin(chat.id(), first.idempotencyKey(), "changed")).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> persistence.begin(chat.id(), UUID.randomUUID().toString(), "next")).isInstanceOf(ConflictException.class);
        doReturn(new OpenCodeClient.SessionStatus("COMPLETED")).when(remote).sessionStatus(any());
        doReturn("答案 [1](knowledge:fake-id)").when(remote).sessionLiveOutput(any());
        doReturn(new OpenCodeClient.SessionResult("答案 [1](knowledge:fake-id)", Map.of(), null, null, 0)).when(remote).sessionResult(any());
        coordinator.close(); coordinator = new KnowledgeCoordinator(mapper, persistence, remote, json, properties, events, new KnowledgeQuestions(v2, mapper, remote, json, events), v2, new KnowledgeResearch(researchMapper, mapper, remote, json)); coordinator.tick(chat.id());
        assertThat(conversations.get(chat.id()).state()).isEqualTo("IDLE");
        assertThat(conversations.messages(chat.id(), null, 50).items().getFirst().answer()).contains("引用未核实").doesNotContain("knowledge:");
        run(chat.id()); verify(remote, times(1)).createSession(any(OpenCodeClient.SessionCreationPlan.class));
        verify(remote, times(2)).promptAsync(any(), any(OpenCodeClient.PromptRequest.class));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM designer_session", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM state_transition_event WHERE machine_type='KNOWLEDGE_TURN'", Integer.class)).isGreaterThan(3);
    }
    @Test void ambiguousSendIsReconciledAndStopNeedsPositiveProof() {
        var chat = create(List.of("code"));
        doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("lost response"); }).when(remote).promptAsync(any(), any(OpenCodeClient.PromptRequest.class));
        var turn = run(chat.id()); assertThat(turn.state()).isEqualTo("UNKNOWN");
        coordinator.tick(chat.id()); assertThat(mapper.turn(turn.id()).orElseThrow().state()).isEqualTo("RUNNING");
        verify(remote, times(1)).promptAsync(any(), any(OpenCodeClient.PromptRequest.class));
        persistence.stop(chat.id()); doThrow(new IllegalStateException("not confirmed")).when(remote).abortWithConfirmation(any()); coordinator.tick(chat.id());
        assertThat(conversations.get(chat.id()).state()).isEqualTo("STOPPING");
        assertThatThrownBy(() -> persistence.begin(chat.id(), UUID.randomUUID().toString(), "next")).isInstanceOf(ConflictException.class);
        doReturn(OpenCodeClient.AbortConfirmation.ACKNOWLEDGED).when(remote).abortWithConfirmation(any()); coordinator.tick(chat.id());
        assertThat(mapper.turn(turn.id()).orElseThrow().state()).isEqualTo("STOPPED");
        assertThat(conversations.get(chat.id()).state()).isEqualTo("IDLE");
        assertThat(persistence.begin(chat.id(), UUID.randomUUID().toString(), "next").state()).isEqualTo("PREPARED");
    }
    @Test void unknownCreationUsesExactIdentityAndStopsWithoutBlindRecreation() {
        var chat = create(List.of("code"));
        doThrow(new IllegalStateException("lost create")).when(remote).createSession(any(OpenCodeClient.SessionCreationPlan.class));
        var turn = persistence.begin(chat.id(), UUID.randomUUID().toString(), "问题"); coordinator.tick(chat.id()); coordinator.tick(chat.id());
        assertThat(mapper.turn(turn.id()).orElseThrow().state()).isEqualTo("CREATE_UNKNOWN");
        verify(remote, times(1)).createSession(any(OpenCodeClient.SessionCreationPlan.class));
        persistence.stop(chat.id()); coordinator.tick(chat.id());
        assertThat(mapper.turn(turn.id()).orElseThrow().state()).isEqualTo("STOPPED"); assertThat(mapper.conversation(chat.id()).orElseThrow().planJson()).isNull();
        doCallRealMethod().when(remote).createSession(any(OpenCodeClient.SessionCreationPlan.class)); run(chat.id());
        assertThat(conversations.get(chat.id()).state()).isEqualTo("RUNNING");
    }
    @Test void sourceReadsCurrentLocalChangesButCannotEscapeProjectOrReadSensitiveFiles() throws Exception {
        Files.writeString(root.resolve("Live.java"), "class Live { int version = 1; }\n"); Files.writeString(root.resolve(".env"), "PRIVATE_VALUE=do-not-read");
        Files.createDirectory(root.resolve("target")); Files.writeString(root.resolve("target/Generated.java"), "generated");
        Path sibling = Files.createTempDirectory(root.getParent(), "sibling"); Files.writeString(sibling.resolve("secret.md"), "private"); Files.createSymbolicLink(root.resolve("outside"), sibling);
        var chat = create(List.of("code")); var source = sources.selected(project, chat.id(), "code");
        var original = reader.read(source, "Live.java", -1, 1, null); Files.writeString(root.resolve("Live.java"), "class Live { int version = 2; }\n");
        var updated = reader.read(source, "Live.java", -1, 1, null); assertThat(updated.get("text")).asString().contains("version = 2"); assertThat(updated.get("sha256")).isNotEqualTo(original.get("sha256"));
        assertThatThrownBy(() -> reader.read(source, "Live.java", -1, 1, original.get("sha256").toString())).isInstanceOf(AssistFailure.class).hasMessageContaining("变化");
        for (String path : List.of(".env", "target/Generated.java", "outside/secret.md", "../secret.md")) assertThatThrownBy(() -> reader.read(source, path, -1, 1, null)).isInstanceOf(AssistFailure.class);
        assertThat(reader.browse(source, "", "", null).items()).extracting(KnowledgeFiles.Entry::name).doesNotContain(".env", "target", "outside");
        assertThat(reader.search(source, "", "审批", null).get("matches")).asList().hasSize(1);
    }
    @Test void citationIsDurableAndScopedAndToolsCannotExpandAuthorization() throws Exception {
        var uploaded = sources.upload(project, List.of(new KnowledgeSources.Incoming("说明.md", "# 付款\n先审批再付款".getBytes(java.nio.charset.StandardCharsets.UTF_8)))).getFirst();
        var chat = create(List.of(uploaded.id())); var turn = run(chat.id()); String session = mapper.conversation(chat.id()).orElseThrow().remoteId();
        var credentials = runtime.current().orElseGet(() -> new InternalMcpCredentialProvider(() -> 19000).issue()); runtime.activate(credentials);
        assist.insertSession(new AssistMapper.Session(session, credentials.generation(), root.toString(), "KNOWLEDGE_READ_ONLY", "[]", json.writeValueAsString(AssistToolCatalog.allowed("KNOWLEDGE_READ_ONLY")), Instant.now().toString()));
        String grant = scopes.grant(session); assertThat(grant).isNotBlank();
        var result = tools.call("read_knowledge_source", Map.of("scope", grant, "sourceId", uploaded.id(), "section", 0));
        assertThat(result.error()).as(result.content().toString()).isFalse(); String citation = result.content().get("citationId").toString();
        sources.remove(project, uploaded.id(), uploaded.version());
        assertThat(conversations.citation(chat.id(), citation).toString()).contains("先审批再付款");
        var other = create(List.of("code")); assertThatThrownBy(() -> conversations.citation(other.id(), citation)).isInstanceOf(NotFoundException.class);
        assertThat(tools.call("read_knowledge_source", Map.of("scope", grant, "sourceId", "code", "path", "Live.java")).error()).isTrue();
        assertThat(tools.call("generate_word", Map.of("scope", grant, "source", "workspace:a.md", "target", "out.docx", "idempotencyKey", "x")).error()).isTrue();
        assertThat(tools.call("query_database_readonly", Map.of("scope", grant, "connectionId", "not-authorized", "sql", "SELECT 1")).error()).isTrue();
        persistence.stop(chat.id()); assertThat(tools.call("list_knowledge_sources", Map.of("scope", grant)).error()).isTrue();
        assertThat(mapper.citations(chat.id(), turn.id())).hasSize(1);
    }
    @Test void citationStorageLimitDoesNotStopReadingTheNextPieceOfEvidence() throws Exception {
        Files.writeString(root.resolve("Next.java"), "class Next { // 继续追踪实际实现\n}\n");
        var chat = create(List.of("code")); var turn = run(chat.id());
        String session = mapper.conversation(chat.id()).orElseThrow().remoteId();
        var credentials = runtime.current().orElseGet(() -> new InternalMcpCredentialProvider(() -> 19000).issue()); runtime.activate(credentials);
        assist.insertSession(new AssistMapper.Session(session, credentials.generation(), root.toString(), "KNOWLEDGE_READ_ONLY", "[]",
                json.writeValueAsString(AssistToolCatalog.allowed("KNOWLEDGE_READ_ONLY")), Instant.now().toString()));
        for (int i = 0; i < 100; i++) assertThat(mapper.cite(new KnowledgeRows.Citation(UUID.randomUUID().toString(), chat.id(), turn.id(),
                "CODE", "code", "Earlier.java", "第1行", "a".repeat(64), "{}", Instant.now().toString()))).isEqualTo(1);
        var result = tools.call("read_knowledge_source", Map.of("scope", scopes.grant(session), "sourceId", "code", "path", "Next.java"));
        assertThat(result.error()).isFalse();
        assertThat(result.content().get("text")).asString().contains("继续追踪实际实现");
        assertThat(result.content()).containsEntry("citationStatus", "LIMIT_REACHED").doesNotContainKey("citationId");
        assertThat(mapper.citationCount(turn.id())).isEqualTo(100);
        assertThat(mapper.calls(chat.id(), turn.id())).allMatch(c -> c.state().equals("SUCCEEDED"));
    }
    @Test void fiveFormatsUseTheSameScopedReaderAndInterruptedUploadRecovers() throws Exception {
        var incoming = new ArrayList<KnowledgeSources.Incoming>();
        incoming.add(new KnowledgeSources.Incoming("a.md", "Markdown 付款".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        try(var doc=new org.apache.poi.xwpf.usermodel.XWPFDocument();var out=new java.io.ByteArrayOutputStream()) { doc.createParagraph().createRun().setText("Word 付款");doc.write(out);incoming.add(new KnowledgeSources.Incoming("a.docx",out.toByteArray())); }
        try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook();var out=new java.io.ByteArrayOutputStream()) { book.createSheet("付款").createRow(0).createCell(0).setCellValue("Excel");book.write(out);incoming.add(new KnowledgeSources.Incoming("a.xlsx",out.toByteArray())); }
        try(var slides=new org.apache.poi.xslf.usermodel.XMLSlideShow();var out=new java.io.ByteArrayOutputStream()) { slides.createSlide().createTextBox().setText("Slides 付款");slides.write(out);incoming.add(new KnowledgeSources.Incoming("a.pptx",out.toByteArray())); }
        try(var pdf=new org.apache.pdfbox.pdmodel.PDDocument();var out=new java.io.ByteArrayOutputStream()) {
            var page=new org.apache.pdfbox.pdmodel.PDPage();pdf.addPage(page);try(var stream=new org.apache.pdfbox.pdmodel.PDPageContentStream(pdf,page)) { stream.beginText();stream.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA),12);stream.showText("Payment PDF");stream.endText(); }
            pdf.save(out);incoming.add(new KnowledgeSources.Incoming("a.pdf",out.toByteArray()));
        }
        var uploaded=sources.upload(project,incoming);assertThat(uploaded).allMatch(s -> s.state().equals("READY"));
        for(var source:uploaded) assertThat(reader.read(sources.selected(project,null,source.id()),"",0,1,null).get("text")).asString().isNotBlank();
        String id=UUID.randomUUID().toString();byte[] bytes="恢复上传".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path target=DATA.toRealPath().resolve("knowledge/files/"+id+".md");Files.write(target.resolveSibling(target.getFileName()+".part"),bytes);
        mapper.insertSource(new KnowledgeRows.Source(id,project,"UPLOAD","恢复.md",target.toString(),AssistFiles.sha(bytes),"PREPARED","", "2020-01-01T00:00:00Z", "2020-01-01T00:00:00Z",0));
        sources.recoverUploads();assertThat(mapper.source(project,id).orElseThrow().state()).isEqualTo("READY");assertThat(Files.readAllBytes(target)).isEqualTo(bytes);
    }
    @Test void duplicateUploadFailureIsolationAndPagination() {
        var files = List.of(new KnowledgeSources.Incoming("有效.md", "付款规则".getBytes(java.nio.charset.StandardCharsets.UTF_8)), new KnowledgeSources.Incoming("坏文件.docx", "broken".getBytes()));
        var uploaded = sources.upload(project, files); assertThat(uploaded).extracting(KnowledgeSources.View::state).containsExactly("READY", "FAILED");
        assertThat(sources.upload(project, files.subList(0,1)).getFirst().id()).isEqualTo(uploaded.getFirst().id());
        var chat = create(List.of("code"));
        for (int i=0;i<3;i++) { var turn = persistence.begin(chat.id(), "request-key-000000"+i, "问题"+i); persistence.finish(turn, "FAILED", "未发送"); }
        var first = conversations.messages(chat.id(), null, 2); var second = conversations.messages(chat.id(), first.nextCursor(), 2);
        assertThat(first.items()).extracting(KnowledgeConversations.Message::ordinal).containsExactly(2,3); assertThat(second.items()).extracting(KnowledgeConversations.Message::ordinal).containsExactly(1); assertThat(second.nextCursor()).isNull();
        assertThat(first.items()).extracting(KnowledgeConversations.Message::id).doesNotContain(second.items().getFirst().id());
    }
    @Test void projectDocumentPathIsIndependentFrozenAndUnavailableDirectoriesAreVisible() throws Exception {
        Path documents = Files.createTempDirectory(root.getParent(), "project-docs").toRealPath();
        Files.writeString(documents.resolve("report.md"), "# 报告\n项目审批结论\n");
        projects.updateDocumentPath(project, documents.toString(), projects.get(project).version());
        assertThat(sources.list(project, null, 50).items()).anyMatch(source -> source.id().equals("project-documents") && source.state().equals("READY"));
        var chat = create(List.of("project-documents"));
        var original = sources.selected(project, chat.id(), "project-documents");
        assertThat(reader.read(original, "report.md", 0, 1, null).get("text")).asString().contains("项目审批结论");
        projects.updateDocumentPath(project, root.resolve("not-yet-created").toString(), projects.get(project).version());
        assertThat(sources.list(project, null, 50).items()).anyMatch(source -> source.id().equals("project-documents") && source.state().equals("FAILED"));
        assertThat(sources.selected(project, chat.id(), "project-documents").path()).isEqualTo(documents.toString());
        assertThat(Files.exists(root.resolve("not-yet-created"))).isFalse();
    }
    @Test void historyFiltersAndArchivePreserveExecutionAndEvidence() {
        var one = create(List.of("code")); var two = conversations.create(new KnowledgeConversations.Create(UUID.randomUUID().toString(), project, "不同标题", "fake/model", List.of("code")));
        var turn = run(one.id()); mapper.answer(turn.id(), turn.version(), "正文中的付款关键词", Instant.now().toString());
        var found = conversations.list(project, null, 1, "active", "", "付款关键词", "", "");
        assertThat(found.items()).extracting(KnowledgeConversations.View::id).containsExactly(one.id());
        var archived = conversations.archive(one.id(), true, one.options().version());
        assertThat(archived.state()).isEqualTo("RUNNING"); assertThat(archived.options().archivedAt()).isNotNull();
        assertThat(conversations.list(project, null, 50).items()).extracting(KnowledgeConversations.View::id).containsExactly(two.id());
        assertThat(conversations.list(project, null, 50, "archived", "", "", "", "").items()).extracting(KnowledgeConversations.View::id).containsExactly(one.id());
        assertThatThrownBy(() -> conversations.archive(one.id(), false, 0)).isInstanceOf(ConflictException.class);
        conversations.archive(one.id(), false, archived.options().version());
        var page = conversations.list(project, null, 1); var next = conversations.list(project, page.nextCursor(), 1);
        assertThat(next.items()).hasSize(1); assertThat(next.items().getFirst().id()).isNotEqualTo(page.items().getFirst().id());
        assertThat(conversations.messages(one.id(), null, 50).items().getFirst().answer()).contains("付款关键词");
    }
    @Test void questionReplySurvivesReloadAndIsDeliveredOnceToOriginalTurn() {
        var chat = create(List.of("code")); var turn = run(chat.id()); String session = mapper.conversation(chat.id()).orElseThrow().remoteId();
        remote.setPendingQuestion(session, new OpenCodeClient.PendingQuestion("native-question", session, List.of(new OpenCodeClient.QuestionPrompt("选择作者", "作者", List.of(new OpenCodeClient.QuestionOption("张三", "身份 A")), false, true))));
        coordinator.tick(chat.id()); var question = conversations.messages(chat.id(), null, 50).items().getFirst().questions().getFirst();
        assertThat(question.state()).isEqualTo("PENDING"); assertThat(mapper.active(chat.id()).orElseThrow().id()).isEqualTo(turn.id());
        assertThat(conversations.list(project, null, 50, "active", "WAITING_INPUT", "", "", "").items()).hasSize(1);
        var service = new KnowledgeQuestions(v2, mapper, remote, json, events);
        var reply = new KnowledgeQuestions.Reply(UUID.randomUUID().toString(), List.of(List.of("张三")), question.version());
        assertThat(service.reply(chat.id(), question.id(), reply).state()).isEqualTo("PREPARED");
        coordinator.close(); coordinator = new KnowledgeCoordinator(mapper, persistence, remote, json, properties, events, service, v2, new KnowledgeResearch(researchMapper, mapper, remote, json));
        coordinator.tick(chat.id()); coordinator.tick(chat.id());
        assertThat(service.reply(chat.id(), question.id(), reply).state()).isEqualTo("ANSWERED");
        verify(remote, times(1)).replyQuestion(any(), eq("native-question"), eq(List.of(List.of("张三"))));
        assertThatThrownBy(() -> service.reply(create(List.of("code")).id(), question.id(), reply)).isInstanceOf(AssistFailure.class);
    }
    @Test void lostQuestionReplyAndStopDoNotResendOrPretendItWasAcknowledged() {
        var chat = create(List.of("code")); run(chat.id()); String session = mapper.conversation(chat.id()).orElseThrow().remoteId();
        remote.setPendingQuestion(session, new OpenCodeClient.PendingQuestion("lost-question", session, List.of(new OpenCodeClient.QuestionPrompt("补充范围", "范围", List.of(), false, true))));
        coordinator.tick(chat.id()); var question = conversations.messages(chat.id(), null, 50).items().getFirst().questions().getFirst();
        var service = new KnowledgeQuestions(v2, mapper, remote, json, events);
        service.reply(chat.id(), question.id(), new KnowledgeQuestions.Reply(UUID.randomUUID().toString(), List.of(List.of("当前模块")), question.version()));
        doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("reply lost"); }).when(remote).replyQuestion(any(), anyString(), anyList());
        coordinator.tick(chat.id()); coordinator.tick(chat.id());
        assertThat(v2.question(chat.id(), question.id()).state()).isEqualTo("UNKNOWN");
        verify(remote, times(1)).replyQuestion(any(), anyString(), anyList());
        persistence.stop(chat.id()); coordinator.tick(chat.id()); assertThat(v2.question(chat.id(), question.id()).state()).isEqualTo("CLOSED");
        assertThat(conversations.get(chat.id()).state()).isEqualTo("IDLE");
    }
    @Test void validatesCitationRangeAgainstSavedEvidenceAndRejectsWrongUnitOrOverflow() {
        var chat = create(List.of("code")); var turn = run(chat.id()); String id = UUID.randomUUID().toString(), now = Instant.now().toString();
        var body = Map.of("kind", "CODE", "text", "one\ntwo\nthree", "startLine", 12, "endLine", 14);
        mapper.cite(new KnowledgeRows.Citation(id, chat.id(), turn.id(), "CODE", "code", "Example.java", "第12–14行", "a".repeat(64), json.writeValueAsString(body), now));
        String answer = "正确 [1](knowledge:" + id + "#L12-L13) 越界 [2](knowledge:" + id + "#L12-L99) 错类型 [3](knowledge:" + id + "#R12-R13)";
        mapper.answer(turn.id(), turn.version(), answer, now);
        assertThat(conversations.messages(chat.id(), null, 50).items().getFirst().answer()).contains("#L12-L13").doesNotContain("#L12-L99", "#R12-R13").contains("引用未核实");
    }

    @Test void gitSourceCannotBeUsedToReadUncommittedFilesThroughGenericTools() {
        var bound = new KnowledgeSources.Bound("git", "GIT", "仓库", root.toString(), "identity", "READY", "", 0);
        assertThatThrownBy(() -> reader.browse(bound, "", "", null)).hasMessageContaining("此来源不能");
        assertThatThrownBy(() -> reader.search(bound, "", "付款", null)).hasMessageContaining("此来源不能");
        assertThatThrownBy(() -> reader.read(bound, "README.md", 0, 1, null)).hasMessageContaining("此来源不能");
    }

}
