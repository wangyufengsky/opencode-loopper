package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(classes = LoopperApplication.class, properties = "loopper.opencode.mode=fake")
class DesignerConversationIntegrationTest {
    private static final Path DATA = database();
    static Path database() {
        try { return Files.createTempDirectory("loopper-conversation-test-"); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("loopper.data-dir", DATA::toString);
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATA.resolve("test.db") + "?foreign_keys=on&journal_mode=WAL&transaction_mode=IMMEDIATE");
        registry.add("loopper.scheduling.enabled", () -> false);
        registry.add("loopper.startup-recovery.enabled", () -> false);
    }
    @Autowired Flyway flyway;
    @Autowired LoopperMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectService projects;
    @Autowired tools.jackson.databind.ObjectMapper json;
    @Autowired StoryBindingService bindings;
    @Autowired StoryAccountingCoordinator accounting;
    @TempDir Path root;
    DesignerConversationCoordinator conversations;
    OpenCodeClient remote;
    String owner;
    AtomicInteger created;

    @BeforeEach void prepare() throws Exception {
        flyway.clean(); flyway.migrate();
        Files.writeString(root.resolve("README.md"), "fixture");
        String project = projects.create("conversation", root.toString()).id();
        owner = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        mapper.insertDesignerSession(new DesignerSessionRow(owner, project, "RUNNING", "READ_ONLY", now, now, 0,
                null, "RUNNING", null, "DISCUSSING_REQUIREMENT", 0, 0));
        remote = mock(OpenCodeClient.class);
        created = new AtomicInteger();
        when(remote.createSession(any(), anyString(), any(), any())).thenAnswer(call ->
                new OpenCodeClient.OpenCodeSession("remote-" + created.incrementAndGet(), root, "generation", "private"));
        when(remote.sessionStatus(any())).thenReturn(new OpenCodeClient.SessionStatus("COMPLETED"));
        conversations = new DesignerConversationCoordinator(mapper, remote, json);
        conversations.enable(owner);
    }

    OpenCodeClient.OpenCodeSession acquire(String scope, boolean adopt) {
        return conversations.acquire(owner, scope, root, new OpenCodeClient.OpenCodeModel("test", "test", null), true, true, adopt, false);
    }
    void turn(OpenCodeClient.OpenCodeSession session, String phase) {
        conversations.begin(session, phase);
        conversations.send(session, OpenCodeClient.PromptRequest.text("Business " + phase));
        conversations.settle(session.id());
    }

