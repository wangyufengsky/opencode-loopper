package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.AttemptState;
import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.domain.LifecycleMachineType;
import io.opencode.loopper.domain.StageState;
import io.opencode.loopper.domain.TaskState;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.AttemptRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.persistence.TemplateTaskBatchRow;
import io.opencode.loopper.persistence.TemplateTaskMapper;
import io.opencode.loopper.runtime.FakeOpenCodeClient;
import io.opencode.loopper.runtime.GitEvidenceProcess;
import io.opencode.loopper.runtime.OpenCodeClient;
import io.opencode.loopper.template.TemplateAnalysis;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = LoopperApplication.class, properties = {"loopper.opencode.mode=fake", "loopper.monitor-delay=1h"})
class TemplateBatchExecutionIntegrationTest {
    @Autowired Flyway flyway;
    @Autowired TemplateTaskService tasks;
    @Autowired TemplateTaskMapper templates;
    @Autowired TemplateBatchStore batches;
    @Autowired TemplateBatchExecution execution;
    @Autowired LoopperMapper mapper;
    @Autowired ProjectService projects;
    @Autowired GitEvidenceProcess git;
    @Autowired LoopperProperties properties;
    @Autowired TaskStateStore states;
    @Autowired LifecycleTransitionService lifecycle;
    @Autowired TaskExecutionCycleService cycles;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean OpenCodeClient client;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired TemplateCandidateSubmissionService submissions;
    @Autowired TemplateTaskCoordinator coordinator;
    @Autowired io.opencode.loopper.persistence.TemplateContinuationMapper continuations;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired io.opencode.loopper.runtime.InternalMcpRuntimeAccess access;
    @Autowired io.opencode.loopper.persistence.TemplateCandidateSubmissionMapper receipts;
    @TempDir Path temporary;
    private TaskRow task;
    private TemplateTaskBatchRow batch;
    private TemplateTaskContractFactory.Frozen contract;
    private FakeOpenCodeClient fake;

    @BeforeEach void prepare() throws Exception {
        flyway.clean(); flyway.migrate();
        fake = (FakeOpenCodeClient) client; fake.reset();
        properties.getOpenCode().setModel("fake/test-model");
        Path source = Files.createDirectory(temporary.resolve("project"));
        git.read(source, "init", "-b", "main");
        git.read(source, "-c", "user.name=Test", "-c", "user.email=test@example.test", "commit", "--allow-empty", "-m", "root");
        String project = projects.create("project", source.toString(), "test").id();
        task = tasks.create(new TemplateTaskService.Request(UUID.randomUUID().toString(), "CODE_REVIEW", io.opencode.loopper.template.TemplateTaskDefinition.VERSION, project,
                "local:refs/heads/main", "2026-09-11", "2026-09-11", StoryBindingConfiguration.disabled()), false);
        LegacyTemplateFixture.freezeV4(jdbc, json, task.id());
        contract = json.readValue(templates.findRun(task.id()).orElseThrow().contractJson(), TemplateTaskContractFactory.Frozen.class);
        enterRunning(Files.createDirectory(temporary.resolve("workspace")));
        var stage = mapper.listStages(task.id()).get(1);
        states.updateStage(states.stageState(stage, StageState.RUNNING));
        var cycle = cycles.ensureInitial(current(), "{}");
        var attempt = new AttemptRow(UUID.randomUUID().toString(), task.id(), stage.id(), cycle.id(), 1,
                AttemptState.RUNNING.name(), null, null, Instant.now().toString(), null, 0);
        states.createAttempt(attempt);
        var unit = new TemplateAnalysis.Unit("unit", "sha", "evidence", "file", "ANALYZE", "@@ -0,0 +1 @@\n+content\n",
                List.of(new TemplateAnalysis.SourceLine(TemplateAnalysis.Side.AFTER, 1)));
        String input = json.writeValueAsString(new TemplateBatchExecution.Input(List.of(unit), null, List.of(), null));
        batch = batches.create(attempt, 0, "REVIEW", input, TemplateGitEvidenceCollector.hash(input));
        fake.setJudgeOutput("{\"reviews\":[{\"unitId\":\"unit\",\"summary\":\"新增内容\",\"findings\":[],\"limitations\":[\"未执行测试\"]}]}");
    }

