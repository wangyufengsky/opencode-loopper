package io.opencode.loopper.service;

import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.domain.ErrorLayer;
import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.persistence.AttemptRow;
import io.opencode.loopper.persistence.ExecutionSessionRow;
import io.opencode.loopper.persistence.LoopDraftRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.persistence.SessionUsageRow;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.runtime.FakeOpenCodeClient;
import io.opencode.loopper.runtime.OpenCodeClient;
import io.opencode.loopper.verification.VerifierEngine;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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

@SpringBootTest(classes = LoopperApplication.class, properties = {"loopper.opencode.mode=fake", "loopper.monitor-delay=1h"})
class TaskServiceIntegrationTest {
    @Autowired private Flyway flyway;
    @Autowired private ProjectService projects;
    @Autowired private LoopDraftService drafts;
    @Autowired private TaskService tasks;
    @Autowired private TaskMonitor monitor;
    @Autowired private OpenCodeClient openCode;
    @Autowired private LoopperMapper mapper;
    @Autowired private UsageInsightsService usageInsights;
    @Autowired private TaskEventHub taskEvents;
    @MockitoSpyBean private VerifierEngine verifierEngine;
    @TempDir Path temp;

    @BeforeEach
    void resetDatabase() {
        flyway.clean(); flyway.migrate();
        ((FakeOpenCodeClient) openCode).reset();
    }

    @Test
    void taskAggregatePersistsOrderedLifecycleAuditAlongsideStateChanges() throws Exception {
        ProjectRow project = projects.create("lifecycle-audit", gitProject());
        TaskRow ready = drafts.confirm(drafts.create(spec(project.id())).id(), "audit transitions");

        tasks.start(ready.id());

        var events = mapper.listStateTransitionsForScope("TASK", ready.id(), 0, 100);
        assertThat(events).extracting(io.opencode.loopper.persistence.StateTransitionEventRow::machineType)
                .contains("TASK", "STAGE", "ATTEMPT", "EXECUTION_SESSION");
        assertThat(events).anySatisfy(event -> {
            assertThat(event.machineType()).isEqualTo("TASK");
            assertThat(event.event()).isEqualTo("CREATED");
            assertThat(event.fromState()).isNull();
            assertThat(event.toState()).isEqualTo("PREPARING");
        });
        assertThat(events).anySatisfy(event -> {
            assertThat(event.machineType()).isEqualTo("TASK");
            assertThat(event.fromState()).isEqualTo("READY");
            assertThat(event.toState()).isEqualTo("RUNNING");
            assertThat(event.event()).isEqualTo("START");
        });
        assertThat(events).isSortedAccordingTo(java.util.Comparator.comparingLong(
                io.opencode.loopper.persistence.StateTransitionEventRow::sequence));
    }

    @Test
    void isolatedBranchesUseTaskTitleAndNumberRepeatedNames() throws Exception {
        Path root = Path.of(gitProject());
        ProjectRow project = projects.create("named-branches", root.toString());

        TaskRow first = drafts.confirm(drafts.create(spec(project.id())).id(), "隔离分支命名");
        TaskRow second = drafts.confirm(drafts.create(spec(project.id())).id(), "隔离分支命名");
        TaskRow third = drafts.confirm(drafts.create(spec(project.id())).id(), "隔离分支命名");
        TaskRow normalized = drafts.confirm(drafts.create(spec(project.id())).id(), "修复 分支/命名");
        run(root, "git", "update-ref", "refs/remotes/origin/loopper/远端同名任务", "HEAD");
        TaskRow remoteCollision = drafts.confirm(drafts.create(spec(project.id())).id(), "远端同名任务");

        assertThat(first.branchName()).isEqualTo("loopper/隔离分支命名");
        assertThat(second.branchName()).isEqualTo("loopper/隔离分支命名(第2次)");
        assertThat(third.branchName()).isEqualTo("loopper/隔离分支命名(第3次)");
        assertThat(normalized.branchName()).isEqualTo("loopper/修复-分支-命名");
        assertThat(remoteCollision.branchName()).isEqualTo("loopper/远端同名任务(第2次)");
        assertThat(localBranches(root)).contains(first.branchName(), second.branchName(), third.branchName(),
                normalized.branchName(), remoteCollision.branchName());
    }

    @Test
    void sessionFailureCreatesNewAttemptAndDoesNotFailTask() throws Exception {
        ProjectRow project = projects.create("fixture", gitProject());
        LoopDraftRow draft = drafts.create(spec(project.id()));
        TaskRow task = drafts.confirm(draft.id(), "session recovery");
        assertThat(task.state()).isEqualTo("READY");
        TaskRow running = tasks.start(task.id());
        AttemptRow first = tasks.attempts(task.id()).getFirst();
        ExecutionSessionRow startedSession = mapper.activeSessions(task.id()).getFirst();
        assertThat(((FakeOpenCodeClient) openCode).promptForSession(startedSession.externalSessionId()))
                .contains("使用简体中文撰写面向用户的进度说明、结论、评审和最终总结")
                .contains("JSON 字段名、协议枚举值");
        TaskRow recovered = tasks.sessionFailed(task.id(), first.id(), "NETWORK", "temporary transport failure");
        assertThat(recovered.state()).isEqualTo("RUNNING");
        assertThat(tasks.attempts(task.id())).hasSize(2);
        assertThat(tasks.errors(task.id())).anyMatch(error -> error.layer().equals(ErrorLayer.SESSION.name()));
    }

    @Test
    void disconnectedTaskEventSubscriberCannotBecomeAnOpenCodeSessionFailure() throws Exception {
        ProjectRow project = projects.create("sse-isolation", gitProject());
        TaskRow task = drafts.confirm(drafts.create(spec(project.id())).id(), "SSE disconnect isolation");
        AtomicInteger failedDeliveries = new AtomicInteger();
        taskEvents.subscribe(task.id(), event -> {
            failedDeliveries.incrementAndGet();
            throw new IllegalStateException("AsyncContext cannot be used after onError");
        });

        TaskRow running = tasks.start(task.id());

        assertThat(running.state()).isEqualTo("RUNNING");
        assertThat(failedDeliveries).hasValue(1);
        assertThat(tasks.attempts(task.id())).hasSize(1);
        assertThat(tasks.errors(task.id())).noneMatch(error -> error.code().equals("SESSION_RUNTIME_ERROR"));
    }

