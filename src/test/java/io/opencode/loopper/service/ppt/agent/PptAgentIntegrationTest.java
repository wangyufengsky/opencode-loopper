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
        coordinator = new PptAgentCoordinator(mapper, persistence, remote, json, properties, workspace, authority, events);
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
        assertThatThrownBy(() -> tools.call("ppt_get_context", oldEnvelope)).hasMessageContaining("授权");
        assertThatThrownBy(() -> toolWrites.save(run, "old-round-key", "ppt_apply_operations", PptAgentService.hash("old-round"), Map.of("revision", 2)))
                .isInstanceOf(ConflictException.class);
        assertThat(mapper.receipt(run.id(), "old-round-key")).isEmpty();
        verify(remote, times(1)).createSession(any(OpenCodeClient.SessionCreationPlan.class));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ppt_agent_prompt", Integer.class)).isEqualTo(2);
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
        coordinator = new PptAgentCoordinator(mapper, persistence, remote, json, properties, workspace, authority, events);
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