    @Autowired io.opencode.loopper.persistence.TemplateSessionReadMapper sessionLabels;
    @Test void sessionLabelsUsePersistedPurposeAndOrdinalEvenBeforeRemoteIsAvailable() {
        batches.plan(task.id(),10,4);
        var attempt=mapper.findAttempt(batch.attemptId()).orElseThrow();
        var person=batches.create(attempt,1,"CONTRIBUTOR",batch.inputJson(),batch.inputSha256());
        batch=execution.advance(batch,contract);
        person=batches.prepareSession(person,json.readValue(batch.creationPlanJson(),OpenCodeClient.SessionCreationPlan.class),
                new TemplateBatchStore.FrozenPrompt("title fixture","msg_title_fixture",null,null));
        assertThat(sessionLabels.sessions(task.id())).anySatisfy(label->{
            assertThat(label.sessionId()).isEqualTo(batch.sessionId());assertThat(label.overallOrdinal()).isEqualTo(1);
            assertThat(label.overallTotal()).isEqualTo(14);
        }).anySatisfy(label->{assertThat(label.purpose()).isEqualTo("CONTRIBUTOR");assertThat(label.ordinal()).isEqualTo(2);
            assertThat(label.overallOrdinal()).isEqualTo(12);assertThat(label.total()).isEqualTo(4);});
        assertThat(sessionLabels.sessions("another-task")).isEmpty();
    }

    @Test void recoversCreateAndPromptAcknowledgementGapsWithoutDuplicateProviderCalls() {
        batch = execution.advance(batch, contract);
        assertThat(batch.state()).isEqualTo("CREATING");
        var plan = json.readValue(batch.creationPlanJson(), OpenCodeClient.SessionCreationPlan.class);
        var created = client.createSession(plan); // The remote operation happened before a simulated crash.
        batch = execution.advance(batch, contract);
        assertThat(batch.state()).isEqualTo("PROMPT_READY");
        assertThat(fake.createReadOnlySessionCalls()).isEqualTo(1);
        assertThat(mapper.findSession(batch.sessionId()).orElseThrow().externalSessionId()).isEqualTo(created.remoteId());
        batch = execution.advance(batch, contract);
        var frozen = json.readValue(batch.promptJson(), TemplateBatchStore.FrozenPrompt.class);
        client.promptAsync(created.session(), frozen.request()); // Lost local acknowledgement checkpoint.
        batch = execution.advance(batch, contract);
        assertThat(batch.state()).isEqualTo("RUNNING");
        assertThat(fake.promptCalls()).isEqualTo(1);
        batch = execution.advance(batch, contract);
        assertThat(batch.state()).isEqualTo("VALIDATED");
        assertThat(batch.outputJson()).contains("新增内容");
        assertThat(mapper.findSession(batch.sessionId()).orElseThrow().state()).isEqualTo("COMPLETED");
    }

    @Test void uncertainAbortKeepsBatchAndSessionOpenUntilPositiveProof() {
        fake.holdProfileOpen(OpenCodeClient.SessionProfile.TEMPLATE_ANALYSIS_NO_TOOLS, true);
        for (int i = 0; i < 4; i++) batch = execution.advance(batch, contract);
        assertThat(batch.state()).isEqualTo("RUNNING");
        fake.failNextAborts(1);
        assertThat(execution.stop(batch)).isFalse();
        assertThat(batches.require(batch.id()).state()).isEqualTo("STOPPING");
        assertThat(mapper.findSession(batch.sessionId()).orElseThrow().state()).isEqualTo("RUNNING");
        assertThat(execution.stop(batches.require(batch.id()))).isTrue();
        assertThat(batches.require(batch.id()).state()).isEqualTo("STOPPED");
        assertThat(mapper.findSession(batch.sessionId()).orElseThrow().state()).isEqualTo("ABORTED");
    }

    @Test void missingCoverageFailsCandidateEvenWhenProviderCompletesAndLateResultsCannotAdvanceCancelledTask() {
        fake.setJudgeOutput("{\"reviews\":[]}");
        for (int i = 0; i < 5; i++) batch = execution.advance(batch, contract);
        assertThat(batch.state()).isEqualTo("FAILED");
        assertThat(batch.errorCode()).isEqualTo("TEMPLATE_CANDIDATE_INVALID");
        assertThat(current().state()).isEqualTo("RUNNING");
        states.updateTask(states.taskState(current(), TaskState.STOPPING), LifecycleEvent.CANCEL, Map.of());
        states.updateTask(states.taskState(current(), TaskState.CANCELLED), LifecycleEvent.COMPLETE, Map.of());
        assertThatThrownBy(() -> batches.requireRunning(task.id(), batch.attemptId())).isInstanceOf(ConflictException.class);
        assertThat(current().state()).isEqualTo("CANCELLED");
    }

