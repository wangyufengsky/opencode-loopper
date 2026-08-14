package io.opencode.loopper.service;

import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.persistence.AttemptRow;
import io.opencode.loopper.persistence.ExecutionSessionRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.persistence.SessionUsageRow;
import io.opencode.loopper.persistence.StageRow;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.runtime.FakeOpenCodeClient;
import io.opencode.loopper.runtime.GitWorktreeManager;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

@SpringBootTest(classes = LoopperApplication.class, properties = {"loopper.opencode.mode=fake", "loopper.monitor-delay=1h"})
class SessionLifecycleServiceIntegrationTest {
    @Autowired private Flyway flyway;
    @Autowired private LoopperMapper mapper;
    @Autowired private SessionLifecycleService lifecycle;
    @Autowired private LoopDraftService drafts;
    @MockitoSpyBean private OpenCodeClient openCode;
    @TempDir Path temp;

    @BeforeEach void resetState() { reset(openCode); flyway.clean(); flyway.migrate(); ((FakeOpenCodeClient) openCode).reset(); }

    @Test
    void providerSnapshotIoNeverRunsInsideTheSqliteWriteTransaction() throws Exception {
        Fixture fixture = fixture("feature/sqlite-boundary", "COMPLETED", "IDLE");
        FakeOpenCodeClient fake = (FakeOpenCodeClient) openCode;
        fake.setSessionTodos(fixture.externalId(), List.of(new OpenCodeClient.SessionTodo(
                "todo-boundary", "验证事务边界", "IN_PROGRESS", "HIGH", 0, java.util.Map.of())));
        AtomicInteger providerCalls = new AtomicInteger();
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            providerCalls.incrementAndGet();
            return invocation.callRealMethod();
        }).when(openCode).sessionTodos(any());

        assertThat(lifecycle.refreshTodos(fixture.taskId(), fixture.sessionId())).hasSize(1);
        assertThat(providerCalls).hasValue(1);
        assertThat(mapper.listSessionTodos(fixture.sessionId())).singleElement()
                .satisfies(todo -> assertThat(todo.externalTodoId()).isEqualTo("todo-boundary"));
    }

    @Test
    void todosAreProviderBackedAndIdempotentlyPersistedWhileCheckpointsHaveStableHashes() throws Exception {
        Fixture fixture = fixture("feature/session", "COMPLETED", "IDLE");
        FakeOpenCodeClient fake = (FakeOpenCodeClient) openCode;
        fake.setSessionTodos(fixture.externalId(), List.of(new OpenCodeClient.SessionTodo("todo-1", "检查真实状态", "IN_PROGRESS", "HIGH", 1, java.util.Map.of("source", "fake"))));

        assertThat(lifecycle.refreshTodos(fixture.taskId(), fixture.sessionId())).hasSize(1);
        assertThat(lifecycle.refreshTodos(fixture.taskId(), fixture.sessionId())).hasSize(1);
        assertThat(mapper.listSessionTodos(fixture.sessionId())).singleElement()
                .satisfies(todo -> assertThat(todo.version()).isZero());
        var one = lifecycle.checkpoint(fixture.taskId(), fixture.sessionId(), "fake-message");
        var two = lifecycle.checkpoint(fixture.taskId(), fixture.sessionId(), "fake-message");
        assertThat(one.contentSha256()).isEqualTo(two.contentSha256()).hasSize(64);
        var persisted = mapper.findSessionCheckpoint(one.id()).orElseThrow();
        assertThat(persisted.messageRefsJson()).contains("fake-message", "fake-output");
        assertThat(persisted.todoRefsJson()).contains("todo-1", "检查真实状态", "IN_PROGRESS", "HIGH",
                "\"ordinal\":1", "\"truncated\":false");
        assertThat(persisted.diffRefJson()).contains("sha256");
        assertThat(persisted.contentSha256()).isEqualTo(sha256(persisted.messageRefsJson() + "\n" + persisted.todoRefsJson() + "\n" + persisted.diffRefJson()));
        assertThatThrownBy(() -> lifecycle.checkpoint(fixture.taskId(), fixture.sessionId(), "fake-output"))
                .isInstanceOfSatisfying(BadRequestException.class, ex -> assertThat(ex.code()).isEqualTo("CHECKPOINT_MESSAGE_NOT_FOUND"));
        assertThatThrownBy(() -> lifecycle.checkpoint(fixture.taskId(), fixture.sessionId(), "foreign-message"))
                .isInstanceOfSatisfying(BadRequestException.class, ex -> assertThat(ex.code()).isEqualTo("CHECKPOINT_MESSAGE_NOT_FOUND"));

        fake.setSessionTodos(fixture.externalId(), List.of());
        assertThat(lifecycle.refreshTodos(fixture.taskId(), fixture.sessionId())).isEmpty();
        assertThat(mapper.listSessionTodos(fixture.sessionId())).isEmpty();
        var withoutRemovedTodo = lifecycle.checkpoint(fixture.taskId(), fixture.sessionId(), "fake-message");
        assertThat(mapper.findSessionCheckpoint(withoutRemovedTodo.id()).orElseThrow().todoRefsJson()).isEqualTo("[]");
    }

    @Test
    void activeAndUnconfirmedWritersCannotFork() throws Exception {
        Fixture active = fixture("feature/active", "RUNNING", "RUNNING");
        assertThatThrownBy(() -> lifecycle.fork(active.taskId(), active.sessionId(), "message-1"))
                .isInstanceOfSatisfying(ConflictException.class, ex -> assertThat(ex.code()).isEqualTo("SESSION_WRITER_ACTIVE"));

        Fixture unconfirmed = fixture("feature/unconfirmed", "DISCONNECTED", "FAILED");
        assertThatThrownBy(() -> lifecycle.fork(unconfirmed.taskId(), unconfirmed.sessionId(), "message-1"))
                .isInstanceOfSatisfying(ConflictException.class, ex -> assertThat(ex.code()).isEqualTo("SESSION_WRITER_UNCONFIRMED"));

        Fixture owner = fixture("feature/owner", "COMPLETED", "IDLE");
        Fixture other = fixture("feature/other", "COMPLETED", "IDLE");
        assertThatThrownBy(() -> lifecycle.todos(other.taskId(), owner.sessionId()))
                .isInstanceOfSatisfying(BadRequestException.class, ex -> assertThat(ex.code()).isEqualTo("SESSION_TASK_MISMATCH"));
    }

    @Test
    void directRevertIsForbiddenAndGitForkCreatesOnlyATerminalSnapshotAssociation() throws Exception {
        Fixture direct = fixture(GitWorktreeManager.DIRECT_BRANCH, "COMPLETED", "IDLE");
        assertThatThrownBy(() -> lifecycle.revert(direct.taskId(), direct.sessionId(), "message-1", "part-1"))
                .isInstanceOfSatisfying(ConflictException.class, ex -> assertThat(ex.code()).isEqualTo("DIRECT_REVERT_REQUIRES_RECOVERY"));

        Fixture git = fixture("feature/fork", "COMPLETED", "IDLE");
        var fork = lifecycle.fork(git.taskId(), git.sessionId(), "message-1");
        ExecutionSessionRow child = mapper.findSession(fork.sessionId()).orElseThrow();
        AttemptRow attempt = mapper.findAttempt(fork.attemptId()).orElseThrow();
        assertThat(child.taskId()).isEqualTo(git.taskId());
        assertThat(child.attemptId()).isEqualTo(attempt.id());
        assertThat(child.state()).isEqualTo("COMPLETED");
        assertThat(attempt.state()).isEqualTo("SUCCEEDED");
        assertThat(attempt.failureKind()).isEqualTo("SESSION_FORK_SNAPSHOT");
        assertThat(mapper.activeSessions(git.taskId())).isEmpty();
    }

    @Test
    void summarizeCallsTheProviderAndPreservesObservedStatesInEvents() throws Exception {
        Fixture fixture = fixture("feature/summary", "COMPLETED", "IDLE");
        var summary = lifecycle.summarize(fixture.taskId(), fixture.sessionId(), true);
        assertThat(((FakeOpenCodeClient) openCode).summarizeCalls()).singleElement()
                .satisfies(call -> assertThat(call.sessionId()).isEqualTo(fixture.externalId()));
        assertThat(summary.remoteStateBefore()).isEqualTo("IDLE");
        assertThat(mapper.eventsAfter(fixture.taskId(), 0)).extracting(event -> event.type()).contains("session.summarized");
    }

    @Test
    void reliableUsageAtSoftLimitBlocksSummarizeBeforeTheProviderCall() throws Exception {
        Fixture fixture = budgetedFixture(10L);
        String now = Instant.now().toString();
        mapper.insertSessionUsage(new SessionUsageRow("summary-budget-usage", fixture.taskId(), fixture.sessionId(), null,
                "message-budget", "usage:summary:message-budget", "provider", "model", 4L, 6L, 10L,
                null, null, true, now));
        FakeOpenCodeClient fake = (FakeOpenCodeClient) openCode;

        assertThatThrownBy(() -> lifecycle.summarize(fixture.taskId(), fixture.sessionId(), true))
                .isInstanceOfSatisfying(ConflictException.class,
                        failure -> assertThat(failure.code()).isEqualTo("BUDGET_TOKEN_LIMIT_REACHED"));

        assertThat(fake.summarizeCalls()).isEmpty();
        assertThat(mapper.findTask(fixture.taskId()).orElseThrow().state()).isEqualTo("WAITING_INPUT");
        assertThat(mapper.listErrors(fixture.taskId())).anySatisfy(error -> {
            assertThat(error.code()).isEqualTo("BUDGET_TOKEN_LIMIT_REACHED");
            assertThat(error.evidenceJson()).contains("SESSION_SUMMARIZE", "nextCallBlocked");
        });
    }

    private Fixture fixture(String branch, String localState, String remoteState) throws Exception {
        String suffix = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        Path root = Files.createDirectories(temp.resolve(suffix));
        String projectId = "project-" + suffix;
        String taskId = "task-" + suffix;
        String stageId = "stage-" + suffix;
        String attemptId = "attempt-" + suffix;
        String sessionId = "session-" + suffix;
        String externalId = ((FakeOpenCodeClient) openCode).createReadOnlySession(root, "LIFECYCLE DESIGNER", null).id();
        mapper.insertProject(new ProjectRow(projectId, "lifecycle", root.toString(), "", now, now, 1, 0));
        mapper.insertTask(new TaskRow(taskId, projectId, null, "会话生命周期", "PAUSED", root.toString(), branch, "baseline", now, now, 0));
        mapper.insertStage(new StageRow(stageId, taskId, 0, "检查会话", "[]", "[]", "[]", "[]", "PAUSED", now, now, 0));
        mapper.insertAttempt(new AttemptRow(attemptId, taskId, stageId, 1, "SUCCEEDED", null, "已有终态", now, now, 0));
        mapper.insertSession(new ExecutionSessionRow(sessionId, taskId, stageId, attemptId, externalId, localState, now, "COMPLETED".equals(localState) ? now : null, 0));
        ((FakeOpenCodeClient) openCode).setSessionState(externalId, remoteState);
        return new Fixture(taskId, sessionId, externalId);
    }

    private Fixture budgetedFixture(long maxTotalTokens) throws Exception {
        String suffix = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        Path root = Files.createDirectories(temp.resolve(suffix));
        String projectId = "project-" + suffix;
        mapper.insertProject(new ProjectRow(projectId, "lifecycle-budget", root.toString(), "", now, now, 1, 0));
        LoopSpec spec = new LoopSpec("v1", projectId, "会话摘要预算门", null,
                List.of(new LoopSpec.StageSpec("检查会话", null, null, null,
                        List.of(new LoopSpec.VerifierSpec("FILE_EXISTS", null, "README.md", null, null, null, null)))),
                null, null, null, null, new LoopSpec.BudgetSpec(maxTotalTokens, null, null));
        var draft = drafts.create(spec);
        String taskId = "task-" + suffix;
        String stageId = "stage-" + suffix;
        String attemptId = "attempt-" + suffix;
        String sessionId = "session-" + suffix;
        String externalId = ((FakeOpenCodeClient) openCode).createReadOnlySession(root, "LIFECYCLE DESIGNER", null).id();
        mapper.insertTask(new TaskRow(taskId, projectId, draft.id(), "会话摘要预算", "PAUSED", root.toString(),
                "feature/summary-budget", "baseline", now, now, 0));
        mapper.insertStage(new StageRow(stageId, taskId, 0, "检查会话", "[]", "[]", "[]", "[]", "PAUSED", now, now, 0));
        mapper.insertAttempt(new AttemptRow(attemptId, taskId, stageId, 1, "SUCCEEDED", null, "已有终态", now, now, 0));
        mapper.insertSession(new ExecutionSessionRow(sessionId, taskId, stageId, attemptId, externalId, "COMPLETED", now, now, 0));
        ((FakeOpenCodeClient) openCode).setSessionState(externalId, "IDLE");
        return new Fixture(taskId, sessionId, externalId);
    }

    private record Fixture(String taskId, String sessionId, String externalId) { }
    private String sha256(String value) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
}