    @Test
    void implementationPromptIncludesTheCompleteStageExecutionContract() throws Exception {
        ProjectRow project = projects.create("prompt-contract", gitProject());
        LoopSpec contract = new LoopSpec("v1", project.id(), "Create the automation fixture",
                "先调用 question，确认后仅创建 automation.txt。",
                List.of(new LoopSpec.StageSpec("Create automation.txt", List.of("automation.txt"), List.of(".git/**"),
                        List.of("automation.txt"), List.of(new LoopSpec.VerifierSpec(
                                "FILE_CONTENT", null, "automation.txt", null, null, null, null, null,
                                null, null, null, null, null, "CONTAINS", "Loopper automation accepted",
                                null, null, null, null)))), null, null, null, null);
        TaskRow task = drafts.confirm(drafts.create(contract).id(), "complete prompt contract");

        tasks.start(task.id());

        ExecutionSessionRow session = mapper.activeSessions(task.id()).getFirst();
        assertThat(((FakeOpenCodeClient) openCode).promptForSession(session.externalSessionId()))
                .contains("Context: 先调用 question，确认后仅创建 automation.txt。")
                .contains("Deliverables: [\"automation.txt\"]")
                .contains("Verifier contract:")
                .contains("\"expectedContent\":\"Loopper automation accepted\"");
    }

    @Test
    void reliableUsageAtSoftLimitBlocksBeforeCreatingTheNextImplementationSession() throws Exception {
        ProjectRow project = projects.create("implementation-budget", gitProject());
        LoopSpec limited = new LoopSpec("v1", project.id(), "Respect provider usage", null,
                List.of(new LoopSpec.StageSpec("Check README", null, null, null,
                        List.of(new LoopSpec.VerifierSpec("FILE_EXISTS", null, "README.md", null, null, null, null)))),
                null, null, null, null, new LoopSpec.BudgetSpec(10L, null, null));
        TaskRow task = drafts.confirm(drafts.create(limited).id(), "implementation budget gate");
        var stage = tasks.stages(task.id()).getFirst();
        String now = Instant.now().toString();
        AttemptRow completed = new AttemptRow("budget-attempt", task.id(), stage.id(), 1, "SUCCEEDED", null, "prior work", now, now, 0);
        mapper.insertAttempt(completed);
        ExecutionSessionRow prior = new ExecutionSessionRow("budget-session", task.id(), stage.id(), completed.id(),
                "budget-remote", "COMPLETED", now, now, 0);
        mapper.insertSession(prior);
        mapper.insertSessionUsage(new SessionUsageRow("budget-usage", task.id(), prior.id(), null, "message-1",
                "usage:session:budget-session:message-1", "provider", "model", 4L, 6L, 10L,
                null, null, true, now));
        FakeOpenCodeClient fake = (FakeOpenCodeClient) openCode;

        TaskRow waiting = tasks.start(task.id());

        assertThat(waiting.state()).isEqualTo("WAITING_INPUT");
        assertThat(tasks.attempts(task.id())).hasSize(1);
        assertThat(mapper.listSessions(task.id())).hasSize(1);
        assertThat(fake.createSessionCalls()).isZero();
        assertThat(fake.promptCalls()).isZero();
        assertThat(tasks.errors(task.id())).anyMatch(error -> error.code().equals("BUDGET_TOKEN_LIMIT_REACHED"));
    }

    @Test
    void completedJudgeUsageBlocksARetryBeforeCreatingAnotherReadOnlySession() throws Exception {
        ProjectRow project = projects.create("judge-budget", gitProject());
        LoopSpec limited = new LoopSpec("v1", project.id(), "Budget judges", null,
                List.of(new LoopSpec.StageSpec("Check README", null, null, null,
                        List.of(new LoopSpec.VerifierSpec("FILE_EXISTS", null, "README.md", null, null, null, null)))),
                null, null, null, null, new LoopSpec.BudgetSpec(10L, null, null));
        TaskRow task = drafts.confirm(drafts.create(limited).id(), "judge budget gate");
        tasks.start(task.id());
        tasks.verify(task.id());
        FakeOpenCodeClient fake = (FakeOpenCodeClient) openCode;
        var requirement = tasks.judges(task.id()).stream().filter(row -> row.role().equals("REQUIREMENT")).findFirst().orElseThrow();
        var risk = tasks.judges(task.id()).stream().filter(row -> row.role().equals("RISK")).findFirst().orElseThrow();
        mapper.updateJudgeRun(new io.opencode.loopper.persistence.JudgeRunRow(requirement.id(), requirement.taskId(), requirement.attemptId(),
                requirement.role(), requirement.ordinal(), requirement.externalSessionId(), "COMPLETED", "PASS", "confirmed",
                "{\"verdict\":\"PASS\"}", requirement.createdAt(), Instant.now().toString(), requirement.version()));
        fake.setSessionUsage(requirement.externalSessionId(), List.of(new OpenCodeClient.UsageRecord(
                "judge-message", "provider", "model", 4L, 6L, null, null, null, true)));
        fake.setSessionState(risk.externalSessionId(), "RETRY");
        int readOnlyCallsBeforeRetry = fake.createReadOnlySessionCalls();
        int promptCallsBeforeRetry = fake.promptCalls();

        tasks.pollJudges(task.id());

        assertThat(tasks.get(task.id()).state()).isEqualTo("WAITING_INPUT");
        assertThat(tasks.judges(task.id())).hasSize(2);
        assertThat(fake.createReadOnlySessionCalls()).isEqualTo(readOnlyCallsBeforeRetry);
        assertThat(fake.promptCalls()).isEqualTo(promptCallsBeforeRetry);
        assertThat(mapper.listTaskUsage(task.id())).anySatisfy(row -> {
            assertThat(row.judgeRunId()).isEqualTo(requirement.id());
            assertThat(row.executionSessionId()).isNull();
            assertThat(row.totalTokens()).isEqualTo(10L);
        });
        assertThat(tasks.errors(task.id())).anyMatch(error -> error.code().equals("BUDGET_TOKEN_LIMIT_REACHED"));
    }