    @Test void mcpCorrectsInSameSessionReplaysReceiptsAndWaitsForRemoteCompletion() {
        enableMcp();
        for (int i = 0; i < 4; i++) batch = execution.advance(batch, contract);
        assertThat(batch.promptJson()).contains("submit_template_analysis", batch.id(), "submissionRevision")
                .doesNotContain(TemplateAnalysisPromptFactory.START);
        String rejected = submissions.submit(batch.id(), "invalid", 0, "{\"reviews\":[]}");
        assertThat(rejected).contains("REJECTED", "FIX_AND_RESUBMIT", "覆盖", "\"submissionRevision\":1");
        assertThat(batches.require(batch.id()).state()).isEqualTo("RUNNING");
        assertThat(submissions.submit(batch.id(), "invalid", 0, "{\"reviews\":[]}")).isEqualTo(rejected);
        assertThat(receipts.revision(batch.id())).isEqualTo(1);
        String accepted = submissions.submit(batch.id(), "corrected", 1, valid());
        assertThat(accepted).contains("ACCEPTED", "\"submissionRevision\":2");
        assertThat(submissions.submit(batch.id(), "corrected", 1, valid())).isEqualTo(accepted);
        assertThat(execution.advance(batch, contract).state()).isEqualTo("RUNNING");
        assertThat(batches.require(batch.id()).outputJson()).isNull();
        fake.setJudgeOutput("This final text is not the report");
        fake.setSessionState(mapper.findSession(batch.sessionId()).orElseThrow().externalSessionId(), "COMPLETED");
        batch = execution.advance(batch, contract);
        assertThat(batch.state()).isEqualTo("VALIDATED");
        assertThat(batch.outputJson()).contains("新增内容");
        assertThat(fake.promptCalls()).isEqualTo(1);
        assertThat(fake.createReadOnlySessionCalls()).isEqualTo(1);
    }

    @Test void mcpRejectsStaleRevisionsAlteredReplaysAndLateCancelledSubmissions() {
        enableMcp();
        for (int i = 0; i < 4; i++) batch = execution.advance(batch, contract);
        submissions.submit(batch.id(), "bad", 0, "{\"reviews\":[]}");
        assertThat(submissions.submit(batch.id(), "stale", 0, valid())).contains("REFRESH_REVISION_AND_RESUBMIT");
        assertThat(receipts.revision(batch.id())).isEqualTo(1);
        assertThatThrownBy(() -> submissions.submit(batch.id(), "bad", 1, valid()))
                .isInstanceOf(ConflictException.class).hasMessageContaining("不同候选");
        states.updateTask(states.taskState(current(), TaskState.STOPPING), LifecycleEvent.CANCEL, Map.of());
        assertThatThrownBy(() -> submissions.submit(batch.id(), "late", 1, valid())).isInstanceOf(ConflictException.class);
        assertThat(receipts.accepted(batch.id())).isEmpty();
    }

    @Test void mcpRejectsWrongGenerationAndForeignEvidence() {
        enableMcp();
        for (int i = 0; i < 4; i++) batch = execution.advance(batch, contract);
        assertThat(submissions.submit(batch.id(), "foreign", 0, valid().replace("\"unit\"", "\"foreign\""))).contains("REJECTED", "未知证据");
        access.activate(new io.opencode.loopper.runtime.InternalMcpCredentialProvider(() -> 18083).issue());
        assertThatThrownBy(() -> submissions.submit(batch.id(), "wrong-generation", 1, valid()))
                .isInstanceOf(ConflictException.class).hasMessageContaining("运行环境");
        assertThat(receipts.accepted(batch.id())).isEmpty();
    }

