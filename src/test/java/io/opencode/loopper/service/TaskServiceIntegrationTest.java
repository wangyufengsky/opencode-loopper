package io.opencode.loopper.service;

import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.api.FeatureContracts;
import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.ErrorLayer;
import io.opencode.loopper.domain.ImplementationKind;
import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.domain.TaskFailure;
import io.opencode.loopper.persistence.AttemptRow;
import io.opencode.loopper.persistence.ExecutionSessionRow;
import io.opencode.loopper.persistence.ErrorEventRow;
import io.opencode.loopper.persistence.LoopDraftRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.persistence.SessionUsageRow;
import io.opencode.loopper.persistence.TaskArtifactRow;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.persistence.WorkspaceLeaseRow;
import io.opencode.loopper.runtime.FakeOpenCodeClient;
import io.opencode.loopper.runtime.OpenCodeClient;
import io.opencode.loopper.verification.VerifierEngine;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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
    @Autowired private TaskPublicationService publication;
    @Autowired private TaskMonitor monitor;
    @Autowired private OpenCodeClient openCode;
    @Autowired private LoopperMapper mapper;
    @Autowired private UsageInsightsService usageInsights;
    @Autowired private StageWorkspaceBaselineService stageWorkspaceBaselines;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LoopperProperties properties;
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
            assertThat(event.toState()).isEqualTo("QUEUED");
        });
        assertThat(events).anySatisfy(event -> {
            assertThat(event.machineType()).isEqualTo("TASK");
            assertThat(event.event()).isEqualTo("PREPARE");
            assertThat(event.fromState()).isEqualTo("QUEUED");
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
    void serializedSourceBranchesUseTaskTitleAndNumberRepeatedNames() throws Exception {
        Path root = Path.of(gitProject());
        ProjectRow project = projects.create("named-branches", root.toString());

        TaskRow first = drafts.confirm(drafts.create(spec(project.id())).id(), "隔离分支命名");
        TaskRow second = drafts.confirm(drafts.create(spec(project.id())).id(), "隔离分支命名");
        TaskRow third = drafts.confirm(drafts.create(spec(project.id())).id(), "隔离分支命名");
        TaskRow normalized = drafts.confirm(drafts.create(spec(project.id())).id(), "修复 分支/命名");
        run(root, "git", "update-ref", "refs/remotes/origin/loopper/远端同名任务", "HEAD");
        TaskRow remoteCollision = drafts.confirm(drafts.create(spec(project.id())).id(), "远端同名任务");

        assertThat(first.branchName()).isEqualTo("loopper/隔离分支命名");
        assertThat(first.worktreePath()).isEqualTo(root.toRealPath().toString());
        assertThat(second.state()).isEqualTo("QUEUED");
        assertThat(second.branchName()).isNull();

        tasks.cancel(first.id());
        second = tasks.get(second.id());
        assertThat(second.branchName()).isEqualTo("loopper/隔离分支命名(第2次)");
        tasks.cancel(second.id());
        third = tasks.get(third.id());
        assertThat(third.branchName()).isEqualTo("loopper/隔离分支命名(第3次)");
        tasks.cancel(third.id());
        normalized = tasks.get(normalized.id());
        assertThat(normalized.branchName()).isEqualTo("loopper/修复-分支-命名");
        tasks.cancel(normalized.id());
        remoteCollision = tasks.get(remoteCollision.id());
        assertThat(remoteCollision.branchName()).isEqualTo("loopper/远端同名任务(第2次)");
        assertThat(localBranches(root)).contains(first.branchName(), second.branchName(), third.branchName(),
                normalized.branchName(), remoteCollision.branchName());
    }

    @Test
    void dirtySourceCheckoutWaitsForPerFileResolutionThenPreparesTheTaskBranch() throws Exception {
        Path root = Path.of(gitProject());
        Files.writeString(root.resolve("README.md"), "local source change\n");
        Files.writeString(root.resolve("stash-only.txt"), "preserve in stash\n");
        ProjectRow project = projects.create("dirty-source-resolution", root.toString());

        TaskRow waiting = drafts.confirm(drafts.create(spec(project.id())).id(), "处理未提交文件");

        assertThat(waiting.state()).isEqualTo("WAITING_INPUT");
        assertThat(waiting.branchName()).isNull();
        assertThat(tasks.loopRetryStatus(waiting.id()).waitingReasonCode())
                .isEqualTo("SOURCE_BRANCH_WORKSPACE_DIRTY");
        assertThat(tasks.errors(waiting.id())).anySatisfy(error -> {
            assertThat(error.code()).isEqualTo("SOURCE_BRANCH_WORKSPACE_DIRTY");
            assertThat(error.retryable()).isTrue();
            assertThat(error.evidenceJson()).contains("README.md", "stash-only.txt");
        });
        var snapshot = tasks.workspaceDirtyStatus(waiting.id());
        TaskService.WorkspaceDirtyResolution resolution = tasks.resolveDirtyWorkspace(waiting.id(), snapshot.snapshotId(), List.of(
                new io.opencode.loopper.runtime.GitWorktreeManager.DirtyFileResolution(
                        "README.md", io.opencode.loopper.runtime.GitWorktreeManager.DirtyFileAction.COMMIT),
                new io.opencode.loopper.runtime.GitWorktreeManager.DirtyFileResolution(
                        "stash-only.txt", io.opencode.loopper.runtime.GitWorktreeManager.DirtyFileAction.STASH)
        ), "chore: preserve pre-task source changes");

        assertThat(resolution.task().state()).isEqualTo("READY");
        assertThat(resolution.task().branchName()).startsWith("loopper/处理未提交文件");
        assertThat(resolution.task().worktreePath()).isEqualTo(root.toRealPath().toString());
        assertThat(resolution.workspace().clean()).isTrue();
        assertThat(Files.exists(root.resolve("stash-only.txt"))).isFalse();
        assertThat(runOutput(root, "git", "show", "HEAD:README.md")).isEqualTo("local source change\n");
        assertThat(mapper.listStateTransitionsForScope("TASK", waiting.id(), 0, 100))
                .extracting(io.opencode.loopper.persistence.StateTransitionEventRow::event)
                .contains("REQUIRE_INPUT", "RETRY_PREPARATION", "PREPARATION_SUCCEEDED");
    }

    @Test
    void cancellingDirtyWorkspaceMarksTheTaskFailedWithoutChangingLocalFiles() throws Exception {
        Path root = Path.of(gitProject());
        Files.writeString(root.resolve("local-only.txt"), "keep me\n");
        ProjectRow project = projects.create("dirty-source-cancel", root.toString());
        TaskRow waiting = drafts.confirm(drafts.create(spec(project.id())).id(), "取消工作区处理");

        TaskRow failed = tasks.failDirtyWorkspace(waiting.id());

        assertThat(failed.state()).isEqualTo("FAILED");
        assertThat(failed.branchName()).isNull();
        assertThat(Files.readString(root.resolve("local-only.txt"))).isEqualTo("keep me\n");
        assertThat(tasks.errors(failed.id())).anyMatch(error ->
                error.code().equals("SOURCE_BRANCH_WORKSPACE_CANCELLED"));
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
        Path projectRoot = Path.of(gitProject()).toRealPath();
        ProjectRow project = projects.create("prompt-contract", projectRoot.toString());
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
                .contains("Authoritative execution workspace: " + projectRoot)
                .contains("Workspace branch: " + task.branchName())
                .contains("All reads, writes, AgentBridge tool calls")
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
        stageWorkspaceBaselines.captureIfAbsent(task, stage);
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

        tasks.start(task.id());
        Files.createDirectories(plainDirectory.resolve("src"));
        Files.writeString(plainDirectory.resolve("src/App.java"), "class App {}");
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
    void multiPackageGitDiffUsesEachStageStartAndKeepsFinalTaskDiffCumulative() throws Exception {
        Path root = Path.of(gitProject()).toRealPath();
        ProjectRow project = projects.create("stage-scoped-git-diff", root.toString());
        TaskRow task = drafts.confirm(drafts.create(stageScopedGitDiffSpec(project.id())).id(),
                "stage scoped package diff");

        tasks.start(task.id());
        Files.writeString(root.resolve("first.txt"), "first");
        assertThat(tasks.verify(task.id()).state()).isEqualTo("RUNNING");

        var stages = tasks.stages(task.id());
        assertThat(stages).extracting(stage -> stage.workPackageId()).containsExactly("WP-1", "WP-2");
        assertThat(mapper.findStageWorkspaceBaseline(stages.get(1).id())).isPresent();
        Files.writeString(root.resolve("second.txt"), "second");

        assertThat(tasks.verify(task.id()).state()).isEqualTo("JUDGING");
        AttemptRow firstAttempt = mapper.latestAttempt(stages.get(0).id()).orElseThrow();
        AttemptRow secondAttempt = mapper.latestAttempt(stages.get(1).id()).orElseThrow();
        assertThat(mapper.listVerifications(firstAttempt.id())).filteredOn(result -> result.type().equals("GIT_DIFF"))
                .singleElement().satisfies(result -> assertThat(result.evidenceJson())
                        .contains("\"baselineScope\":\"STAGE\"", "\"changedPaths\":[\"first.txt\"]")
                        .doesNotContain("second.txt"));
        assertThat(mapper.listVerifications(secondAttempt.id())).filteredOn(result -> result.type().equals("GIT_DIFF"))
                .singleElement().satisfies(result -> assertThat(result.evidenceJson())
                        .contains("\"baselineScope\":\"STAGE\"", "\"changedPaths\":[\"second.txt\"]")
                        .doesNotContain("first.txt"));
        assertThat(tasks.artifacts(task.id())).filteredOn(artifact -> artifact.kind().equals("GIT_DIFF"))
                .singleElement().satisfies(artifact -> assertThat(artifact.content())
                        .contains("\"baselineScope\":\"TASK\"", "first.txt", "second.txt"));
    }

    @Test
    void multiPackageDirectWorkspaceUsesTheSameStageScopedDiffContract() throws Exception {
        Path root = Files.createDirectory(temp.resolve("stage-scoped-direct"));
        Files.writeString(root.resolve("README.md"), "fixture");
        ProjectRow project = projects.create("stage-scoped-direct", root.toString());
        TaskRow task = drafts.confirm(drafts.create(stageScopedGitDiffSpec(project.id())).id(),
                "direct stage scoped package diff");
        assertThat(task.baselineCommit()).startsWith("direct:" + task.id() + ":");

        tasks.start(task.id());
        Files.writeString(root.resolve("first.txt"), "first");
        assertThat(tasks.verify(task.id()).state()).isEqualTo("RUNNING");
        Files.writeString(root.resolve("second.txt"), "second");
        assertThat(tasks.verify(task.id()).state()).isEqualTo("JUDGING");

        var stages = tasks.stages(task.id());
        AttemptRow secondAttempt = mapper.latestAttempt(stages.get(1).id()).orElseThrow();
        assertThat(mapper.listVerifications(secondAttempt.id())).filteredOn(result -> result.type().equals("GIT_DIFF"))
                .singleElement().satisfies(result -> assertThat(result.evidenceJson())
                        .contains("\"baselineScope\":\"STAGE\"", "\"changedPaths\":[\"second.txt\"]")
                        .doesNotContain("first.txt"));
        assertThat(tasks.artifacts(task.id())).filteredOn(artifact -> artifact.kind().equals("GIT_DIFF"))
                .singleElement().satisfies(artifact -> assertThat(artifact.content())
                        .contains("\"baselineScope\":\"TASK\"", "first.txt", "second.txt"));
    }

    @Test
    void laterStageNoOpAndPredecessorEditUseTheSameImmutableStageBaseline() throws Exception {
        Path root = Path.of(gitProject()).toRealPath();
        ProjectRow project = projects.create("stage-no-op-and-violation", root.toString());
        TaskRow task = drafts.confirm(drafts.create(stageScopedGitDiffSpec(project.id())).id(),
                "stage baseline retry");
        tasks.start(task.id());
        Files.writeString(root.resolve("first.txt"), "first");
        assertThat(tasks.verify(task.id()).state()).isEqualTo("RUNNING");
        var secondStage = tasks.stages(task.id()).get(1);
        String baseline = mapper.findStageWorkspaceBaseline(secondStage.id()).orElseThrow().baselineRef();

        TaskRow afterNoOp = tasks.verify(task.id());
        assertThat(afterNoOp.state()).as(tasks.errors(task.id()).toString()).isEqualTo("RUNNING");
        AttemptRow firstStageTwoAttempt = tasks.attempts(task.id()).stream()
                .filter(attempt -> attempt.stageId().equals(secondStage.id()) && attempt.ordinal() == 1)
                .findFirst().orElseThrow();
        assertThat(mapper.listVerifications(firstStageTwoAttempt.id())).filteredOn(result -> result.type().equals("GIT_DIFF"))
                .singleElement().satisfies(result -> {
                    assertThat(result.state()).isEqualTo("FAIL");
                    assertThat(result.summary()).contains("no files changed");
                });
        assertThat(tasks.artifacts(task.id())).filteredOn(artifact ->
                        artifact.kind().equals("ATTEMPT_HANDOFF")
                                && firstStageTwoAttempt.id().equals(artifact.attemptId()))
                .singleElement().satisfies(artifact -> assertThat(artifact.content())
                        .contains("\"changedPaths\":[]"));
        assertThat(mapper.findStageWorkspaceBaseline(secondStage.id()).orElseThrow().baselineRef()).isEqualTo(baseline);

        Files.writeString(root.resolve("first.txt"), "changed by the later stage");
        assertThat(tasks.verify(task.id()).state()).isEqualTo("RUNNING");
        AttemptRow secondStageTwoAttempt = tasks.attempts(task.id()).stream()
                .filter(attempt -> attempt.stageId().equals(secondStage.id()) && attempt.ordinal() == 2)
                .findFirst().orElseThrow();
        assertThat(mapper.listVerifications(secondStageTwoAttempt.id())).filteredOn(result -> result.type().equals("GIT_DIFF"))
                .singleElement().satisfies(result -> {
                    assertThat(result.state()).isEqualTo("FAIL");
                    assertThat(result.summary()).contains("outside allowed paths: first.txt");
                });
        assertThat(tasks.artifacts(task.id())).filteredOn(artifact ->
                        artifact.kind().equals("ATTEMPT_HANDOFF")
                                && secondStageTwoAttempt.id().equals(artifact.attemptId()))
                .singleElement().satisfies(artifact -> assertThat(artifact.content())
                        .contains("\"changedPaths\":[\"first.txt\"]"));
        assertThat(mapper.findStageWorkspaceBaseline(secondStage.id()).orElseThrow().baselineRef()).isEqualTo(baseline);
    }

    @Test
    void legacyAttemptWithoutStageWorkspaceBaselineFailsBeforeOpeningAnotherSession() throws Exception {
        ProjectRow project = projects.create("legacy-stage-baseline", gitProject());
        TaskRow task = drafts.confirm(drafts.create(spec(project.id())).id(), "legacy missing stage baseline");
        tasks.start(task.id());
        var stage = tasks.stages(task.id()).getFirst();
        AttemptRow attempt = tasks.attempts(task.id()).getFirst();
        int promptCalls = ((FakeOpenCodeClient) openCode).promptCalls();
        assertThat(jdbc.update("DELETE FROM stage_workspace_baseline WHERE stage_id=?", stage.id())).isEqualTo(1);

        TaskRow failed = tasks.sessionFailed(task.id(), attempt.id(), "NETWORK", "retry legacy stage");

        assertThat(failed.state()).isEqualTo("FAILED");
        assertThat(tasks.attempts(task.id())).hasSize(1);
        assertThat(mapper.listSessions(task.id())).hasSize(1);
        assertThat(((FakeOpenCodeClient) openCode).promptCalls()).isEqualTo(promptCalls);
        assertThat(tasks.errors(task.id())).anyMatch(error ->
                error.code().equals("STAGE_WORKSPACE_BASELINE_MISSING")
                        && error.layer().equals(ErrorLayer.TASK.name()));
    }

    @Test
    void unavailablePersistedStageBaselineFailsBeforeOpeningRetrySession() throws Exception {
        ProjectRow project = projects.create("unavailable-stage-baseline", gitProject());
        TaskRow task = drafts.confirm(drafts.create(spec(project.id())).id(), "unavailable stage baseline");
        tasks.start(task.id());
        var stage = tasks.stages(task.id()).getFirst();
        AttemptRow attempt = tasks.attempts(task.id()).getFirst();
        int promptCalls = ((FakeOpenCodeClient) openCode).promptCalls();
        Path index = properties.getDataDir().resolve("stage-baselines").resolve(task.id())
                .resolve("indexes").resolve(stage.id() + ".index");
        assertThat(index).isRegularFile();
        Files.delete(index);

        TaskRow failed = tasks.sessionFailed(task.id(), attempt.id(), "NETWORK", "retry without baseline storage");

        assertThat(failed.state()).isEqualTo("FAILED");
        assertThat(tasks.attempts(task.id())).hasSize(1);
        assertThat(mapper.listSessions(task.id())).hasSize(1);
        assertThat(((FakeOpenCodeClient) openCode).promptCalls()).isEqualTo(promptCalls);
        assertThat(tasks.errors(task.id())).anyMatch(error ->
                error.code().equals("STAGE_WORKSPACE_BASELINE_UNAVAILABLE")
                        && error.layer().equals(ErrorLayer.TASK.name()));
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
    void queuedTaskCanBeCancelledArchivedAndDeletedWithoutReleasingTheCurrentHolder() throws Exception {
        Path root = Files.createDirectory(temp.resolve("cancel-queued-direct-root"));
        Files.writeString(root.resolve("README.md"), "fixture");
        ProjectRow project = projects.create("cancel-queued-direct", root.toString());
        TaskRow holder = drafts.confirm(drafts.create(spec(project.id())).id(), "active direct writer");
        TaskRow queued = drafts.confirm(drafts.create(spec(project.id())).id(), "queued direct writer");

        TaskRow cancelled = tasks.cancel(queued.id());

        assertThat(cancelled.state()).isEqualTo("CANCELLED");
        assertThat(tasks.queueStatus(queued.id()).state()).isEqualTo("CANCELLED");
        assertThat(tasks.get(holder.id()).state()).isEqualTo("READY");
        assertThat(mapper.findWorkspaceLease(root.toRealPath().toString()).orElseThrow().holderTaskId())
                .isEqualTo(holder.id());

        tasks.archive(queued.id());
        assertThat(tasks.archived(queued.id())).isTrue();
        tasks.deleteArchived(queued.id());

        assertThat(mapper.findTask(queued.id())).isEmpty();
        assertThat(tasks.get(holder.id()).state()).isEqualTo("READY");
    }

    @Test
    void dirtyTerminalHolderStaysVisibleAndManualReconciliationAdmitsTheWaiterAfterCleanup() throws Exception {
        Path root = Path.of(gitProject());
        ProjectRow project = projects.create("manual-terminal-reconcile", root.toString());
        TaskRow holder = drafts.confirm(drafts.create(spec(project.id())).id(), "dirty cancelled holder");
        TaskRow waiter = drafts.confirm(drafts.create(spec(project.id())).id(), "waiting after cleanup");
        Files.writeString(root.resolve("unfinished.txt"), "retain the lease\n");

        tasks.cancel(holder.id());

        assertThat(tasks.get(holder.id()).state()).isEqualTo("CANCELLED");
        assertThat(tasks.get(waiter.id()).state()).isEqualTo("QUEUED");
        assertThat(tasks.queueStatus(waiter.id())).satisfies(queue -> {
            assertThat(queue.holderTaskId()).isEqualTo(holder.id());
            assertThat(queue.holderTaskTitle()).isEqualTo("dirty cancelled holder");
            assertThat(queue.holderTaskState()).isEqualTo("CANCELLED");
            assertThat(queue.holderArchived()).isFalse();
            assertThat(queue.releaseReason()).isEqualTo("SOURCE_BRANCH_WORKSPACE_DIRTY");
            assertThat(queue.reconcileAvailable()).isTrue();
        });
        assertThatThrownBy(() -> tasks.reconcileQueue(waiter.id()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("未提交或未跟踪文件");
        assertThatThrownBy(() -> tasks.reconcileQueue(waiter.id()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("未提交或未跟踪文件");
        assertThat(mapper.eventsAfter(holder.id(), 0).stream()
                .filter(event -> "workspace.lease_reconcile_blocked".equals(event.type()))).hasSize(1);

        Files.delete(root.resolve("unfinished.txt"));
        FeatureContracts.QueueStatusDto reconciled = tasks.reconcileQueue(waiter.id());

        assertThat(reconciled.state()).isEqualTo("ADMITTED");
        assertThat(tasks.get(waiter.id()).state()).isEqualTo("READY");
        assertThat(mapper.findTaskQueue(holder.id()).orElseThrow().state()).isEqualTo("FINISHED");
        assertThat(mapper.findWorkspaceLease(root.toRealPath().toString()).orElseThrow().holderTaskId())
                .isEqualTo(waiter.id());
        assertThat(tasks.reconcileQueue(waiter.id()).state()).isEqualTo("ADMITTED");
    }

    @Test
    void archiveAndPermanentDeleteRejectAnActiveHolderUntilSafeReconciliationCompletes() throws Exception {
        Path root = Path.of(gitProject());
        ProjectRow project = projects.create("archive-lease-guard", root.toString());
        TaskRow holder = drafts.confirm(drafts.create(spec(project.id())).id(), "archivable holder");
        TaskRow waiter = drafts.confirm(drafts.create(spec(project.id())).id(), "archive waiter");
        Files.writeString(root.resolve("unpublished.txt"), "block archive\n");
        tasks.cancel(holder.id());

        assertThatThrownBy(() -> tasks.archive(holder.id()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("释放完成前不能归档", "SOURCE_BRANCH_WORKSPACE_DIRTY");
        assertThat(tasks.archived(holder.id())).isFalse();

        mapper.archiveTask(holder.id(), Instant.now().toString());
        assertThatThrownBy(() -> tasks.deleteArchived(holder.id()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("活动项目写租约 holder");
        assertThat(mapper.findWorkspaceLease(root.toRealPath().toString()).orElseThrow().holderTaskId())
                .isEqualTo(holder.id());

        Files.delete(root.resolve("unpublished.txt"));
        tasks.archive(holder.id());

        assertThat(tasks.archived(holder.id())).isTrue();
        assertThat(tasks.get(waiter.id()).state()).isEqualTo("READY");
        assertThat(mapper.findTaskQueue(holder.id()).orElseThrow().state()).isEqualTo("FINISHED");
    }

    @Test
    void fingerprintMismatchFailsClosedBeforeTheTaskBranchCanBeRestored() throws Exception {
        Path root = Path.of(gitProject());
        ProjectRow project = projects.create("fingerprint-reconcile", root.toString());
        TaskRow holder = drafts.confirm(drafts.create(spec(project.id())).id(), "fingerprint holder");
        TaskRow waiter = drafts.confirm(drafts.create(spec(project.id())).id(), "fingerprint waiter");
        Files.writeString(root.resolve("temporary.txt"), "block initial release\n");
        tasks.cancel(holder.id());
        Files.delete(root.resolve("temporary.txt"));
        String holderBranch = runOutput(root, "git", "branch", "--show-current").trim();
        WorkspaceLeaseRow lease = mapper.findWorkspaceLease(root.toRealPath().toString()).orElseThrow();
        assertThat(mapper.updateWorkspaceLease(new WorkspaceLeaseRow(
                lease.canonicalRoot(), "changed-fingerprint", lease.mode(), lease.holderTaskId(),
                lease.writerSessionId(), lease.state(), lease.acquiredAt(), lease.heartbeatAt(),
                lease.releasedAt(), lease.releaseReason(), lease.version()))).isEqualTo(1);

        assertThatThrownBy(() -> tasks.reconcileQueue(waiter.id()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("稳定指纹已变化");

        assertThat(runOutput(root, "git", "branch", "--show-current").trim()).isEqualTo(holderBranch);
        assertThat(mapper.findTaskQueue(holder.id()).orElseThrow().state()).isEqualTo("ADMITTED");
        assertThat(mapper.findTaskQueue(waiter.id()).orElseThrow().state()).isEqualTo("QUEUED");
    }

    @Test
    void unavailableWorkspaceAndUnexpectedBranchBothFailClosed() throws Exception {
        Path unavailableRoot = Path.of(gitProject());
        ProjectRow unavailableProject = projects.create("unavailable-reconcile", unavailableRoot.toString());
        TaskRow unavailableHolder = drafts.confirm(drafts.create(spec(unavailableProject.id())).id(), "unavailable holder");
        TaskRow unavailableWaiter = drafts.confirm(drafts.create(spec(unavailableProject.id())).id(), "unavailable waiter");
        Files.writeString(unavailableRoot.resolve("temporary.txt"), "block initial release\n");
        tasks.cancel(unavailableHolder.id());
        Files.delete(unavailableRoot.resolve("temporary.txt"));
        Files.move(unavailableRoot, unavailableRoot.resolveSibling(unavailableRoot.getFileName() + "-moved"));

        assertThatThrownBy(() -> tasks.reconcileQueue(unavailableWaiter.id()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("cannot be resolved");
        assertThat(mapper.findTaskQueue(unavailableWaiter.id()).orElseThrow().state()).isEqualTo("QUEUED");

        Path branchRoot = Path.of(gitProject());
        ProjectRow branchProject = projects.create("branch-reconcile", branchRoot.toString());
        TaskRow branchHolder = drafts.confirm(drafts.create(spec(branchProject.id())).id(), "branch holder");
        TaskRow branchWaiter = drafts.confirm(drafts.create(spec(branchProject.id())).id(), "branch waiter");
        Files.writeString(branchRoot.resolve("temporary.txt"), "block initial release\n");
        tasks.cancel(branchHolder.id());
        Files.delete(branchRoot.resolve("temporary.txt"));
        run(branchRoot, "git", "switch", "-c", "unexpected-branch");

        assertThatThrownBy(() -> tasks.reconcileQueue(branchWaiter.id()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("instead of Task branch");
        assertThat(runOutput(branchRoot, "git", "branch", "--show-current").trim()).isEqualTo("unexpected-branch");
        assertThat(mapper.findTaskQueue(branchWaiter.id()).orElseThrow().state()).isEqualTo("QUEUED");
    }

    @Test
    void concurrentManualAndAutomaticReconciliationTransferExactlyOneFifoWaiter() throws Exception {
        Path root = Path.of(gitProject());
        ProjectRow project = projects.create("concurrent-reconcile", root.toString());
        TaskRow holder = drafts.confirm(drafts.create(spec(project.id())).id(), "concurrent holder");
        TaskRow firstWaiter = drafts.confirm(drafts.create(spec(project.id())).id(), "first waiter");
        TaskRow secondWaiter = drafts.confirm(drafts.create(spec(project.id())).id(), "second waiter");
        Files.writeString(root.resolve("temporary.txt"), "block initial release\n");
        tasks.cancel(holder.id());
        Files.delete(root.resolve("temporary.txt"));
        run(root, "git", "switch", holder.sourceBranch());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<?> manual = pool.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                tasks.reconcileQueue(firstWaiter.id());
                return null;
            });
            Future<?> automatic = pool.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                tasks.reconcileTerminalWorkspaceLeasesWithWaiters();
                return null;
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            manual.get(20, TimeUnit.SECONDS);
            automatic.get(20, TimeUnit.SECONDS);
        }

        assertThat(mapper.findTaskQueue(holder.id()).orElseThrow().state()).isEqualTo("FINISHED");
        assertThat(mapper.findTaskQueue(firstWaiter.id()).orElseThrow().state()).isEqualTo("ADMITTED");
        assertThat(mapper.findTaskQueue(secondWaiter.id()).orElseThrow().state()).isEqualTo("QUEUED");
        assertThat(tasks.get(firstWaiter.id()).state()).isEqualTo("READY");
        assertThat(tasks.get(secondWaiter.id()).state()).isEqualTo("QUEUED");
        assertThat(mapper.eventsAfter(holder.id(), 0).stream()
                .filter(event -> "workspace.lease_released".equals(event.type()))).hasSize(1);
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
        mapper.archiveTask(first.id(), Instant.now().toString());

        tasks.recoverAfterRestart();

        assertThat(tasks.get(first.id()).state()).isEqualTo("CANCELLED");
        assertThat(tasks.archived(first.id())).isTrue();
        assertThat(mapper.findTaskQueue(first.id()).orElseThrow().state()).isEqualTo("FINISHED");
        assertThat(tasks.get(second.id()).state()).isEqualTo("READY");
        assertThat(tasks.queueStatus(second.id()).state()).isEqualTo("ADMITTED");
    }

    @Test
    void scheduledReconciliationSkipsTerminalHoldersWithoutQueuedWaiters() throws Exception {
        Path root = Path.of(gitProject());
        ProjectRow project = projects.create("no-waiter-reconcile", root.toString());
        TaskRow holder = drafts.confirm(drafts.create(spec(project.id())).id(), "terminal without waiter");
        Files.writeString(root.resolve("temporary.txt"), "dirty\n");
        tasks.cancel(holder.id());
        Files.delete(root.resolve("temporary.txt"));

        tasks.reconcileTerminalWorkspaceLeasesWithWaiters();

        assertThat(mapper.findTaskQueue(holder.id()).orElseThrow().state()).isEqualTo("ADMITTED");
        assertThat(mapper.findWorkspaceLease(root.toRealPath().toString()).orElseThrow().holderTaskId())
                .isEqualTo(holder.id());
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
        TaskRow task = drafts.confirm(drafts.create(judgeContractSpec(project.id())).id(), "two judges");
        tasks.start(task.id());

        TaskRow judging = tasks.verify(task.id());
        assertThat(judging.state()).isEqualTo("JUDGING");
        assertThat(tasks.judges(task.id())).hasSize(2).allSatisfy(judge -> {
            assertThat(judge.state()).isEqualTo("RUNNING");
            assertThat(((FakeOpenCodeClient) openCode).isReadOnlySession(judge.externalSessionId())).isTrue();
            assertThat(((FakeOpenCodeClient) openCode).promptForSession(judge.externalSessionId()))
                    .contains("基于证据的中文 Markdown", "## 证据", "`reason` 必须使用简体中文", "每个换行正确转义")
                    .contains("跨阶段 AI 验收合同", "AC-1 [BOTH]", "评审准则：检查边界行为与需求一致性")
                    .contains("MACHINE 条件由确定性验证负责")
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
    void finalReviewAggregatesDeterministicEvidenceFromEverySucceededStage() throws Exception {
        ProjectRow project = projects.create("multi-stage-judge", gitProject());
        TaskRow task = drafts.confirm(drafts.create(multiStageJudgeContractSpec(project.id())).id(),
                "multi stage judges");
        tasks.start(task.id());

        assertThat(tasks.verify(task.id()).state()).isEqualTo("RUNNING");
        assertThat(tasks.verify(task.id()).state()).isEqualTo("JUDGING");

        String summary = tasks.artifacts(task.id()).stream()
                .filter(artifact -> artifact.kind().equals("VERIFICATION_SUMMARY"))
                .map(TaskArtifactRow::content)
                .filter(content -> content.contains("\"schemaVersion\":\"v2\""))
                .findFirst().orElseThrow();
        assertThat(summary)
                .contains("\"ordinal\":0", "\"ordinal\":1", "Stage one", "Stage two")
                .contains("\"allPassed\":true");
        assertThat(tasks.judges(task.id())).hasSize(2).allSatisfy(judge ->
                assertThat(((FakeOpenCodeClient) openCode).promptForSession(judge.externalSessionId()))
                        .contains("跨阶段确定性验证摘要", "阶段 1：Stage one", "阶段 2：Stage two")
                        .contains("\"ordinal\":0", "\"ordinal\":1", "AC-1", "AC-2"));
    }

    @Test
    void oversizedJudgePromptWaitsForInputBeforeCreatingAnyJudgeSession() throws Exception {
        ProjectRow project = projects.create("bounded-judge-prompt", gitProject());
        TaskRow task = drafts.confirm(drafts.create(judgeContractSpec(project.id())).id(), "bounded judges");
        tasks.start(task.id());
        AttemptRow attempt = tasks.attempts(task.id()).getFirst();
        mapper.insertTaskArtifact(new TaskArtifactRow(UUID.randomUUID().toString(), task.id(), attempt.id(), null,
                "GIT_DIFF", "oversized-task-diff.json", "application/json",
                "x".repeat(JudgePromptPolicy.MAX_PROMPT_UTF8_BYTES), "{}", Instant.now().toString()));
        FakeOpenCodeClient fake = (FakeOpenCodeClient) openCode;
        int promptCallsBeforeReview = fake.promptCalls();

        TaskRow waiting = tasks.verify(task.id());

        assertThat(waiting.state()).isEqualTo("WAITING_INPUT");
        assertThat(tasks.judges(task.id())).isEmpty();
        assertThat(fake.promptCalls()).isEqualTo(promptCallsBeforeReview);
        assertThat(tasks.errors(task.id())).anyMatch(error -> error.code().equals("JUDGE_PROMPT_BUDGET_EXCEEDED")
                && error.layer().equals(ErrorLayer.VERIFICATION.name()));
    }

    @Test
    void preflightsBothRolePromptsBeforeStartingRequirementJudgeOnExplicitRetry() throws Exception {
        ProjectRow project = projects.create("boundary-judge-prompt", gitProject());
        LoopSpec loopSpec = judgeContractSpec(project.id());
        TaskRow task = drafts.confirm(drafts.create(loopSpec).id(), "boundary judges");
        tasks.start(task.id());
        AttemptRow attempt = tasks.attempts(task.id()).getFirst();
        mapper.insertTaskArtifact(new TaskArtifactRow(UUID.randomUUID().toString(), task.id(), attempt.id(), null,
                "GIT_DIFF", "initial-oversized-task-diff.json", "application/json",
                "x".repeat(JudgePromptPolicy.MAX_PROMPT_UTF8_BYTES), "{}", Instant.now().toString()));
        assertThat(tasks.verify(task.id()).state()).isEqualTo("WAITING_INPUT");

        String objectives = "- 阶段 1：" + mapper.listStages(task.id()).getFirst().objective();
        String verification = tasks.artifacts(task.id()).stream()
                .filter(artifact -> artifact.kind().equals("VERIFICATION_SUMMARY"))
                .map(TaskArtifactRow::content)
                .filter(content -> content.contains("\"schemaVersion\":\"v2\""))
                .findFirst().orElseThrow();
        String requirementWithoutDiff = JudgePromptPolicy.prompt(loopSpec, "REQUIREMENT", objectives,
                verification, "", attempt.id());
        int boundaryDiffBytes = JudgePromptPolicy.MAX_PROMPT_UTF8_BYTES
                - JudgePromptPolicy.utf8Bytes(requirementWithoutDiff);
        String boundaryDiff = "x".repeat(boundaryDiffBytes);
        assertThat(JudgePromptPolicy.utf8Bytes(JudgePromptPolicy.prompt(loopSpec, "REQUIREMENT", objectives,
                verification, boundaryDiff, attempt.id()))).isEqualTo(JudgePromptPolicy.MAX_PROMPT_UTF8_BYTES);
        assertThatThrownBy(() -> JudgePromptPolicy.prompt(loopSpec, "RISK", objectives,
                verification, boundaryDiff, attempt.id()))
                .isInstanceOf(TaskFailure.class)
                .satisfies(error -> assertThat(((TaskFailure) error).code())
                        .isEqualTo("JUDGE_PROMPT_BUDGET_EXCEEDED"));
        mapper.insertTaskArtifact(new TaskArtifactRow(UUID.randomUUID().toString(), task.id(), attempt.id(), null,
                "GIT_DIFF", "boundary-task-diff.json", "application/json", boundaryDiff, "{}",
                Instant.now().plusSeconds(30).toString()));
        FakeOpenCodeClient fake = (FakeOpenCodeClient) openCode;
        int promptCallsBeforeRetry = fake.promptCalls();

        TaskRow waiting = tasks.retryJudges(task.id());

        assertThat(waiting.state()).isEqualTo("WAITING_INPUT");
        assertThat(tasks.judges(task.id())).isEmpty();
        assertThat(fake.promptCalls()).isEqualTo(promptCallsBeforeRetry);
        assertThat(tasks.errors(task.id())).anyMatch(error -> error.code().equals("JUDGE_PROMPT_BUDGET_EXCEEDED")
                && error.layer().equals(ErrorLayer.VERIFICATION.name()));
    }

    @Test
    void finalEvidenceCapturesBaselineDiffWithoutGitDiffVerifierAndPreviewSurvivesBranchRestore() throws Exception {
        ProjectRow project = projects.create("stable-task-diff", gitProject());
        TaskRow task = drafts.confirm(drafts.create(spec(project.id())).id(), "stable task diff");
        Path workspace = Path.of(task.worktreePath());
        Files.writeString(workspace.resolve("feature.txt"), "verified change\n");
        tasks.start(task.id());

        tasks.verify(task.id());
        tasks.pollJudges(task.id());

        assertThat(tasks.get(task.id()).state()).isEqualTo("SUCCEEDED");
        assertThat(tasks.verifications(tasks.attempts(task.id()).getFirst().id()))
                .noneMatch(result -> result.type().equals("GIT_DIFF"));
        assertThat(tasks.artifacts(task.id())).filteredOn(artifact -> artifact.kind().equals("GIT_DIFF"))
                .singleElement().satisfies(artifact -> {
                    assertThat(artifact.name()).isEqualTo("task-diff.json");
                    assertThat(artifact.metadataJson()).contains("deterministic-task-baseline-diff", "feature.txt");
                });
        assertThat(tasks.diffPreview(task.id(), "feature.txt").patch()).contains("+verified change");

        TaskPublicationService.PublicationStatus published = publication.commitAndPush(
                task.id(), "#3032_持久化任务分支差异");

        assertThat(published.state()).isEqualTo("SYNCED_LOCAL");
        assertThat(runOutput(workspace, "git", "branch", "--show-current").strip()).isEqualTo(task.sourceBranch());
        assertThat(tasks.diffPreview(task.id(), "feature.txt").patch()).contains("+verified change");
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

    @Test
    void verifierFailurePersistsBoundedHandoffAndInjectsConfiguredRetryInstructions() throws Exception {
        ProjectRow project = projects.create("attempt-handoff", gitProject());
        LoopSpec handoffSpec = failingContentSpec(project.id(), 3,
                "优先处理 ${failureSummary}；复核 ${changedPaths}；指纹 ${workspaceFingerprint}",
                new LoopSpec.SessionPolicy(true, true));
        TaskRow task = drafts.confirm(drafts.create(handoffSpec).id(), "persist attempt handoff");
        tasks.start(task.id());

        TaskRow retrying = tasks.verify(task.id());

        assertThat(retrying.state()).isEqualTo("RUNNING");
        assertThat(tasks.attempts(task.id())).hasSize(2);
        assertThat(tasks.artifacts(task.id())).filteredOn(artifact -> artifact.kind().equals("ATTEMPT_HANDOFF"))
                .singleElement().satisfies(artifact -> {
                    assertThat(artifact.content()).contains("\"workspaceReliable\":true", "\"consecutiveStagnationCount\":1");
                    assertThat(artifact.content().length()).isLessThan(40_000);
        });
        ExecutionSessionRow retrySession = mapper.activeSessions(task.id()).getFirst();
        String retryPrompt = ((FakeOpenCodeClient) openCode).promptForSession(retrySession.externalSessionId());
        assertThat(retryPrompt.length()).isLessThan(20_000);
        assertThat(retryPrompt)
                .contains("Previous Attempt handoff (server-generated, bounded, and read-only)")
                .contains("LoopSpec next-attempt instructions")
                .contains("优先处理", "复核 (none)", "指纹");
    }

    @Test
    void unchangedFailureStopsAtStagnationLimitAndExplicitConfirmationStartsOneFreshRetry() throws Exception {
        ProjectRow project = projects.create("stagnation-stop", gitProject());
        LoopSpec stagnant = failingContentSpec(project.id(), 2,
                "人工继续时优先处理 ${failureSummary}，并检查 ${changedPaths}",
                new LoopSpec.SessionPolicy(true, true));
        TaskRow task = drafts.confirm(drafts.create(stagnant).id(), "stop unchanged loop");
        tasks.start(task.id());

        tasks.verify(task.id());
        TaskRow waiting = tasks.verify(task.id());

        assertThat(waiting.state()).isEqualTo("WAITING_INPUT");
        assertThat(tasks.attempts(task.id())).hasSize(2);
        assertThat(mapper.activeSessions(task.id())).isEmpty();
        assertThat(tasks.errors(task.id())).filteredOn(error -> error.code().equals("LOOP_STAGNATION_DETECTED"))
                .singleElement().satisfies(error -> {
                    assertThat(error.layer()).isEqualTo(ErrorLayer.VERIFICATION.name());
                    assertThat(error.retryable()).isTrue();
                    assertThat(error.evidenceJson()).contains("\"stagnationCount\":2", "\"explicitRetryAvailable\":true");
                });
        assertThat(tasks.artifacts(task.id())).filteredOn(artifact -> artifact.kind().equals("ATTEMPT_HANDOFF"))
                .hasSize(2).anySatisfy(artifact -> assertThat(artifact.content()).contains("\"consecutiveStagnationCount\":2"));

        TaskRow retried = tasks.retryWaitingLoop(task.id());

        assertThat(retried.state()).isEqualTo("RUNNING");
        assertThat(tasks.attempts(task.id())).hasSize(3);
        assertThat(tasks.artifacts(task.id())).anyMatch(artifact -> artifact.kind().equals("LOOP_STAGNATION_OVERRIDE"));
        ExecutionSessionRow retrySession = mapper.activeSessions(task.id()).getFirst();
        assertThat(((FakeOpenCodeClient) openCode).promptForSession(retrySession.externalSessionId()))
                .contains("user explicitly approved one fresh retry",
                        "Previous Attempt handoff (server-generated, bounded, and read-only)",
                        "LoopSpec next-attempt instructions", "人工继续时优先处理");
    }

    @Test
    void explicitLoopRetryRejectsUnreadableHandoffBeforeChangingState() throws Exception {
        ProjectRow project = projects.create("invalid-handoff", gitProject());
        LoopSpec stagnant = failingContentSpec(project.id(), 2, null, new LoopSpec.SessionPolicy(true, true));
        TaskRow task = drafts.confirm(drafts.create(stagnant).id(), "reject corrupt handoff");
        tasks.start(task.id());
        tasks.verify(task.id());
        tasks.verify(task.id());
        int attemptsBefore = tasks.attempts(task.id()).size();
        jdbc.update("UPDATE task_artifact SET content=? WHERE task_id=? AND kind='ATTEMPT_HANDOFF'", "{", task.id());

        assertThatThrownBy(() -> tasks.retryWaitingLoop(task.id()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("unreadable");

        assertThat(tasks.get(task.id()).state()).isEqualTo("WAITING_INPUT");
        assertThat(tasks.attempts(task.id())).hasSize(attemptsBefore);
        assertThat(tasks.artifacts(task.id())).noneMatch(artifact -> artifact.kind().equals("LOOP_STAGNATION_OVERRIDE"));
    }

    @Test
    void loopRetryProjectionUsesTheNewestWaitingReasonOnly() throws Exception {
        ProjectRow project = projects.create("waiting-reason", gitProject());
        LoopSpec stagnant = failingContentSpec(project.id(), 2, null, new LoopSpec.SessionPolicy(true, true));
        TaskRow task = drafts.confirm(drafts.create(stagnant).id(), "project current waiting reason");
        tasks.start(task.id());
        tasks.verify(task.id());
        tasks.verify(task.id());

        assertThat(tasks.loopRetryStatus(task.id())).isEqualTo(
                new TaskService.LoopRetryStatus("LOOP_STAGNATION_DETECTED", true));
        mapper.insertError(new ErrorEventRow("newer-budget-wait", task.id(), null, null, null,
                ErrorLayer.TASK.name(), "TASK_BUDGET_WAITING_INPUT", "等待用户调整预算", true,
                "{\"resolution\":\"WAITING_INPUT\"}", "9999-12-31T23:59:59Z"));

        assertThat(tasks.loopRetryStatus(task.id())).isEqualTo(
                new TaskService.LoopRetryStatus("TASK_BUDGET_WAITING_INPUT", false));
    }

    @Test
    void changedWorkspaceFingerprintPreventsFalseStagnationEvenWhenVerifierSummaryIsUnchanged() throws Exception {
        ProjectRow project = projects.create("stagnation-progress", gitProject());
        LoopSpec progressing = failingContentSpec(project.id(), 2, null, new LoopSpec.SessionPolicy(true, true));
        TaskRow task = drafts.confirm(drafts.create(progressing).id(), "recognize workspace progress");
        tasks.start(task.id());

        tasks.verify(task.id());
        Files.writeString(Path.of(task.worktreePath()).resolve("README.md"), "different work without the required marker");
        TaskRow retrying = tasks.verify(task.id());

        assertThat(retrying.state()).isEqualTo("RUNNING");
        assertThat(tasks.attempts(task.id())).hasSize(3);
        assertThat(tasks.errors(task.id())).noneMatch(error -> error.code().equals("LOOP_STAGNATION_DETECTED"));
        assertThat(tasks.artifacts(task.id())).filteredOn(artifact -> artifact.kind().equals("ATTEMPT_HANDOFF"))
                .hasSize(2).allSatisfy(artifact -> assertThat(artifact.content()).contains("\"consecutiveStagnationCount\":1"));
    }

    @Test
    void disabledFreshSessionPolicyWaitsForExplicitRetryInsteadOfSilentlyReusingTranscript() throws Exception {
        ProjectRow project = projects.create("fresh-session-policy", gitProject());
        LoopSpec manualFresh = failingContentSpec(project.id(), 3, null,
                new LoopSpec.SessionPolicy(true, false));
        TaskRow task = drafts.confirm(drafts.create(manualFresh).id(), "require explicit fresh retry");
        tasks.start(task.id());

        TaskRow waiting = tasks.verify(task.id());

        assertThat(waiting.state()).isEqualTo("WAITING_INPUT");
        assertThat(tasks.attempts(task.id())).hasSize(1);
        assertThat(tasks.errors(task.id())).anyMatch(error -> error.code().equals("LOOP_FRESH_SESSION_REQUIRED"));
    }

    @Test
    void workPackageAttemptPoolReservesTheUnstartedStagesFirstAttempt() throws Exception {
        ProjectRow project = projects.create("package-attempt-pool", gitProject());
        TaskRow task = drafts.confirm(drafts.create(packageAttemptSpec(project.id())).id(),
                "reserve later stage attempt");
        tasks.start(task.id());

        assertThat(tasks.verify(task.id()).state()).isEqualTo("RUNNING");
        assertThat(tasks.verify(task.id()).state()).isEqualTo("RUNNING");
        TaskRow failed = tasks.verify(task.id());

        assertThat(failed.state()).isEqualTo("FAILED");
        assertThat(tasks.attempts(task.id())).hasSize(3);
        assertThat(tasks.stages(task.id())).extracting(stage -> stage.state())
                .containsExactly("FAILED", "PENDING");
        assertThat(tasks.errors(task.id())).anySatisfy(error -> {
            assertThat(error.code()).isEqualTo("WORK_PACKAGE_ATTEMPT_LIMIT_EXHAUSTED");
            assertThat(error.message()).contains("independent attempt pool of 4", "reserving one first attempt");
        });
    }

    private LoopSpec spec(String projectId) {
        return new LoopSpec("v1", projectId, "Verify README", null, List.of(new LoopSpec.StageSpec("Check README", null, null, null,
                List.of(new LoopSpec.VerifierSpec("FILE_EXISTS", null, "README.md", null, null, null, null)))), null, null, null, null);
    }
    private LoopSpec judgeContractSpec(String projectId) {
        LoopSpec.VerifierSpec verifier = new LoopSpec.VerifierSpec(
                "FILE_CONTENT", null, "README.md", null, List.of(), List.of(), false, null,
                null, null, null, null, null, "EXACT", "fixture", null, null, null,
                List.of(), List.of("AC-1"), null, List.of());
        LoopSpec.AcceptanceCriterion criterion = new LoopSpec.AcceptanceCriterion(
                "AC-1", "README 行为满足已确认要求", "BOTH", "检查边界行为与需求一致性", null);
        return new LoopSpec("v2", projectId, "Verify README", null,
                List.of(new LoopSpec.StageSpec("Check README", List.of("README.md"), List.of(),
                        List.of("verified README"), List.of(verifier), List.of(criterion), null,
                        ImplementationKind.NON_JAVA)),
                null, null, null, null);
    }
    private LoopSpec multiStageJudgeContractSpec(String projectId) {
        LoopSpec.VerifierSpec first = new LoopSpec.VerifierSpec(
                "FILE_CONTENT", null, "README.md", null, List.of(), List.of(), false, null,
                null, null, null, null, null, "EXACT", "fixture", null, null, null,
                List.of(), List.of("AC-1"), null, List.of());
        LoopSpec.VerifierSpec second = new LoopSpec.VerifierSpec(
                "FILE_CONTENT", null, "README.md", null, List.of(), List.of(), false, null,
                null, null, null, null, null, "CONTAINS", "fix", null, null, null,
                List.of(), List.of("AC-2"), null, List.of());
        return new LoopSpec("v2", projectId, "Verify README through two stages", null, List.of(
                new LoopSpec.StageSpec("Stage one", List.of("README.md"), List.of(), List.of("first evidence"),
                        List.of(first), List.of(new LoopSpec.AcceptanceCriterion("AC-1", "first observable result",
                        "BOTH", "review the first-stage result", null)), null, ImplementationKind.NON_JAVA),
                new LoopSpec.StageSpec("Stage two", List.of("README.md"), List.of(), List.of("second evidence"),
                        List.of(second), List.of(new LoopSpec.AcceptanceCriterion("AC-2", "second observable result",
                        "BOTH", "review the second-stage result", null)), null, ImplementationKind.NON_JAVA)),
                null, null, null, null);
    }
    private LoopSpec failingContentSpec(String projectId, int stagnationLimit, String retryTemplate,
                                        LoopSpec.SessionPolicy sessionPolicy) {
        LoopSpec.VerifierSpec verifier = new LoopSpec.VerifierSpec(
                "FILE_CONTENT", null, "README.md", null, null, null, null, null,
                null, null, null, null, null, "CONTAINS", "content-that-is-not-present",
                null, null, null, null);
        return new LoopSpec("v1", projectId, "Make README contain the required marker", null,
                List.of(new LoopSpec.StageSpec("Update and verify README", List.of("README.md"), null,
                        List.of("README contains the marker"), List.of(verifier))),
                new LoopSpec.Limits(4, 6, 3, stagnationLimit, 7200L, 1800L, 600L),
                null, sessionPolicy, retryTemplate);
    }
    private LoopSpec packageAttemptSpec(String projectId) {
        LoopSpec.VerifierSpec failing = new LoopSpec.VerifierSpec(
                "FILE_CONTENT", null, "README.md", null, null, null, null, null,
                null, null, null, null, null, "CONTAINS", "package-marker-not-present",
                null, null, null, null);
        return new LoopSpec("v1", projectId, "Respect independent work-package attempt pools", null,
                List.of(
                        new LoopSpec.StageSpec("Retry-prone first stage", List.of("README.md"), List.of(),
                                List.of("first result"), List.of(failing), List.of(), null, null, "WP-1"),
                        new LoopSpec.StageSpec("Reserved later stage", List.of("README.md"), List.of(),
                                List.of("second result"), List.of(failing), List.of(), null, null, "WP-1")),
                new LoopSpec.Limits(5, 8, 3, 20, 7200L, 1800L, 600L), null,
                new LoopSpec.SessionPolicy(true, true), null);
    }
    private LoopSpec stageScopedGitDiffSpec(String projectId) {
        LoopSpec.VerifierSpec firstDiff = new LoopSpec.VerifierSpec(
                "GIT_DIFF", null, null, true, List.of("first.txt"), List.of(), true);
        LoopSpec.VerifierSpec secondDiff = new LoopSpec.VerifierSpec(
                "GIT_DIFF", null, null, true, List.of("second.txt"), List.of(), true);
        LoopSpec.VerifierSpec firstContent = new LoopSpec.VerifierSpec(
                "FILE_EXISTS", null, "first.txt", null, List.of(), List.of(), false);
        LoopSpec.VerifierSpec secondContent = new LoopSpec.VerifierSpec(
                "FILE_EXISTS", null, "second.txt", null, List.of(), List.of(), false);
        return new LoopSpec("v1", projectId, "Verify package-local workspace changes", null, List.of(
                new LoopSpec.StageSpec("Package one", List.of("first.txt"), List.of(), List.of("first.txt"),
                        List.of(firstDiff, firstContent), List.of(), null, null, "WP-1"),
                new LoopSpec.StageSpec("Package two", List.of("second.txt"), List.of(), List.of("second.txt"),
                        List.of(secondDiff, secondContent), List.of(), null, null, "WP-2")),
                null, null, null, null);
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
    private String runOutput(Path root, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) throw new AssertionError(output);
        return output;
    }
    private List<String> localBranches(Path root) throws Exception {
        Process process = new ProcessBuilder("git", "for-each-ref", "--format=%(refname:short)", "refs/heads")
                .directory(root.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) throw new AssertionError(output);
        return output.lines().toList();
    }
}