    @Test
    void archivesOnlyTerminalTasksAndRestoresThemWithoutChangingTaskState() throws Exception {
        ProjectRow project = projects.create("archive", gitProject());
        TaskRow ready = drafts.confirm(drafts.create(spec(project.id())).id(), "archive evidence");

        assertThatThrownBy(() -> tasks.archive(ready.id()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("只有已成功、已失败或已取消");

        TaskRow cancelled = tasks.cancel(ready.id());
        tasks.archive(cancelled.id());
        assertThat(tasks.archived(cancelled.id())).isTrue();
        assertThat(tasks.get(cancelled.id()).state()).isEqualTo("CANCELLED");

        tasks.restoreArchive(cancelled.id());
        assertThat(tasks.archived(cancelled.id())).isFalse();
        assertThat(tasks.get(cancelled.id()).state()).isEqualTo("CANCELLED");
    }

    @Test
    void permanentlyDeletesOnlyArchivedTerminalTasksAndTheirPersistedHistory() throws Exception {
        ProjectRow project = projects.create("delete-history", gitProject());
        LoopDraftRow draft = drafts.create(spec(project.id()));
        TaskRow task = drafts.confirm(draft.id(), "delete archived history");
        TaskRow cancelled = tasks.cancel(task.id());

        assertThatThrownBy(() -> tasks.deleteArchived(cancelled.id()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("请先归档任务");

        tasks.archive(cancelled.id());
        tasks.deleteArchived(cancelled.id());

        assertThat(mapper.findTask(cancelled.id())).isEmpty();
        assertThat(mapper.findDraft(draft.id())).isEmpty();
        assertThat(mapper.findLatestDesignerSessionByDraft(draft.id())).isEmpty();
        assertThat(mapper.listStages(cancelled.id())).isEmpty();
        assertThat(mapper.listTaskArtifacts(cancelled.id())).isEmpty();
        assertThat(mapper.listStateTransitionsForScope("TASK", cancelled.id(), 0, 100)).isEmpty();
    }

    @Test
    void confirmationRejectsDesignerContractThatOnlyChecksGitDiff() throws Exception {
        ProjectRow project = projects.create("weak-designer-acceptance", gitProject());
        LoopSpec weak = new LoopSpec("v1", project.id(), "Compile and print PASS", null,
                List.of(new LoopSpec.StageSpec("Implement and verify", List.of("src/**"), List.of("data/**"), List.of("source"),
                        List.of(new LoopSpec.VerifierSpec("GIT_DIFF", null, null, true,
                                List.of("src/**"), List.of("data/**"), true)))), null, null, null, null);
        LoopDraftRow draft = drafts.create(weak);

        assertThatThrownBy(() -> drafts.confirm(draft.id(), "must be executable"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("GIT_DIFF only checks change scope");
    }

    @Test
    void providerRetryStatusFlowsThroughMonitorAsSessionErrorAndContinuesTaskLoop() throws Exception {
        ProjectRow project = projects.create("provider-retry", gitProject());
        TaskRow task = drafts.confirm(drafts.create(spec(project.id())).id(), "provider retry recovery");
        tasks.start(task.id());
        AttemptRow first = tasks.attempts(task.id()).getFirst();
        ExecutionSessionRow active = mapper.activeSessions(task.id()).getFirst();
        ((FakeOpenCodeClient) openCode).setSessionStatus(active.externalSessionId(), "RETRY", "Free usage exceeded");

        monitor.poll();

        assertThat(tasks.get(task.id()).state()).isEqualTo("RUNNING");
        assertThat(tasks.attempts(task.id())).hasSize(2);
        assertThat(tasks.attempts(task.id()).stream().filter(attempt -> attempt.id().equals(first.id())).findFirst().orElseThrow().state())
                .isEqualTo("SESSION_ERROR");
        assertThat(tasks.attempts(task.id())).anyMatch(attempt -> attempt.state().equals("RUNNING"));
        assertThat(tasks.errors(task.id())).anyMatch(error -> error.layer().equals(ErrorLayer.SESSION.name())
                && error.code().equals("OPENCODE_SESSION_RETRY") && error.message().contains("Free usage exceeded"));
    }

    @Test
    void sessionFailureBecomesTaskFatalWhenOldWriterCannotBeConfirmedStopped() throws Exception {
        ProjectRow project = projects.create("unsafe-session-retry", gitProject());
        TaskRow task = drafts.confirm(drafts.create(spec(project.id())).id(), "unsafe session retry");
        tasks.start(task.id());
        AttemptRow first = tasks.attempts(task.id()).getFirst();
        String externalSessionId = mapper.activeSessions(task.id()).getFirst().externalSessionId();
        FakeOpenCodeClient fake = (FakeOpenCodeClient) openCode;
        fake.setSessionState(externalSessionId, "RUNNING");
        fake.failNextAborts(1);

        TaskRow failed = tasks.sessionFailed(task.id(), first.id(), "NETWORK", "transport state is unknown");

        assertThat(failed.state()).isEqualTo("FAILED");
        assertThat(tasks.attempts(task.id())).hasSize(1).noneMatch(attempt -> attempt.state().equals("RUNNING"));
        assertThat(tasks.errors(task.id())).anyMatch(error -> error.layer().equals(ErrorLayer.TASK.name())
                && error.code().equals("SESSION_ABORT_UNCONFIRMED"));
    }

    @Test
    void taskDurationFailureKeepsUnconfirmedWriterVisibleUntilCleanupConfirmsAbort() throws Exception {
        ProjectRow project = projects.create("task-timeout-cleanup", gitProject());
        LoopSpec shortTask = new LoopSpec("v1", project.id(), "Bound task cleanup", null,
                List.of(new LoopSpec.StageSpec("Keep the writer bounded", null, null, null,
                        List.of(new LoopSpec.VerifierSpec("FILE_EXISTS", null, "README.md", null, null, null, null)))),
                new LoopSpec.Limits(3, 12, 3, 2, 1L, 30L, 30L), null, null, null);
        TaskRow task = drafts.confirm(drafts.create(shortTask).id(), "task timeout cleanup");
        tasks.start(task.id());
        ExecutionSessionRow active = mapper.activeSessions(task.id()).getFirst();
        FakeOpenCodeClient fake = (FakeOpenCodeClient) openCode;
        fake.setSessionState(active.externalSessionId(), "RUNNING");
        fake.failNextAborts(1);

        long waitMillis = Duration.between(Instant.now(), Instant.parse(task.createdAt()).plusSeconds(1).plusMillis(25)).toMillis();
        if (waitMillis > 0) Thread.sleep(waitMillis);
        tasks.enforceTimeouts(task.id());

        assertThat(tasks.get(task.id()).state()).isEqualTo("FAILED");
        assertThat(mapper.findSession(active.id()).orElseThrow().state()).isEqualTo("DISCONNECTED");
        assertThat(tasks.attempts(task.id())).noneMatch(attempt -> attempt.state().equals("RUNNING"));
        assertThat(tasks.errors(task.id())).anyMatch(error -> error.layer().equals(ErrorLayer.SESSION.name())
                && error.code().equals("SESSION_ABORT_UNCONFIRMED") && error.retryable());

        tasks.retrySessionCleanup(active.id());

        assertThat(tasks.get(task.id()).state()).isEqualTo("FAILED");
        assertThat(mapper.findSession(active.id()).orElseThrow().state()).isEqualTo("ABORTED");
        assertThat(tasks.errors(task.id())).anyMatch(error -> error.code().equals("SESSION_ABORT_CLEANUP_CONFIRMED"));
    }

    @Test
    void terminalTaskAbortCleanupIsBoundedAndNeverClaimsAnUnconfirmedWriterWasAborted() throws Exception {
        ProjectRow project = projects.create("bounded-abort-cleanup", gitProject());
        TaskRow task = drafts.confirm(drafts.create(spec(project.id())).id(), "bounded abort cleanup");
        tasks.start(task.id());
        ExecutionSessionRow active = mapper.activeSessions(task.id()).getFirst();
        FakeOpenCodeClient fake = (FakeOpenCodeClient) openCode;
        fake.setSessionState(active.externalSessionId(), "RUNNING");
        fake.failNextAborts(10);

        tasks.cancel(task.id());
        tasks.retrySessionCleanup(active.id());
        tasks.retrySessionCleanup(active.id());
        tasks.retrySessionCleanup(active.id());
        tasks.retrySessionCleanup(active.id()); // no-op after the persisted limit

        assertThat(tasks.get(task.id()).state()).isEqualTo("CANCELLED");
        assertThat(mapper.findSession(active.id()).orElseThrow().state()).isEqualTo("DISCONNECTED");
        assertThat(mapper.sessionsPendingAbortCleanup()).isEmpty();
        assertThat(tasks.errors(task.id())).anyMatch(error -> error.code().equals("SESSION_ABORT_CLEANUP_EXHAUSTED")
                && !error.retryable());
        assertThat(openCode.sessionStatus(new OpenCodeClient.OpenCodeSession(active.externalSessionId(), Path.of(task.worktreePath()))).state())
                .isEqualTo("RUNNING");
    }

    @Test
    void stagePathGuidanceDoesNotCreateAnImplicitGitDiffVerifier() throws Exception {
        ProjectRow project = projects.create("path-policy", gitProject());
        LoopSpec restricted = new LoopSpec("v1", project.id(), "Keep changes in README", null,
                List.of(new LoopSpec.StageSpec("Edit only README", List.of("README.md"), List.of("outside.txt"), null,
                        List.of(new LoopSpec.VerifierSpec("FILE_EXISTS", null, "README.md", null, null, null, null)))),
                null, null, null, null);
        TaskRow task = drafts.confirm(drafts.create(restricted).id(), "path policy");
        tasks.start(task.id());
        Files.writeString(Path.of(task.worktreePath()).resolve("outside.txt"), "out of scope");

        TaskRow judging = tasks.verify(task.id());

        assertThat(judging.state()).isEqualTo("JUDGING");
        assertThat(tasks.attempts(task.id())).hasSize(1);
        assertThat(tasks.verifications(tasks.attempts(task.id()).getFirst().id()))
                .extracting(result -> result.type()).containsExactly("FILE_EXISTS");
        assertThat(tasks.errors(task.id())).noneMatch(error -> error.layer().equals(ErrorLayer.VERIFICATION.name()));
    }

    @Test
    void deterministicVerifierRunsOutsideTheSQLiteTransaction() throws Exception {
        ProjectRow project = projects.create("verifier-transaction-boundary", gitProject());
        TaskRow task = drafts.confirm(drafts.create(spec(project.id())).id(), "short verification transaction");
        tasks.start(task.id());
        java.util.concurrent.atomic.AtomicBoolean invoked = new java.util.concurrent.atomic.AtomicBoolean();
        org.mockito.Mockito.doAnswer(invocation -> {
            invoked.set(true);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return invocation.callRealMethod();
        }).when(verifierEngine).verify(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        tasks.verify(task.id());

        assertThat(invoked).isTrue();
        assertThat(tasks.get(task.id()).state()).isEqualTo("JUDGING");
    }

    @Test
    void pauseCanWinWhileVerificationIsOutsideSQLiteWithoutLeavingARunningAttempt() throws Exception {
        ProjectRow project = projects.create("pause-during-verification", gitProject());
        TaskRow task = drafts.confirm(drafts.create(spec(project.id())).id(), "pause verification safely");
        tasks.start(task.id());
        var verifierEntered = new java.util.concurrent.CountDownLatch(1);
        var releaseVerifier = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            verifierEntered.countDown();
            if (!releaseVerifier.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting to release verifier");
            }
            return invocation.callRealMethod();
        }).when(verifierEngine).verify(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        var verification = java.util.concurrent.CompletableFuture.supplyAsync(() -> tasks.verify(task.id()));

        try {
            assertThat(verifierEntered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(tasks.get(task.id()).state()).isEqualTo("VERIFYING");

            TaskRow paused = tasks.pause(task.id());

            assertThat(paused.state()).isEqualTo("PAUSED");
            assertThat(tasks.attempts(task.id())).noneMatch(attempt -> attempt.state().equals("RUNNING"));
        } finally {
            releaseVerifier.countDown();
        }
        assertThatThrownBy(verification::join).hasCauseInstanceOf(ConflictException.class);
        assertThat(tasks.verifications(tasks.attempts(task.id()).getFirst().id())).isEmpty();
    }

    @Test
    void missingLegacyFileExistsVerifierDoesNotCreateAnotherModelAttempt() throws Exception {
        ProjectRow project = projects.create("legacy-file-exists", gitProject());
        LoopSpec legacy = new LoopSpec("v1", project.id(), "Keep legacy artifact checks non-blocking", null,
                List.of(new LoopSpec.StageSpec("Implement and self-check", null, null, null,
                        List.of(new LoopSpec.VerifierSpec("FILE_EXISTS", null, "target/model-guessed-output.txt",
                                null, null, null, null)))), null, null, null, null);
        TaskRow task = drafts.confirm(drafts.create(legacy).id(), "legacy file exists");
        tasks.start(task.id());

        TaskRow judging = tasks.verify(task.id());

        assertThat(judging.state()).isEqualTo("JUDGING");
        assertThat(tasks.attempts(task.id())).hasSize(1);
        assertThat(tasks.verifications(tasks.attempts(task.id()).getFirst().id())).singleElement().satisfies(result -> {
            assertThat(result.type()).isEqualTo("FILE_EXISTS");
            assertThat(result.state()).isEqualTo("PASS");
            assertThat(result.summary()).contains("non-blocking");
            assertThat(result.evidenceJson()).contains("\"blocking\":false", "\"exists\":false");
        });
        assertThat(tasks.errors(task.id())).noneMatch(error -> error.layer().equals(ErrorLayer.VERIFICATION.name()));
    }

    @Test
    void malformedVerifierPathFailsTaskAndPersistsTaskErrorInsteadOfStallingMonitor() throws Exception {
        ProjectRow project = projects.create("invalid-verifier-path", gitProject());
        LoopSpec invalid = new LoopSpec("v1", project.id(), "Reject malformed verifier path", null,
                List.of(new LoopSpec.StageSpec("Check malformed path", null, null, null,
                        List.of(new LoopSpec.VerifierSpec("FILE_EXISTS", null, "bad\u0000path", null, null, null, null)))),
                null, null, null, null);
        TaskRow task = drafts.confirm(drafts.create(invalid).id(), "invalid verifier path");
        tasks.start(task.id());

        TaskRow failed = tasks.verify(task.id());

        assertThat(failed.state()).isEqualTo("FAILED");
        assertThat(tasks.attempts(task.id())).singleElement().satisfies(attempt -> {
            assertThat(attempt.state()).isEqualTo("TASK_ERROR");
            assertThat(attempt.failureKind()).isEqualTo("VERIFIER_PATH_INVALID");
        });
        assertThat(tasks.errors(task.id())).anyMatch(error -> error.layer().equals(ErrorLayer.TASK.name())
                && error.code().equals("VERIFIER_PATH_INVALID"));
        assertThat(tasks.stages(task.id())).singleElement().satisfies(stage ->
                assertThat(stage.state()).isEqualTo("FAILED"));
        assertThat(mapper.listStateTransitionsForScope("TASK", task.id(), 0, 100)).anySatisfy(event -> {
            assertThat(event.machineType()).isEqualTo("STAGE");
            assertThat(event.fromState()).isEqualTo("RUNNING");
            assertThat(event.toState()).isEqualTo("FAILED");
            assertThat(event.event()).isEqualTo("FAIL");
        });
    }

    @Test
    void verificationRefusesToRaceAStillRunningImplementationSession() throws Exception {
        ProjectRow project = projects.create("running-session-gate", gitProject());
        TaskRow task = drafts.confirm(drafts.create(spec(project.id())).id(), "running session gate");
        tasks.start(task.id());
        String externalSessionId = mapper.activeSessions(task.id()).getFirst().externalSessionId();
        ((FakeOpenCodeClient) openCode).setSessionState(externalSessionId, "RUNNING");

        assertThatThrownBy(() -> tasks.verify(task.id()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("while the implementation Session is RUNNING");

        assertThat(tasks.get(task.id()).state()).isEqualTo("RUNNING");
        assertThat(tasks.attempts(task.id())).hasSize(1).allMatch(attempt -> attempt.state().equals("RUNNING"));
        assertThat(mapper.activeSessions(task.id())).hasSize(1);
    }

    @Test
    void projectWithoutGitRunsInRegisteredDirectoryAndKeepsDiffPolicy() throws Exception {
        Path plainDirectory = Files.createDirectory(temp.resolve("not-a-repository"));
        Files.writeString(plainDirectory.resolve("README.md"), "fixture");
        ProjectRow project = projects.create("plain", plainDirectory.toString());
        LoopSpec directSpec = new LoopSpec("v1", project.id(), "Create a source file", null,
                List.of(new LoopSpec.StageSpec("Implement directly", List.of("src/**"), List.of("data/**"), List.of("src/App.java"),
                        List.of(
                                new LoopSpec.VerifierSpec("GIT_DIFF", null, null, true,
                                        List.of("src/**"), List.of("data/**"), true),
                                new LoopSpec.VerifierSpec("FILE_EXISTS", null, "src/App.java", null, null, null, null)))),
                null, null, null, null);
        TaskRow task = drafts.confirm(drafts.create(directSpec).id(), "run directly");

        assertThat(task.state()).isEqualTo("READY");
        assertThat(task.branchName()).isEqualTo("DIRECT");
        assertThat(task.worktreePath()).isEqualTo(plainDirectory.toRealPath().toString());
        assertThat(task.baselineCommit()).startsWith("direct:" + task.id() + ":");

        Files.createDirectories(plainDirectory.resolve("src"));
        Files.writeString(plainDirectory.resolve("src/App.java"), "class App {}");
        tasks.start(task.id());
        tasks.verify(task.id());

        assertThat(tasks.get(task.id()).state()).isEqualTo("JUDGING");
        assertThat(tasks.verifications(tasks.attempts(task.id()).getFirst().id()))
                .allMatch(result -> result.state().equals("PASS"));
        assertThat(tasks.diffPreview(task.id(), "src/App.java")).satisfies(preview -> {
            assertThat(preview.changeType()).isEqualTo("NEW");
            assertThat(preview.patch()).contains("+class App {}");
        });
        assertThatThrownBy(() -> tasks.diffPreview(task.id(), "README.md"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("not present in persisted GIT_DIFF evidence");
    }

    @Test
    void projectWithUnbornGitRepositoryAlsoRunsDirectly() throws Exception {
        Path projectRoot = Files.createDirectory(temp.resolve("unborn-repository"));
        Files.writeString(projectRoot.resolve("README.md"), "fixture");
        run(projectRoot, "git", "init");
        run(projectRoot, "git", "add", "README.md");
        ProjectRow project = projects.create("unborn", projectRoot.toString());

        TaskRow task = drafts.confirm(drafts.create(spec(project.id())).id(), "no head yet");

        assertThat(task.state()).isEqualTo("READY");
        assertThat(task.branchName()).isEqualTo("DIRECT");
        assertThat(task.worktreePath()).isEqualTo(projectRoot.toRealPath().toString());
    }

    @Test
    void secondDirectTaskWaitsWithoutBaselineUntilTheHolderTerminates() throws Exception {
        Path root = Files.createDirectory(temp.resolve("shared-direct-root"));
        Files.writeString(root.resolve("README.md"), "fixture");
        ProjectRow project = projects.create("shared-direct", root.toString());
        TaskRow first = drafts.confirm(drafts.create(spec(project.id())).id(), "first direct writer");
        TaskRow second = drafts.confirm(drafts.create(spec(project.id())).id(), "second direct writer");

        assertThat(first.state()).isEqualTo("READY");
        assertThat(second.state()).isEqualTo("QUEUED");
        assertThat(second.worktreePath()).isNull();
        assertThat(second.baselineCommit()).isNull();
        assertThat(tasks.queueStatus(second.id())).satisfies(queue -> {
            assertThat(queue.state()).isEqualTo("QUEUED");
            assertThat(queue.queuePosition()).isEqualTo(1L);
            assertThat(queue.leaseState()).isEqualTo("HELD");
        });

        tasks.cancel(first.id());

        assertThat(tasks.get(first.id()).state()).isEqualTo("CANCELLED");
        assertThat(tasks.get(second.id()).state()).isEqualTo("READY");
        assertThat(tasks.get(second.id()).baselineCommit()).startsWith("direct:" + second.id() + ":");
        assertThat(tasks.queueStatus(second.id()).state()).isEqualTo("ADMITTED");
    }

    @Test
    void unconfirmedDirectWriterKeepsNextTaskQueuedUntilCleanupObservesTerminalState() throws Exception {
        Path root = Files.createDirectory(temp.resolve("blocked-direct-root"));
        Files.writeString(root.resolve("README.md"), "fixture");
        ProjectRow project = projects.create("blocked-direct", root.toString());
        TaskRow first = drafts.confirm(drafts.create(spec(project.id())).id(), "blocking direct writer");
        TaskRow second = drafts.confirm(drafts.create(spec(project.id())).id(), "waiting direct writer");
        tasks.start(first.id());
        ExecutionSessionRow writer = mapper.activeSessions(first.id()).getFirst();
        FakeOpenCodeClient fake = (FakeOpenCodeClient) openCode;
        fake.setSessionState(writer.externalSessionId(), "RUNNING");
        fake.failNextAborts(1);

        tasks.cancel(first.id());

        assertThat(tasks.get(first.id()).state()).isEqualTo("CANCELLED");
        assertThat(tasks.get(second.id()).state()).isEqualTo("QUEUED");
        assertThat(tasks.queueStatus(second.id()).leaseState()).isEqualTo("RELEASE_PENDING");

        tasks.retrySessionCleanup(writer.id());

        assertThat(tasks.get(second.id()).state()).isEqualTo("READY");
        assertThat(tasks.queueStatus(second.id()).state()).isEqualTo("ADMITTED");
    }

    @Test
    void restartRehydratesATerminalHolderAndAdmitsThePersistedNextTaskWithoutLeaseExpiry() throws Exception {
        Path root = Files.createDirectory(temp.resolve("restart-direct-root"));
        Files.writeString(root.resolve("README.md"), "fixture");
        ProjectRow project = projects.create("restart-direct", root.toString());
        TaskRow first = drafts.confirm(drafts.create(spec(project.id())).id(), "crashed terminal holder");
        TaskRow second = drafts.confirm(drafts.create(spec(project.id())).id(), "persisted restart waiter");
        mapper.updateTaskState(new TaskRow(first.id(), first.projectId(), first.loopDraftId(), first.title(), "CANCELLED",
                first.worktreePath(), first.branchName(), first.baselineCommit(), first.createdAt(), Instant.now().toString(), first.version()));

        tasks.recoverAfterRestart();

        assertThat(tasks.get(first.id()).state()).isEqualTo("CANCELLED");
        assertThat(tasks.get(second.id()).state()).isEqualTo("READY");
        assertThat(tasks.queueStatus(second.id()).state()).isEqualTo("ADMITTED");
    }

    @Test
    void pauseStopsTheOldSessionAndResumeCreatesAFreshAttempt() throws Exception {
        ProjectRow project = projects.create("fixture", gitProject());
        TaskRow task = drafts.confirm(drafts.create(spec(project.id())).id(), "pause");
        tasks.start(task.id());
        tasks.pause(task.id());
        TaskRow resumed = tasks.resume(task.id());
        assertThat(resumed.state()).isEqualTo("RUNNING");
        assertThat(tasks.attempts(task.id())).hasSize(2);
        assertThat(tasks.attempts(task.id())).anyMatch(attempt -> "PAUSED".equals(attempt.failureKind()));
        assertThat(tasks.stages(task.id())).allMatch(stage -> !stage.state().equals("PAUSED"));
    }

    @Test
    void pauseWithUnconfirmedDirectWriterBlocksResumeUntilCleanupConfirmsTermination() throws Exception {
        Path root = Files.createDirectory(temp.resolve("paused-direct-root"));
        Files.writeString(root.resolve("README.md"), "fixture");
        ProjectRow project = projects.create("paused-direct", root.toString());
        TaskRow task = drafts.confirm(drafts.create(spec(project.id())).id(), "pause direct writer");
        tasks.start(task.id());
        ExecutionSessionRow writer = mapper.activeSessions(task.id()).getFirst();
        FakeOpenCodeClient fake = (FakeOpenCodeClient) openCode;
        fake.setSessionState(writer.externalSessionId(), "RUNNING");
        fake.failNextAborts(1);

        tasks.pause(task.id());

        assertThat(tasks.get(task.id()).state()).isEqualTo("PAUSED");
        assertThat(tasks.queueStatus(task.id()).leaseState()).isEqualTo("RELEASE_PENDING");
        assertThatThrownBy(() -> tasks.resume(task.id()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("writable takeover is blocked");

        tasks.retrySessionCleanup(writer.id());
        assertThat(tasks.queueStatus(task.id()).leaseState()).isEqualTo("HELD");
        assertThat(tasks.resume(task.id()).state()).isEqualTo("RUNNING");
    }

    @Test
    void restartRecoveryAbortsOldSessionAndContinuesWithFreshSession() throws Exception {
        ProjectRow project = projects.create("fixture", gitProject());
        TaskRow task = drafts.confirm(drafts.create(spec(project.id())).id(), "restart");
        tasks.start(task.id());
        String externalSessionId = mapper.activeSessions(task.id()).getFirst().externalSessionId();
        tasks.recoverAfterRestart();
        assertThat(tasks.get(task.id()).state()).isEqualTo("RUNNING");
        assertThat(openCode.sessionStatus(new OpenCodeClient.OpenCodeSession(externalSessionId, Path.of(task.worktreePath()))).state())
                .isEqualTo("ABORTED");
        assertThat(tasks.attempts(task.id())).hasSize(2);
        assertThat(tasks.attempts(task.id())).anySatisfy(attempt -> assertThat(attempt.state()).isEqualTo("SESSION_ERROR"));
        assertThat(tasks.attempts(task.id())).anySatisfy(attempt -> assertThat(attempt.state()).isEqualTo("RUNNING"));
        assertThat(tasks.errors(task.id())).anyMatch(error -> error.code().equals("RUNTIME_RESTART"));
    }

    @Test
    void restartRecoveryFailsTaskWhenOldMutatingSessionCannotBeConfirmedStopped() throws Exception {
        ProjectRow project = projects.create("unsafe-restart", gitProject());
        TaskRow task = drafts.confirm(drafts.create(spec(project.id())).id(), "unsafe restart");
        tasks.start(task.id());
        String externalSessionId = mapper.activeSessions(task.id()).getFirst().externalSessionId();
        FakeOpenCodeClient fake = (FakeOpenCodeClient) openCode;
        fake.setSessionState(externalSessionId, "RUNNING");
        fake.failNextAborts(1);

        tasks.recoverAfterRestart();

        assertThat(tasks.get(task.id()).state()).isEqualTo("FAILED");
        assertThat(tasks.attempts(task.id())).hasSize(1).noneMatch(attempt -> attempt.state().equals("RUNNING"));
        assertThat(tasks.errors(task.id())).anyMatch(error -> error.layer().equals(ErrorLayer.TASK.name())
                && error.code().equals("SESSION_ABORT_UNCONFIRMED"));
    }

    @Test
    void finalDeterministicPassRequiresTwoReadOnlyJudgesAndRetainsEvidence() throws Exception {
        ProjectRow project = projects.create("fixture", gitProject());
        TaskRow task = drafts.confirm(drafts.create(spec(project.id())).id(), "two judges");
        tasks.start(task.id());

        TaskRow judging = tasks.verify(task.id());
        assertThat(judging.state()).isEqualTo("JUDGING");
        assertThat(tasks.judges(task.id())).hasSize(2).allSatisfy(judge -> {
            assertThat(judge.state()).isEqualTo("RUNNING");
            assertThat(((FakeOpenCodeClient) openCode).isReadOnlySession(judge.externalSessionId())).isTrue();
            assertThat(((FakeOpenCodeClient) openCode).promptForSession(judge.externalSessionId()))
                    .contains("基于证据的中文 Markdown", "## 证据", "`reason` 必须使用简体中文", "每个换行正确转义")
                    .contains("PASS|REVISE|BLOCKED")
                    .doesNotContain("## Evidence");
        });
        assertThat(tasks.artifacts(task.id())).extracting(artifact -> artifact.kind())
                .contains("GIT_DIFF", "VERIFICATION_SUMMARY", "JUDGE_LOG_METADATA");

        FakeOpenCodeClient fake = (FakeOpenCodeClient) openCode;
        var startedJudges = tasks.judges(task.id());
        fake.setSessionUsage(startedJudges.get(0).externalSessionId(), List.of(new OpenCodeClient.UsageRecord(
                "requirement-message", "provider", "model", 3L, 5L, null, new BigDecimal("0.12"), "CNY", true)));
        fake.setSessionUsage(startedJudges.get(1).externalSessionId(), List.of(new OpenCodeClient.UsageRecord(
                "risk-message", "provider", "model", 7L, 11L, null, null, null, true)));

        tasks.pollJudges(task.id());
        assertThat(tasks.get(task.id()).state()).isEqualTo("SUCCEEDED");
        assertThat(tasks.judges(task.id())).allSatisfy(judge -> {
            assertThat(judge.verdict()).isEqualTo("PASS");
            assertThat(judge.rawOutput()).contains("PASS");
        });
        assertThat(tasks.artifacts(task.id())).extracting(artifact -> artifact.kind()).contains("JUDGE_RESULT");
        assertThat(mapper.listTaskUsage(task.id())).filteredOn(row -> row.judgeRunId() != null)
                .hasSize(2)
                .allSatisfy(row -> {
                    assertThat(row.executionSessionId()).isNull();
                    assertThat(row.idempotencyKey()).startsWith("usage:judge:" + row.judgeRunId() + ":");
                    assertThat(row.reliable()).isTrue();
                });
        usageInsights.collectTaskUsage(task.id());
        assertThat(mapper.listTaskUsage(task.id())).filteredOn(row -> row.judgeRunId() != null).hasSize(2);

        int attemptsAfterSuccess = tasks.attempts(task.id()).size();
        int errorsAfterSuccess = tasks.errors(task.id()).size();
        String historicalAttemptId = tasks.attempts(task.id()).getFirst().id();
        assertThatThrownBy(() -> tasks.sessionFailed(task.id(), historicalAttemptId, "LATE_CALLBACK", "stale transport callback"))
                .isInstanceOf(ConflictException.class);
        assertThat(tasks.get(task.id()).state()).isEqualTo("SUCCEEDED");
        assertThat(tasks.attempts(task.id())).hasSize(attemptsAfterSuccess);
        assertThat(tasks.errors(task.id())).hasSize(errorsAfterSuccess);
    }

    @Test
    void blockedOrUnparseableJudgeWaitsForInputInsteadOfPretendingSuccessOrFailingTask() throws Exception {
        ((FakeOpenCodeClient) openCode).setJudgeOutput("{\"verdict\":\"BLOCKED\",\"reason\":\"Missing release evidence\"}");
        ProjectRow project = projects.create("fixture", gitProject());
        TaskRow task = drafts.confirm(drafts.create(spec(project.id())).id(), "blocked judge");
        tasks.start(task.id());
        tasks.verify(task.id());

        tasks.pollJudges(task.id());
        assertThat(tasks.get(task.id()).state()).isEqualTo("WAITING_INPUT");
        assertThat(tasks.get(task.id()).state()).isNotEqualTo("FAILED");
        assertThat(tasks.errors(task.id())).anyMatch(error -> error.code().equals("JUDGE_REVIEW_NOT_APPROVED") || error.code().equals("JUDGE_CONFLICT"));
    }

    @Test
    void explicitJudgeRetryStartsAFreshPairAndCanCompleteAWaitingTask() throws Exception {
        FakeOpenCodeClient fake = (FakeOpenCodeClient) openCode;
        fake.setJudgeOutput("{\"verdict\":\"BLOCKED\",\"reason\":\"Missing release evidence\"}");
        ProjectRow project = projects.create("judge-retry", gitProject());
        TaskRow task = drafts.confirm(drafts.create(spec(project.id())).id(), "retry blocked judges");
        tasks.start(task.id()); tasks.verify(task.id()); tasks.pollJudges(task.id());

        assertThat(tasks.get(task.id()).state()).isEqualTo("WAITING_INPUT");
        assertThat(tasks.judges(task.id())).hasSize(2);

        fake.setJudgeOutput("{\"verdict\":\"PASS\",\"reason\":\"Current evidence is sufficient\"}");
        TaskRow judging = tasks.retryJudges(task.id());

        assertThat(judging.state()).isEqualTo("JUDGING");
        assertThat(tasks.judges(task.id())).hasSize(4);
        assertThat(mapper.latestJudgeRun(task.id(), "REQUIREMENT")).hasValueSatisfying(judge -> {
            assertThat(judge.ordinal()).isEqualTo(2);
            assertThat(judge.state()).isEqualTo("RUNNING");
            assertThat(fake.isReadOnlySession(judge.externalSessionId())).isTrue();
        });
        assertThat(mapper.latestJudgeRun(task.id(), "RISK")).hasValueSatisfying(judge -> assertThat(judge.ordinal()).isEqualTo(2));

        tasks.pollJudges(task.id());
        assertThat(tasks.get(task.id()).state()).isEqualTo("SUCCEEDED");
        assertThat(mapper.latestJudgeRun(task.id(), "REQUIREMENT")).hasValueSatisfying(judge -> assertThat(judge.verdict()).isEqualTo("PASS"));
        assertThat(mapper.latestJudgeRun(task.id(), "RISK")).hasValueSatisfying(judge -> assertThat(judge.verdict()).isEqualTo("PASS"));
    }

    @Test
    void reviseAndUnparseableJudgeResponsesBothStopAtWaitingInput() throws Exception {
        ((FakeOpenCodeClient) openCode).setJudgeOutput("{\"verdict\":\"REVISE\",\"reason\":\"A release note is still required\"}");
        ProjectRow reviseProject = projects.create("revise", gitProject());
        TaskRow revise = drafts.confirm(drafts.create(spec(reviseProject.id())).id(), "revise judge");
        tasks.start(revise.id()); tasks.verify(revise.id()); tasks.pollJudges(revise.id());
        assertThat(tasks.get(revise.id()).state()).isEqualTo("WAITING_INPUT");
        assertThat(tasks.judges(revise.id())).allSatisfy(judge -> assertThat(judge.verdict()).isEqualTo("REVISE"));

        ((FakeOpenCodeClient) openCode).setJudgeOutput("not a JSON decision");
        ProjectRow malformedProject = projects.create("malformed", gitProject());
        TaskRow malformed = drafts.confirm(drafts.create(spec(malformedProject.id())).id(), "unparseable judge");
        tasks.start(malformed.id()); tasks.verify(malformed.id()); tasks.pollJudges(malformed.id());
        assertThat(tasks.get(malformed.id()).state()).isEqualTo("WAITING_INPUT");
        assertThat(tasks.judges(malformed.id())).allSatisfy(judge -> assertThat(judge.verdict()).isEqualTo("UNPARSEABLE"));
    }

    @Test
    void conflictingJudgeVerdictsWaitForHumanResolution() throws Exception {
        ((FakeOpenCodeClient) openCode).setJudgeOutput("REQUIREMENT", "{\"verdict\":\"PASS\",\"reason\":\"Requirements are met\"}");
        ((FakeOpenCodeClient) openCode).setJudgeOutput("RISK", "{\"verdict\":\"BLOCKED\",\"reason\":\"Security evidence is missing\"}");
        ProjectRow project = projects.create("conflict", gitProject());
        TaskRow task = drafts.confirm(drafts.create(spec(project.id())).id(), "conflicting judges");
        tasks.start(task.id()); tasks.verify(task.id()); tasks.pollJudges(task.id());

        assertThat(tasks.get(task.id()).state()).isEqualTo("WAITING_INPUT");
        assertThat(tasks.errors(task.id())).anyMatch(error -> error.code().equals("JUDGE_CONFLICT") && error.layer().equals(ErrorLayer.VERIFICATION.name()));
    }

    @Test
    void judgeRetryStatusStartsFreshReadOnlySessionWithProviderDetailWithoutFailingTask() throws Exception {
        ProjectRow project = projects.create("fixture", gitProject());
        TaskRow task = drafts.confirm(drafts.create(spec(project.id())).id(), "judge transport recovery");
        tasks.start(task.id());
        tasks.verify(task.id());
        var retryingJudge = tasks.judges(task.id()).getFirst();
        FakeOpenCodeClient fake = (FakeOpenCodeClient) openCode;
        fake.setSessionUsage(retryingJudge.externalSessionId(), List.of(new OpenCodeClient.UsageRecord(
                "failed-judge-message", "provider", "model", 2L, 3L, null, null, null, true)));
        fake.setSessionStatus(retryingJudge.externalSessionId(), "RETRY", "Free usage exceeded");

        tasks.pollJudges(task.id());
        assertThat(tasks.get(task.id()).state()).isEqualTo("JUDGING");
        assertThat(tasks.judges(task.id())).hasSize(3);
        assertThat(tasks.errors(task.id())).anyMatch(error -> error.layer().equals(ErrorLayer.SESSION.name())
                && error.code().equals("JUDGE_SESSION_RETRY") && error.message().contains("Free usage exceeded"));
        assertThat(mapper.listTaskUsage(task.id())).anySatisfy(row -> {
            assertThat(row.judgeRunId()).isEqualTo(retryingJudge.id());
            assertThat(row.executionSessionId()).isNull();
            assertThat(row.totalTokens()).isEqualTo(5L);
            assertThat(row.idempotencyKey()).isEqualTo("usage:judge:" + retryingJudge.id() + ":failed-judge-message");
        });

        tasks.pollJudges(task.id());
        assertThat(tasks.get(task.id()).state()).isEqualTo("SUCCEEDED");
    }

    @Test
    void exhaustedJudgeSessionRetriesWaitForInputInsteadOfCreatingAnInfiniteLoopOrFailingTask() throws Exception {
        ((FakeOpenCodeClient) openCode).failNextReadOnlySessions("REQUIREMENT", 3);
        ProjectRow project = projects.create("exhaustion", gitProject());
        TaskRow task = drafts.confirm(drafts.create(spec(project.id())).id(), "judge retry exhaustion");
        tasks.start(task.id()); tasks.verify(task.id());

        tasks.pollJudges(task.id());
        tasks.pollJudges(task.id());
        tasks.pollJudges(task.id());

        assertThat(tasks.get(task.id()).state()).isEqualTo("WAITING_INPUT");
        assertThat(tasks.get(task.id()).state()).isNotEqualTo("FAILED");
        assertThat(tasks.judges(task.id()).stream().filter(judge -> judge.role().equals("REQUIREMENT") && judge.state().equals("SESSION_ERROR"))).hasSize(3);
        assertThat(tasks.errors(task.id())).anyMatch(error -> error.code().equals("JUDGE_SESSION_RETRY_EXHAUSTED") && error.layer().equals(ErrorLayer.VERIFICATION.name()));

        TaskRow judging = tasks.retryJudges(task.id());
        assertThat(judging.state()).isEqualTo("JUDGING");
        assertThat(mapper.latestJudgeRun(task.id(), "REQUIREMENT")).hasValueSatisfying(judge -> {
            assertThat(judge.ordinal()).isEqualTo(4);
            assertThat(judge.state()).isEqualTo("RUNNING");
        });
        tasks.pollJudges(task.id());
        assertThat(tasks.get(task.id()).state()).isEqualTo("SUCCEEDED");
    }

    private LoopSpec spec(String projectId) {
        return new LoopSpec("v1", projectId, "Verify README", null, List.of(new LoopSpec.StageSpec("Check README", null, null, null,
                List.of(new LoopSpec.VerifierSpec("FILE_EXISTS", null, "README.md", null, null, null, null)))), null, null, null, null);
    }
    private String gitProject() throws Exception {
        Path root = Files.createDirectory(temp.resolve("git-" + System.nanoTime()));
        Files.writeString(root.resolve("README.md"), "fixture");
        run(root, "git", "init"); run(root, "git", "config", "user.email", "test@example.invalid"); run(root, "git", "config", "user.name", "test");
        run(root, "git", "add", "README.md"); run(root, "git", "commit", "-m", "initial");
        return root.toString();
    }
    private void run(Path root, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) throw new AssertionError(output);
    }
    private List<String> localBranches(Path root) throws Exception {
        Process process = new ProcessBuilder("git", "for-each-ref", "--format=%(refname:short)", "refs/heads")
                .directory(root.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) throw new AssertionError(output);
        return output.lines().toList();
    }
}