    @Test void mcpCompletionWithoutSubmissionNeverFallsBackToFinalText() {
        enableMcp();
        for (int i = 0; i < 4; i++) batch = execution.advance(batch, contract);
        fake.setJudgeOutput(valid());
        fake.setSessionState(mapper.findSession(batch.sessionId()).orElseThrow().externalSessionId(), "COMPLETED");
        assertThatThrownBy(() -> execution.advance(batch, contract)).isInstanceOf(io.opencode.loopper.domain.SessionFailure.class)
                .hasMessageContaining("没有通过 MCP");
        assertThat(batches.require(batch.id()).state()).isEqualTo("RUNNING");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"5", "6", "7"})
    void mcpUnknownStopKeepsAcceptedCandidateFromCompletingOrReleasingBatch(String version) {
        enableMcp(version);
        for (int i = 0; i < 4; i++) batch = execution.advance(batch, contract);
        submissions.submit(batch.id(), "accepted", 0, valid());
        fake.failNextAborts(1);
        assertThat(execution.stop(batch)).isFalse();
        assertThat(batches.require(batch.id()).state()).isEqualTo("STOPPING");
        assertThat(batches.require(batch.id()).outputJson()).isNull();
        assertThat(receipts.accepted(batch.id())).isPresent();
        assertThat(execution.stop(batch)).isTrue();
    }

    @Test void concurrentCandidatesAcceptOnlyOnceAndExposeTheWinningRevision() throws Exception {
        enableMcp();
        for (int i = 0; i < 4; i++) batch = execution.advance(batch, contract);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            var first = executor.submit(() -> { start.await(); return concurrentSubmit("one"); });
            var second = executor.submit(() -> { start.await(); return concurrentSubmit("two"); });
            start.countDown();
            var one = first.get(10, java.util.concurrent.TimeUnit.SECONDS);
            var two = second.get(10, java.util.concurrent.TimeUnit.SECONDS);
            // Shared-memory SQLite may return SQLITE_LOCKED instead of busy-waiting. The MCP adapter
            // explicitly asks for an identical replay; emulate that caller behavior after both transactions end.
            if (one.equals("RETRY_SAME_REQUEST")) one = submissions.submit(batch.id(), "one", 0, valid());
            if (two.equals("RETRY_SAME_REQUEST")) two = submissions.submit(batch.id(), "two", 0, valid());
            var results = List.of(one, two);
            assertThat(results.stream().filter(value -> value.contains("ACCEPTED")).count()).isEqualTo(1);
            assertThat(results.stream().filter(value -> value.contains("REFRESH_REVISION_AND_RESUBMIT")).count()).isEqualTo(1);
            assertThat(receipts.revision(batch.id())).isEqualTo(1);
        }
    }

    @Test void taskCleanupExplicitlyDeletesReceiptsAndRollsBackWithItsOwnerTransaction() {
        enableMcp();
        for (int i = 0; i < 4; i++) batch = execution.advance(batch, contract);
        submissions.submit(batch.id(), "accepted", 0, valid());
        var transaction = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> { coordinator.deleteBeforeAttempts(task.id()); status.setRollbackOnly(); });
        assertThat(receipts.accepted(batch.id())).isPresent();
        assertThat(templates.findBatch(batch.id())).isPresent();
        transaction.executeWithoutResult(status -> coordinator.deleteBeforeAttempts(task.id()));
        assertThat(receipts.revision(batch.id())).isZero();
        assertThat(templates.findBatch(batch.id())).isEmpty();
    }

    @Test void lengthContinuationUsesSameSessionAndRecoversLostAcknowledgementExactlyOnce() {
        startContinuable();
        String sessionId = batch.sessionId();
        String initial = batch.promptJson();
        batch = execution.advance(batch, contract);
        assertThat(batch.state()).isEqualTo("DISPATCHING");
        var intent = continuations.latest(batch.id()).orElseThrow();
        assertThat(intent.priorPromptJson()).isEqualTo(initial);
        assertThat(intent.stagnantLengths()).isEqualTo(1);
        var request = json.readValue(batch.promptJson(), TemplateBatchStore.FrozenPrompt.class).request();
        client.promptAsync(remote(), request); // Remote received it, local acknowledgement was lost.
        int calls = fake.promptCalls();
        batch = execution.advance(batch, contract);
        assertThat(batch.state()).isEqualTo("RUNNING");
        assertThat(fake.promptCalls()).isEqualTo(calls);
        assertThat(batch.sessionId()).isEqualTo(sessionId);
        assertThat(fake.createReadOnlySessionCalls()).isEqualTo(1);
        submissions.submit(batch.id(), "accepted", 0, valid());
        batch = execution.advance(batch, contract);
        assertThat(batch.state()).isEqualTo("VALIDATED");
        assertThat(continuations.latest(batch.id()).orElseThrow().ordinal()).isEqualTo(1);
    }

    @Test void repeatedLengthWithoutDistinctCandidateStopsAfterTwoContinuations() {
        startContinuable();
        for (int i = 0; i < 2; i++) {
            batch = execution.advance(batch, contract);
            batch = execution.advance(batch, contract);
        }
        int calls = fake.promptCalls();
        assertThatThrownBy(() -> execution.advance(batch, contract)).hasMessageContaining("连续三次");
        assertThat(fake.promptCalls()).isEqualTo(calls);
        assertThat(continuations.latest(batch.id()).orElseThrow().ordinal()).isEqualTo(2);
    }

    @Test void distinctRejectedCandidateResetsStagnationButIdenticalContentDoesNot() {
        startContinuable();
        batch = execution.advance(batch, contract); batch = execution.advance(batch, contract);
        submissions.submit(batch.id(), "one", 0, "{}");
        batch = execution.advance(batch, contract);
        assertThat(continuations.latest(batch.id()).orElseThrow().stagnantLengths()).isZero();
        assertThat(batch.promptJson()).contains("expectedSubmissionRevision=1");
        batch = execution.advance(batch, contract);
        submissions.submit(batch.id(), "two", 1, "{}");
        batch = execution.advance(batch, contract);
        assertThat(continuations.latest(batch.id()).orElseThrow().stagnantLengths()).isEqualTo(1);
    }

    @Test void acceptedBeforeContinuationDispatchAvoidsAnotherModelCall() {
        startContinuable();
        batch = execution.advance(batch, contract);
        submissions.submit(batch.id(), "accepted", 0, valid());
        int calls = fake.promptCalls();
        batch = execution.advance(batch, contract);
        assertThat(batch.state()).isEqualTo("VALIDATED");
        assertThat(fake.promptCalls()).isEqualTo(calls);
    }

    @Test void continuationWaitsForStopProofAndNeverResendsUnknownOrAlteredDelivery() {
        startContinuable(); batch = execution.advance(batch, contract);
        int calls = fake.promptCalls();
        fake.setSessionState(remote().id(), "RUNNING");
        assertThat(execution.advance(batch, contract).state()).isEqualTo("DISPATCHING");
        assertThat(fake.promptCalls()).isEqualTo(calls);
        fake.setSessionState(remote().id(), "COMPLETED");
        org.mockito.Mockito.doReturn(new OpenCodeClient.MessageLookup(false, false, null)).when(client)
                .findPromptMessage(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(OpenCodeClient.PromptRequest.class), org.mockito.ArgumentMatchers.anyString());
        assertThatThrownBy(() -> execution.advance(batch, contract)).hasMessageContaining("无法核对");
        org.mockito.Mockito.doReturn(new OpenCodeClient.MessageLookup(true, true, "a".repeat(64))).when(client)
                .findPromptMessage(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(OpenCodeClient.PromptRequest.class), org.mockito.ArgumentMatchers.anyString());
        assertThatThrownBy(() -> execution.advance(batch, contract)).hasMessageContaining("不一致");
        assertThat(fake.promptCalls()).isEqualTo(calls);
    }

    @Test void cancellationAndStalePreparationCannotDispatchContinuation() {
        startContinuable();
        var old = batch;
        batch = execution.advance(batch, contract);
        assertThatThrownBy(() -> batches.prepareContinuation(old)).isInstanceOf(ConflictException.class);
        int calls = fake.promptCalls();
        states.updateTask(states.taskState(current(), TaskState.STOPPING), LifecycleEvent.CANCEL, Map.of());
        assertThatThrownBy(() -> execution.advance(batch, contract)).isInstanceOf(ConflictException.class);
        assertThat(fake.promptCalls()).isEqualTo(calls);
    }

    @Test void frozenV5LengthRemainsAnErrorAndCreatesNoContinuation() {
        enableMcp(); for (int i = 0; i < 4; i++) batch = execution.advance(batch, contract);
        lengthResult();
        assertThatThrownBy(() -> execution.advance(batch, contract)).hasMessageContaining("长度耗尽");
        assertThat(continuations.latest(batch.id())).isEmpty();
    }

    @Test void continuationCleanupAndBatchPointerRollBackTogether() {
        startContinuable();
        var transaction = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> { batches.prepareContinuation(batch); status.setRollbackOnly(); });
        assertThat(continuations.latest(batch.id())).isEmpty();
        assertThat(batches.require(batch.id()).state()).isEqualTo("RUNNING");
        batch = execution.advance(batch, contract);
        transaction.executeWithoutResult(status -> { coordinator.deleteBeforeAttempts(task.id()); status.setRollbackOnly(); });
        assertThat(continuations.latest(batch.id())).isPresent();
        transaction.executeWithoutResult(status -> coordinator.deleteBeforeAttempts(task.id()));
        assertThat(continuations.latest(batch.id())).isEmpty();
    }

    @Test void expiredTaskDeadlineBlocksPersistedContinuationBeforeDispatch() {
        startContinuable(); batch = execution.advance(batch, contract);
        int calls = fake.promptCalls();
        jdbc.update("UPDATE task_execution_cycle SET started_at='2000-01-01T00:00:00Z' WHERE task_id=?", task.id());
        coordinator.advance(task.id());
        assertThat(current().state()).isEqualTo("WAITING_INPUT");
        assertThat(fake.promptCalls()).isEqualTo(calls);
    }

    private void startContinuable() {
        enableMcp();
        var tree = (tools.jackson.databind.node.ObjectNode) json.valueToTree(contract);
        ((tools.jackson.databind.node.ObjectNode) tree.get("definition")).put("version", "6");
        contract = json.treeToValue(tree, TemplateTaskContractFactory.Frozen.class);
        for (int i = 0; i < 4; i++) batch = execution.advance(batch, contract);
        lengthResult();
    }
    private void lengthResult() {
        fake.setSessionState(remote().id(), "COMPLETED");
        org.mockito.Mockito.doReturn(new OpenCodeClient.SessionResult("", Map.of(), "OPENCODE_OUTPUT_LENGTH_EXHAUSTED", "length", 0))
                .when(client).sessionResult(org.mockito.ArgumentMatchers.any());
    }
    private OpenCodeClient.OpenCodeSession remote() {
        var plan = json.readValue(batch.creationPlanJson(), OpenCodeClient.SessionCreationPlan.class);
        return new OpenCodeClient.OpenCodeSession(mapper.findSession(batch.sessionId()).orElseThrow().externalSessionId(),
                plan.canonicalDirectory(), plan.runtimeGenerationId(), plan.internalMcpServer());
    }

    private String concurrentSubmit(String key) {
        try { return submissions.submit(batch.id(), key, 0, valid()); }
        catch (org.springframework.dao.DataAccessException busy) { return "RETRY_SAME_REQUEST"; }
    }

    private void enableMcp() { enableMcp("5"); }
    private void enableMcp(String version) {
        var credentials = new io.opencode.loopper.runtime.InternalMcpCredentialProvider(() -> 18083).issue();
        access.activate(credentials); access.connected(credentials.generation());
        fake.setManagedRuntime(credentials.generation(), credentials.serverName());
        fake.holdProfileOpen(OpenCodeClient.SessionProfile.TEMPLATE_ANALYSIS_CANDIDATE_NO_TOOLS, true);
        var tree = (tools.jackson.databind.node.ObjectNode) json.valueToTree(contract);
        ((tools.jackson.databind.node.ObjectNode) tree.get("definition")).put("version", version);
        contract = json.treeToValue(tree, TemplateTaskContractFactory.Frozen.class);
    }

    private String valid() {
        return "{\"reviews\":[{\"unitId\":\"unit\",\"summary\":\"新增内容\",\"findings\":[],\"limitations\":[\"未执行测试\"]}]}";
    }

    private void enterRunning(Path workspace) {
        states.updateTask(states.taskState(current(), TaskState.QUEUED), LifecycleEvent.REQUEST_START, Map.of());
        states.updateTask(states.taskState(current(), TaskState.PREPARING));
        var old = current();
        var ready = new TaskRow(old.id(), old.projectId(), old.loopDraftId(), old.title(), "READY", workspace.toString(), "TEMPLATE_REPORT",
                "refs/heads/main", "head", old.createdAt(), Instant.now().toString(), old.version(), old.taskProfileId(), old.rolePackId(),
                old.rolePackVersion(), old.executionMode(), old.workspacePolicy());
        lifecycle.transition(states.subject(LifecycleMachineType.TASK, task.id(), task.id()), "PREPARING", "READY", null, Map.of(),
                () -> mapper.prepareTask(ready), () -> new IllegalStateException("fixture conflict"));
        states.updateTask(states.taskState(current(), TaskState.RUNNING));
    }
    private TaskRow current() { return mapper.findTask(task.id()).orElseThrow(); }
}
