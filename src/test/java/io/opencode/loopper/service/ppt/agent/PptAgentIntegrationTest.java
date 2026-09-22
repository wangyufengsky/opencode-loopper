package io.opencode.loopper.service.ppt.agent;

import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.persistence.PptAgentRows.Run;
import io.opencode.loopper.runtime.*;
import io.opencode.loopper.service.ConflictException;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(classes=LoopperApplication.class, properties={"loopper.opencode.mode=fake", "loopper.scheduling.enabled=false", "loopper.startup-recovery.enabled=false"})
class PptAgentIntegrationTest {
    private static final Path DATA = data();
    private static Path data() { try { return Files.createTempDirectory("ppt-agent-it-"); } catch (Exception e) { throw new IllegalStateException(e); } }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("loopper.data-dir", DATA::toString);
        r.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATA.resolve("test.db") + "?foreign_keys=on&journal_mode=WAL&transaction_mode=IMMEDIATE");
    }
    @Autowired Flyway flyway;
    @Autowired PptAgentMapper mapper;
    @Autowired io.opencode.loopper.service.ppt.agent.PptAgentActivity activity;
    @Autowired PptAgentPersistence persistence;
    @Autowired PptAgentService service;
    @Autowired PptAgentTools tools;
    @Autowired PptAgentToolWrites toolWrites;
    @Autowired PptAgentAuthority authority;
    @Autowired io.opencode.loopper.service.ppt.PptEvents events;
    @Autowired PptRuntimeSupport scopes;
    @Autowired InternalMcpRuntimeAccess runtime;
    @Autowired ObjectMapper json;
    @Autowired LoopperProperties properties;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @MockitoBean PptAgentWorkspace workspace;
    @MockitoBean PptAgentCoordinator scheduler;
    @MockitoBean OwnedRuntimeGenerationStore generations;
    @TempDir Path root;
    FakeOpenCodeClient remote;
    PptAgentCoordinator coordinator;
    InternalMcpCredentialProvider.Credentials credential;
    String document;
    @BeforeEach void prepare() throws Exception {
        flyway.clean(); flyway.migrate(); document = UUID.randomUUID().toString(); root = root.toRealPath();
        jdbc.update("INSERT INTO ppt_document(id,title,model,phase,create_digest,created_at,updated_at) VALUES(?,?,?,'DESIGN',?,?,?)",
                document, "测试作品", "fake/model", "digest", "now", "now");
        when(workspace.workspace(document)).thenReturn(new PptAgentWorkspace.Workspace(document, "DESIGN", 0, "fake/model", root.resolve("agent"), json.createObjectNode()));
        when(workspace.invoke(eq(document), anyString(), any(), any())).thenAnswer(invocation -> {
            invocation.getArgument(3, Runnable.class).run(); return Map.of("revision", 1, "saved", true);
        });
        credential = new InternalMcpCredentialProvider(() -> 8080).issue(); runtime.activate(credential);
        remote = spy(new FakeOpenCodeClient()); remote.setManagedRuntime(credential.generation(), credential.serverName());
        remote.holdProfileOpen(OpenCodeClient.SessionProfile.PPT_AGENT, true);
        coordinator = new PptAgentCoordinator(mapper, persistence, remote, json, properties, workspace, authority, events, activity);
    }
    @AfterEach void close() { coordinator.close(); }
    PptAgentService.Send input(String key) { return new PptAgentService.Send(key, "只做一页项目收益", 0, json.valueToTree(Map.of("kind", "DOCUMENT"))); }
    Run start() {
        var message = service.send(document, input(UUID.randomUUID().toString()));
        coordinator.tick(document); coordinator.tick(document); return persistence.require(message.id());
    }
    Map<String,Object> call(Run run, Map<String,Object> args) {
        return Map.of("scope", scopes.grant(run), "runId", run.id(), "documentId", document, "args", args);
    }
    @Test void capturesProviderActivityAndKeepsItAcrossProjectionFailureAndStop() {
        var run = start();
        var transcript = new OpenCodeClient.SessionTranscript(List.of(
                new OpenCodeClient.SessionPart("thought", "THINKING", "思考", "检查页面结构", "completed"),
                new OpenCodeClient.SessionPart("tool", "TOOL", "private_ppt_get_context", "scope=do-not-display", "running")));
        doReturn(transcript).when(remote).sessionTranscript(any());
        coordinator.tick(document);
        var message = service.messages(document, null, 50).items().getFirst();
        assertThat(message.thinking()).isEqualTo("检查页面结构");
        assertThat(message.calls()).hasSize(1);
        assertThat(message.calls().getFirst().tool()).isEqualTo("ppt_get_context");
        assertThat(message.calls().getFirst().state()).isEqualTo("RUNNING");
        assertThat(message.calls().getFirst().detail()).isEmpty();
        doThrow(new IllegalStateException("temporarily unavailable")).when(remote).sessionTranscript(any());
        coordinator.tick(document);
        assertThat(service.message(persistence.require(run.id())).thinking()).isEqualTo("检查页面结构");
        service.stop(document); coordinator.tick(document);
        assertThat(service.message(persistence.require(run.id())).thinking()).isEqualTo("检查页面结构");
        activity.save(run, new OpenCodeClient.SessionTranscript(List.of(new OpenCodeClient.SessionPart("late", "THINKING", "", "迟到数据", "completed"))));
        assertThat(service.message(persistence.require(run.id())).thinking()).isEqualTo("检查页面结构");
        assertThat(persistence.require(run.id()).state()).isEqualTo("STOPPED");
    }
    @Test void activityKeepsThirtyCallsBoundsThinkingAndPreservesQuestionRounds() {
        var run = start(); var parts = new ArrayList<OpenCodeClient.SessionPart>();
        parts.add(new OpenCodeClient.SessionPart("thought", "THINKING", "", "思".repeat(64000), "completed"));
        parts.add(new OpenCodeClient.SessionPart("more-thought", "THINKING", "", "继续检查", "completed"));
        for (int i = 0; i < 35; i++) parts.add(new OpenCodeClient.SessionPart("tool-" + i, "TOOL", "private_ppt_measure_text", "", "completed"));
        activity.save(run, new OpenCodeClient.SessionTranscript(parts));
        var first = service.message(run); assertThat(first.thinking().length()).isLessThanOrEqualTo(64000); assertThat(first.calls()).hasSize(30);
        tools.call("ppt_request_input", call(run, Map.of("idempotencyKey", "activity-question", "prompt", "面向谁？", "options", List.of("管理层"))));
        var question = mapper.pending(run.id()).orElseThrow(); coordinator.tick(document); coordinator.tick(document);
        service.reply(document, question.id(), new PptAgentService.Reply("activity-reply", "管理层", 0, 0)); coordinator.tick(document);
        var resumed = persistence.require(run.id());
        activity.save(resumed, new OpenCodeClient.SessionTranscript(List.of(new OpenCodeClient.SessionPart("new", "TOOL", "private_ppt_apply_operations", "", "running"))));
        assertThat(service.message(resumed).thinking()).isEqualTo(first.thinking());
        activity.save(run, new OpenCodeClient.SessionTranscript(List.of(new OpenCodeClient.SessionPart("old", "THINKING", "", "旧轮次", "completed"))));
        assertThat(service.message(resumed).calls().getLast().tool()).isEqualTo("ppt_apply_operations");
    }
    @Test void sameKeyReplaysOneWriterAndFreezesDedicatedRoleWithoutPersistingScopeSecrets() {
        String key = UUID.randomUUID().toString(); var first = service.send(document, input(key));
        assertThat(service.send(document, input(key)).id()).isEqualTo(first.id());
        assertThatThrownBy(() -> service.send(document, input(UUID.randomUUID().toString()))).isInstanceOf(ConflictException.class);
        coordinator.tick(document); coordinator.tick(document);
        Run run = persistence.require(first.id());
        assertThat(run.state()).isEqualTo("RUNNING");
        assertThat(run.requestJson()).contains("loopper-ppt").doesNotContain("lpp_", "lpa_", credential.bearerToken());
        assertThat(json.readTree(run.planJson()).path("profile").asText()).isEqualTo("PPT_AGENT");
        assertThat(Files.isDirectory(root.resolve("agent"))).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task", Integer.class)).isZero();
    }
    @Test void createResponseLossRecoversExactSessionWithoutSecondCreate() {
        doAnswer(invocation -> { invocation.callRealMethod(); throw new IllegalStateException("lost response"); })
                .when(remote).createSession(any(OpenCodeClient.SessionCreationPlan.class));
        var message = service.send(document, input(UUID.randomUUID().toString())); coordinator.tick(document);
        assertThat(persistence.require(message.id()).state()).isEqualTo("CREATE_UNKNOWN");
        coordinator.tick(document); coordinator.tick(document);
        assertThat(persistence.require(message.id()).state()).isEqualTo("RUNNING");
        verify(remote, times(1)).createSession(any(OpenCodeClient.SessionCreationPlan.class));
    }
    @Test void unknownPromptIsRecoveredByExactMessageAndHashAndNeverBlindlyResent() {
        doAnswer(invocation -> { invocation.callRealMethod(); throw new IllegalStateException("lost response"); })
                .when(remote).promptAsync(any(), any(OpenCodeClient.PromptRequest.class));
        var run = start(); assertThat(run.state()).isEqualTo("UNKNOWN");
        coordinator.tick(document); assertThat(persistence.require(run.id()).state()).isEqualTo("RUNNING");
        verify(remote, times(1)).promptAsync(any(), any(OpenCodeClient.PromptRequest.class));
    }
    @Test void anEmptyCreateLookupDoesNotProveAnUnknownInFlightCreateStopped() {
        doThrow(new IllegalStateException("create response unknown")).when(remote).createSession(any(OpenCodeClient.SessionCreationPlan.class));
        var message = service.send(document, input(UUID.randomUUID().toString())); coordinator.tick(document);
        assertThat(persistence.require(message.id()).state()).isEqualTo("CREATE_UNKNOWN");
        service.stop(document); coordinator.tick(document); coordinator.tick(document);
        assertThat(persistence.require(message.id()).state()).isEqualTo("STOPPING");
        assertThat(persistence.require(message.id()).stopProof()).isNull();
        verify(remote, times(1)).createSession(any(OpenCodeClient.SessionCreationPlan.class));
    }
    @Test void stopUnknownRetainsWriterUntilPositiveProofAndSavedContentSurvives() {
        var run = start(); remote.failNextAborts(1); service.stop(document); coordinator.tick(document);
        assertThat(persistence.require(run.id()).state()).isEqualTo("STOPPING");
        assertThatThrownBy(() -> service.send(document, input(UUID.randomUUID().toString()))).isInstanceOf(ConflictException.class);
        coordinator.tick(document); var stopped = persistence.require(run.id());
        assertThat(stopped.state()).isEqualTo("STOPPED"); assertThat(stopped.stopProof()).isNotBlank();
        assertThat(mapper.active(document)).isEmpty();
    }
    @Test void grantRejectsCrossDocumentOldGenerationAndLateWritesButAllowsExactReceiptReplay() {
        var run = start(); var args = Map.<String,Object>of("idempotencyKey", "batch-key-1", "expectedRevision", 0, "operations", List.of());
        Object first = tools.call("ppt_apply_operations", call(run, args));
        service.stop(document); coordinator.tick(document);
        assertThat((tools.jackson.databind.JsonNode) json.valueToTree(tools.call("ppt_apply_operations", call(run, args)))).isEqualTo(json.valueToTree(first));
        assertThatThrownBy(() -> tools.call("ppt_apply_operations", call(run, Map.of("idempotencyKey", "new-key-1")))).isInstanceOf(ConflictException.class);
        var cross = new HashMap<>(call(run, args)); cross.put("documentId", "other");
        assertThatThrownBy(() -> tools.call("ppt_apply_operations", cross)).hasMessageContaining("授权");
        runtime.activate(new InternalMcpCredentialProvider(() -> 8080).issue());
        assertThatThrownBy(() -> tools.call("ppt_apply_operations", cross)).hasMessageContaining("授权");
        verify(workspace, times(1)).invoke(eq(document), eq("ppt_apply_operations"), any(), any());
    }
    @Test void questionStopsSafelyThenAnswerResumesExactNewMessageWithoutNewSession() {
        var run = start(); var oldEnvelope = call(run, Map.of());
        tools.call("ppt_request_input", call(run, Map.of("idempotencyKey", "question-1", "prompt", "面向什么受众？", "options", List.of("领导", "技术人员"))));
        var question = mapper.pending(run.id()).orElseThrow();
        assertThatThrownBy(() -> service.reply(document, question.id(), new PptAgentService.Reply("reply-key-1", "领导", 0, 0))).isInstanceOf(ConflictException.class);
        coordinator.tick(document); coordinator.tick(document);
        assertThat(persistence.require(run.id()).state()).isEqualTo("WAITING_INPUT");
        var reply = new PptAgentService.Reply("reply-key-1", "领导", 0, 0);
        service.reply(document, question.id(), reply); service.reply(document, question.id(), reply); coordinator.tick(document);
        var resumed = persistence.require(run.id());
        assertThat(resumed.state()).isEqualTo("RUNNING"); assertThat(resumed.round()).isEqualTo(1);
        assertThat(resumed.messageId()).isNotEqualTo(run.messageId()); assertThat(resumed.requestJson()).contains("领导");
        assertThatThrownBy(() -> tools.call("ppt_get_context", oldEnvelope)).isInstanceOfSatisfying(io.opencode.loopper.domain.SessionFailure.class,
                failure -> assertThat(failure.code()).isEqualTo("PPT_SCOPE_EXPIRED"));
        assertThatThrownBy(() -> toolWrites.save(run, "old-round-key", "ppt_apply_operations", PptAgentService.hash("old-round"), Map.of("revision", 2)))
                .isInstanceOf(ConflictException.class);
        assertThat(mapper.receipt(run.id(), "old-round-key")).isEmpty();
        verify(remote, times(1)).createSession(any(OpenCodeClient.SessionCreationPlan.class));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ppt_agent_prompt", Integer.class)).isEqualTo(2);
    }
    @Test void requirementsDialogueNeedsExplicitHumanConfirmationAndPreservesSameSessionAcrossRejection() {
        var context = json.createObjectNode().put("requirementsProtocol", PptRequirements.PROTOCOL);
        when(workspace.workspace(document)).thenReturn(new PptAgentWorkspace.Workspace(document, "DESIGN", 0, "fake/model", root.resolve("agent"), context));
        var run = start();
        assertThat(service.status(document).requirementsState()).isEqualTo("CLARIFYING");
        var before = json.valueToTree(tools.call("ppt_get_capabilities", call(run, Map.of())));
        assertThat(before.path("allowedTools").valueStream().map(JsonNode::asText))
                .contains("ppt_request_input", "ppt_get_context").doesNotContain("ppt_submit_plan", "ppt_apply_operations");
        final var initial = run;
        assertThatThrownBy(() -> tools.call("ppt_submit_plan", call(initial, Map.of("idempotencyKey", "premature-plan", "expectedRevision", 0))))
                .hasMessageContaining("尚未完成需求确认");
        assertThatThrownBy(() -> tools.call("ppt_request_input", call(initial, Map.of("idempotencyKey", "premature-confirm", "kind", PptRequirements.CONFIRMATION, "prompt", "请确认"))))
                .hasMessageContaining("先围绕内容重点");
        tools.call("ppt_request_input", call(run, Map.of("idempotencyKey", "requirements-first", "prompt", "重点展示成果还是风险？")));
        var first = mapper.pending(run.id()).orElseThrow(); coordinator.tick(document); coordinator.tick(document);
        service.reply(document, first.id(), new PptAgentService.Reply("requirements-answer", "重点成果，风格简洁", 0, 0)); coordinator.tick(document);
        run = persistence.require(run.id());
        // Replies rebuild workspace context; the server-owned protocol must survive even if this live projection omits it.
        when(workspace.workspace(document)).thenReturn(new PptAgentWorkspace.Workspace(document, "DESIGN", 0, "fake/model", root.resolve("agent"), json.createObjectNode()));
        tools.call("ppt_request_input", call(run, Map.of("idempotencyKey", "requirements-summary", "kind", PptRequirements.CONFIRMATION, "prompt", "面向领导，重点成果，10页简洁商务风格；无来源的数字不编造。")));
        var summary = mapper.pending(run.id()).orElseThrow();
        assertThat(service.status(document).requirementsState()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(service.status(document).questions().getFirst().kind()).isEqualTo(PptRequirements.CONFIRMATION);
        coordinator.tick(document); coordinator.tick(document);
        service.reply(document, summary.id(), new PptAgentService.Reply("requirements-refine", "可以，但改为8页", 0, 0)); coordinator.tick(document);
        run = persistence.require(run.id());
        assertThat(run.contextJson()).contains(PptRequirements.PROTOCOL);
        assertThat(mapper.question(document, summary.id()).orElseThrow().confirmed()).isFalse();
        final var unconfirmed = run;
        assertThatThrownBy(() -> tools.call("ppt_apply_operations", call(unconfirmed, Map.of("idempotencyKey", "premature-slides", "expectedRevision", 0))))
                .hasMessageContaining("尚未完成需求确认");
        tools.call("ppt_request_input", call(run, Map.of("idempotencyKey", "requirements-summary-v2", "kind", PptRequirements.CONFIRMATION, "prompt", "面向领导，重点成果，8页简洁商务风格。")));
        var finalSummary = mapper.pending(run.id()).orElseThrow();
        var acceptance = new PptAgentService.Reply("requirements-accepted", "确认以上需求，请开始设计", 0, 0, true);
        assertThatThrownBy(() -> service.reply(document, finalSummary.id(), acceptance)).isInstanceOf(ConflictException.class);
        coordinator.tick(document); coordinator.tick(document);
        assertThatThrownBy(() -> service.reply(document, finalSummary.id(), new PptAgentService.Reply("requirements-stale", "确认以上需求", 1, 0, true)))
                .isInstanceOf(ConflictException.class);
        assertThat(mapper.question(document, finalSummary.id()).orElseThrow().confirmed()).isNull();
        service.reply(document, finalSummary.id(), acceptance); service.reply(document, finalSummary.id(), acceptance);
        assertThatThrownBy(() -> service.reply(document, finalSummary.id(), new PptAgentService.Reply(acceptance.idempotencyKey(), acceptance.answer(), 0, 0, false)))
                .isInstanceOf(ConflictException.class);
        coordinator.tick(document); run = persistence.require(run.id());
        assertThat(service.status(document).requirementsState()).isEqualTo("CONFIRMED");
        var after = json.valueToTree(tools.call("ppt_get_capabilities", call(run, Map.of())));
        assertThat(after.path("allowedTools").valueStream().map(JsonNode::asText)).contains("ppt_submit_plan", "ppt_apply_operations");
        assertThat(run.round()).isEqualTo(3);
        tools.call("ppt_submit_plan", call(run, Map.of("idempotencyKey", "accepted-plan", "expectedRevision", 0)));
        verify(workspace, times(1)).invoke(eq(document), eq("ppt_submit_plan"), any(), any());
        verify(remote, times(1)).createSession(any(OpenCodeClient.SessionCreationPlan.class));
        tools.call("ppt_request_input", call(run, Map.of("idempotencyKey", "new-requirements", "prompt", "新增风险章节放在最后吗？")));
        var change = mapper.pending(run.id()).orElseThrow(); coordinator.tick(document); coordinator.tick(document);
        service.reply(document, change.id(), new PptAgentService.Reply("changed-requirements", "改为风险优先", 0, 0)); coordinator.tick(document);
        var changed = persistence.require(run.id());
        assertThat(service.status(document).requirementsState()).isEqualTo("CLARIFYING");
        assertThatThrownBy(() -> tools.call("ppt_submit_plan", call(changed, Map.of("idempotencyKey", "stale-acceptance", "expectedRevision", 0))))
                .hasMessageContaining("尚未完成需求确认");
    }
    @Test void commitRevalidationRejectsConcurrentStopAndNoSuccessfulReceiptIsInvented() {
        var run = start();
        when(workspace.invoke(eq(document), anyString(), any(), any())).thenAnswer(invocation -> {
            persistence.stop(document, "USER"); invocation.getArgument(3, Runnable.class).run(); return Map.of("saved", true);
        });
        assertThatThrownBy(() -> tools.call("ppt_apply_operations", call(run, Map.of("idempotencyKey", "race-key-1")))).isInstanceOf(ConflictException.class);
        assertThat(mapper.receipt(run.id(), "race-key-1")).isEmpty();
    }
    @Test void lateReceiptPersistenceRechecksOriginalRoundAndStopInsideItsTransaction() {
        var run = start();
        String savedSha = PptAgentService.hash("saved-input"), lateSha = PptAgentService.hash("late-input");
        toolWrites.save(run, "saved-key-1", "ppt_apply_operations", savedSha, Map.of("revision", 1));
        service.stop(document); coordinator.tick(document);
        assertThatThrownBy(() -> toolWrites.save(run, "late-key-1", "ppt_apply_operations", lateSha, Map.of("revision", 2)))
                .isInstanceOf(ConflictException.class);
        assertThat(mapper.receipt(run.id(), "late-key-1")).isEmpty();
        assertThat(toolWrites.save(run, "saved-key-1", "ppt_apply_operations", savedSha, Map.of("revision", 1))).isNotNull();
    }
    @Test void completedExportKeepsSameReviewSessionAuthorizedForJobReadsAndFurtherDraftEdits() {
        when(workspace.workspace(document)).thenReturn(new PptAgentWorkspace.Workspace(document, "REVIEW", 0, "fake/model", root.resolve("agent"), json.createObjectNode()));
        var run = start();
        when(workspace.invoke(eq(document), eq("ppt_export"), any(), any())).thenAnswer(invocation -> {
            invocation.getArgument(3, Runnable.class).run();
            when(workspace.workspace(document)).thenReturn(new PptAgentWorkspace.Workspace(document, "EXPORTED", 0, "fake/model", root.resolve("agent"), json.createObjectNode()));
            return Map.of("id", "export-job", "state", "COMPLETED", "revision", 0);
        });
        tools.call("ppt_export", call(run, Map.of("idempotencyKey", "export-domain-key", "revision", 0)));
        assertThat(mapper.receipt(run.id(), "export-domain-key")).isPresent();
        tools.call("ppt_get_job", call(run, Map.of("jobId", "export-job")));
        coordinator.tick(document);
        assertThat(persistence.require(run.id()).state()).isEqualTo("RUNNING");
        when(workspace.invoke(eq(document), eq("ppt_apply_operations"), any(), any())).thenAnswer(invocation -> {
            invocation.getArgument(3, Runnable.class).run();
            when(workspace.workspace(document)).thenReturn(new PptAgentWorkspace.Workspace(document, "REVIEW", 1, "fake/model", root.resolve("agent"), json.createObjectNode()));
            return Map.of("revision", 1);
        });
        tools.call("ppt_apply_operations", call(run, Map.of("idempotencyKey", "edit-after-export", "expectedRevision", 0, "operations", List.of())));
        assertThat(mapper.receipt(run.id(), "edit-after-export")).isPresent();
        when(workspace.workspace(document)).thenReturn(new PptAgentWorkspace.Workspace(document, "DESIGN", 1, "fake/model", root.resolve("agent"), json.createObjectNode()));
        assertThatThrownBy(() -> tools.call("ppt_get_job", call(run, Map.of("jobId", "export-job")))).isInstanceOf(ConflictException.class);
        coordinator.tick(document);
        assertThat(persistence.require(run.id()).state()).isEqualTo("STOPPING");
    }
    @Test void restartUsesIndependentOwnedProcessExitProofBeforeReleasingUnknownWriter() {
        doAnswer(invocation -> { invocation.callRealMethod(); throw new IllegalStateException("lost response"); })
                .when(remote).promptAsync(any(), any(OpenCodeClient.PromptRequest.class));
        var run = start(); assertThat(run.state()).isEqualTo("UNKNOWN");
        runtime.activate(new InternalMcpCredentialProvider(() -> 8080).issue());
        doReturn(null).when(remote).abortWithConfirmation(any());
        service.stop(document); coordinator.close();
        coordinator = new PptAgentCoordinator(mapper, persistence, remote, json, properties, workspace, authority, events, activity);
        coordinator.tick(document);
        assertThat(mapper.active(document)).isPresent();
        assertThatThrownBy(() -> service.send(document, input(UUID.randomUUID().toString()))).isInstanceOf(ConflictException.class);
        when(generations.exitProof(run.generation(), true)).thenReturn(Optional.of("OWNED_PROCESS_EXITED:recorded-identity"));
        coordinator.tick(document);
        assertThat(persistence.require(run.id()).state()).isEqualTo("STOPPED");
        assertThat(persistence.require(run.id()).stopProof()).startsWith("OWNED_PROCESS_EXITED:");
        assertThat(service.send(document, input(UUID.randomUUID().toString())).id()).isNotEqualTo(run.id());
    }
    @Test void waitingQuestionIsRecoverableAfterOwnedRuntimeRetiresAndOldSessionCannotContinue() {
        var run = start();
        tools.call("ppt_request_input", call(run, Map.of("idempotencyKey", "restart-question", "prompt", "请确认受众", "options", List.of())));
        coordinator.tick(document); coordinator.tick(document);
        assertThat(persistence.require(run.id()).state()).isEqualTo("WAITING_INPUT");
        assertThat(mapper.activeDocuments()).contains(document);
        runtime.activate(new InternalMcpCredentialProvider(() -> 8080).issue());
        when(generations.exitProof(run.generation(), true)).thenReturn(Optional.of("OWNED_PROCESS_EXITED:recorded-identity"));
        coordinator.tick(document);
        assertThat(persistence.require(run.id()).state()).isEqualTo("STOPPED");
        assertThat(mapper.questions(run.id())).singleElement().satisfies(q -> assertThat(q.state()).isEqualTo("CLOSED"));
        assertThat(mapper.active(document)).isEmpty();
    }
}