    @Test void waitingOnOneDesignerDoesNotBlockAnotherOwner() throws Exception {
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Future<Boolean> sameOwner;
            var entered = new java.util.concurrent.CountDownLatch(1);
            try (var held = conversations.guard(owner)) {
                sameOwner = executor.submit(() -> {
                    entered.countDown();
                    try (var ignored = conversations.guard(owner)) { return true; }
                });
                assertThat(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                var another = executor.submit(() -> {
                    try (var ignored = conversations.guard("another-owner")) { return true; }
                });
                assertThat(another.get(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(sameOwner.isDone()).isFalse();
            }
            assertThat(sameOwner.get(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void oneSinglePackageConversationAcrossQuestionsCompilationAndRevisions() {
        var first = acquire("REQUIREMENT", false);
        jdbc.update("UPDATE designer_session SET external_session_id=? WHERE id=?", first.id(), owner);
        bindings.attachDesigner(owner, new StoryBindingConfiguration(true, "SYS-001", "000123"));
        var operations = new java.util.ArrayList<String>();
        StoryAccountingCoordinator.CommandTransport transport = request -> {
            operations.add(request.arguments()); return new OpenCodeClient.CommandResult("run", "ok");
        };
        accounting.beforeBusinessPrompt(first, transport);
        turn(first, "REQUIREMENT");
        var design = acquire("WP-ROW-1", true);
        assertThat(design.id()).isEqualTo(first.id());
        for (int revision = 0; revision < 3; revision++) {
            assertThat(acquire("WP-ROW-1", true).id()).isEqualTo(first.id());
            accounting.beforeBusinessPrompt(design, transport);
            turn(design, "PACKAGE_DESIGN");
            jdbc.update("UPDATE designer_session SET workflow_phase='COMPILING' WHERE id=?", owner);
            accounting.afterTerminalStatus(design, transport);
            assertThat(mapper.storyAccountingOwnerActive(first.id())).isTrue();
        }
        assertThat(operations).containsExactly("start SYS-001 000123");
        conversations.retire(first.id(), "PACKAGE_APPROVED");
        accounting.afterTerminalStatus(first, transport);
        assertThat(operations).containsExactly("start SYS-001 000123", "complete");
        assertThat(created).hasValue(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT message_id) FROM designer_conversation_turn", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT candidate_run_id) FROM designer_conversation_turn", Integer.class)).isEqualTo(4);
    }

    @Test void eachPackageHasIndependentConversationAndReopeningCreatesNewGeneration() {
        var global = acquire("REQUIREMENT", false);
        turn(global, "REQUIREMENT"); conversations.retire(global.id(), "REQUIREMENT_CONFIRMED");
        for (int index = 1; index <= 3; index++) {
            var session = acquire("WP-" + index, false);
            turn(session, "PACKAGE_QUESTION");
            assertThat(acquire("WP-" + index, false).id()).isEqualTo(session.id());
            turn(session, "PACKAGE_DESIGN");
            conversations.retire(session.id(), "PACKAGE_APPROVED");
        }
        assertThat(created).hasValue(4);
        var reopened = acquire("WP-1", false);
        assertThat(mapper.designerConversationForRemote(reopened.id()).orElseThrow().generation()).isEqualTo(2);
        assertThat(created).hasValue(5);
    }

    @Test void activeTurnAndDuplicateDispatchAreRejectedWithoutAnotherPrompt() {
        var session = acquire("REQUIREMENT", false);
        conversations.begin(session, "REQUIREMENT");
        conversations.send(session, OpenCodeClient.PromptRequest.text("first"));
        when(remote.sessionStatus(any())).thenReturn(new OpenCodeClient.SessionStatus("RUNNING"));
        assertThatThrownBy(() -> acquire("REQUIREMENT", false)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> conversations.send(session, OpenCodeClient.PromptRequest.text("second"))).isInstanceOf(ConflictException.class);
        verify(remote, times(1)).promptAsync(any(), any(OpenCodeClient.PromptRequest.class));
        assertThat(created).hasValue(1);
    }

    @Test void questionsRemainReadableWhileDispatchIsPreparedSendingOrUnknown() {
        var session = acquire("REQUIREMENT", false);
        conversations.begin(session, "REQUIREMENT");
        assertThat(conversations.questions(session.id(), root)).isEmpty();
        conversations.send(session, OpenCodeClient.PromptRequest.text("first"));
        var turn = mapper.designerTurnForRemote(session.id()).orElseThrow();
        for (String state : java.util.List.of("SENDING", "UNKNOWN")) {
            jdbc.update("UPDATE designer_conversation_turn SET state=? WHERE id=?", state, turn.id());
            assertThat(conversations.questions(session.id(), root)).isEmpty();
            assertThat(mapper.designerTurnForRemote(session.id()).orElseThrow().state()).isEqualTo(state);
        }
        verify(remote, never()).pendingQuestions(any());
        verify(remote, never()).findPromptMessage(any(), any(), any());
        jdbc.update("UPDATE designer_conversation_turn SET state='SENT' WHERE id=?", turn.id());
        var question = new OpenCodeClient.PendingQuestion("q", session.id(), java.util.List.of());
        when(remote.pendingQuestions(any())).thenReturn(java.util.List.of(question));
        assertThat(conversations.questions(session.id(), root)).containsExactly(question);
        verify(remote, times(1)).promptAsync(any(), any(OpenCodeClient.PromptRequest.class));
    }

    @Test void restartRestoresExactTurnAndNeverResendsUnknownRequest() {
        var session = acquire("REQUIREMENT", false);
        conversations.begin(session, "REQUIREMENT");
        conversations.send(session, OpenCodeClient.PromptRequest.text("first"));
        var turn = mapper.designerTurnForRemote(session.id()).orElseThrow();
        jdbc.update("UPDATE designer_conversation_turn SET state='SENDING' WHERE id=?", turn.id());
        var recovered = new DesignerConversationCoordinator(mapper, remote, json);
        when(remote.findPromptMessage(any(), any(OpenCodeClient.PromptRequest.class), anyString()))
                .thenReturn(new OpenCodeClient.MessageLookup(true, false, null));
        assertThatThrownBy(() -> recovered.remote(session.id(), root)).hasMessageContaining("未知");
        when(remote.findPromptMessage(any(), any(OpenCodeClient.PromptRequest.class), anyString()))
                .thenReturn(new OpenCodeClient.MessageLookup(true, true, turn.requestSha256()));
        assertThat(recovered.remote(session.id(), root).id()).isEqualTo(session.id());
        assertThat(mapper.designerTurnForRemote(session.id()).orElseThrow().state()).isEqualTo("SENT");
        verify(remote, times(1)).promptAsync(any(), any(OpenCodeClient.PromptRequest.class));
        verify(remote, atLeastOnce()).restoreDesignTurn(any(), any(), any(), eq(turn.messageId()));
    }

    @Test void interruptedCreationRecoversWithoutDispatchingAnUnknownBusinessRequest() {
        String now = Instant.now().toString();
        mapper.insertDesignerConversation(new io.opencode.loopper.persistence.DesignerConversationRow(
                "interrupted", owner, "REQUIREMENT", 1, null, null, null, root.toString(),
                "GENERAL_READ_ONLY", "null", "CREATING", null, now, now, 0));
        var session = acquire("REQUIREMENT", false);
        assertThat(mapper.latestDesignerConversation(owner, "REQUIREMENT").orElseThrow().generation()).isEqualTo(2);
        assertThat(mapper.bindDesignerConversation("interrupted", "late-remote", null, null)).isZero();
        assertThat(conversations.history(owner).getFirst().reason()).isEqualTo("CREATION_INTERRUPTED_BEFORE_BUSINESS");
        verify(remote, never()).promptAsync(any(), any(OpenCodeClient.PromptRequest.class));
    }

    @Test void replacementRequiresPositiveStopAndPreservesOriginalOnFailure() {
        var original = acquire("REQUIREMENT", false);
        turn(original, "REQUIREMENT");
        doThrow(new io.opencode.loopper.domain.SessionFailure("STOP_UNKNOWN", "unknown"))
                .when(remote).abortWithConfirmation(any());
        assertThatThrownBy(() -> conversations.acquire(owner, "REQUIREMENT", root, null, true, true, false, true))
                .hasMessageContaining("unknown");
        assertThat(mapper.designerConversationForRemote(original.id()).orElseThrow().state()).isEqualTo("OPEN");
        assertThat(created).hasValue(1);
        doReturn(OpenCodeClient.AbortConfirmation.ACKNOWLEDGED).when(remote).abortWithConfirmation(any());
        var replacement = conversations.acquire(owner, "REQUIREMENT", root, null, true, true, false, true);
        assertThat(replacement.id()).isNotEqualTo(original.id());
        assertThat(mapper.designerConversationForRemote(original.id()).orElseThrow().reason()).isEqualTo("CONTEXT_REPLACED");
    }

    @Test void migratedDesignerWithoutOptInKeepsLegacyPolicy() {
        jdbc.update("DELETE FROM designer_conversation_policy WHERE designer_session_id=?", owner);
        assertThat(conversations.enabled(owner)).isFalse();
        assertThat(conversations.history(owner)).isEmpty();
    }
}
