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
    @Autowired Flyway flyway; @Autowired ProjectService projects; @Autowired KnowledgeSources sources;
    @Autowired KnowledgeConversations conversations; @Autowired KnowledgePersistence persistence; @Autowired KnowledgeMapper mapper;
    @Autowired KnowledgeReader reader; @Autowired ObjectMapper json; @Autowired LoopperProperties properties;
    @Autowired KnowledgeEventHub events; @Autowired AssistMapper assist; @Autowired AssistScopeService scopes;
    @Autowired AssistToolService tools; @Autowired InternalMcpRuntimeAccess runtime; @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @org.springframework.test.context.bean.override.mockito.MockitoBean OpenCodeModelCatalogService catalog;
    @TempDir Path root;
    FakeOpenCodeClient remote; KnowledgeCoordinator coordinator; String project;
    @BeforeEach void prepare() throws Exception {
        flyway.clean(); flyway.migrate(); root = root.toRealPath(); Files.writeString(root.resolve("当前.md"), "# 业务\n付款必须审批\n");
        project = projects.create("知识项目", root.toString()).id(); remote = spy(new FakeOpenCodeClient());
        remote.holdProfileOpen(OpenCodeClient.SessionProfile.KNOWLEDGE_READ_ONLY, true);
        coordinator = new KnowledgeCoordinator(mapper, persistence, remote, json, properties, events);
    }
    @AfterEach void close() { coordinator.close(); }
    KnowledgeConversations.View create(List<String> ids) {
        return conversations.create(new KnowledgeConversations.Create(UUID.randomUUID().toString(), project, "如何付款", "fake/model", ids));
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
        assertThat(persistence.begin(chat.id(), first.idempotencyKey(), first.userText()).id()).isEqualTo(first.id());
        assertThatThrownBy(() -> persistence.begin(chat.id(), first.idempotencyKey(), "changed")).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> persistence.begin(chat.id(), UUID.randomUUID().toString(), "next")).isInstanceOf(ConflictException.class);
        doReturn(new OpenCodeClient.SessionStatus("COMPLETED")).when(remote).sessionStatus(any());
        doReturn("答案 [1](knowledge:fake-id)").when(remote).sessionLiveOutput(any());
        doReturn(new OpenCodeClient.SessionResult("答案 [1](knowledge:fake-id)", Map.of(), null, null, 0)).when(remote).sessionResult(any());
        coordinator.close(); coordinator = new KnowledgeCoordinator(mapper, persistence, remote, json, properties, events); coordinator.tick(chat.id());
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
}
