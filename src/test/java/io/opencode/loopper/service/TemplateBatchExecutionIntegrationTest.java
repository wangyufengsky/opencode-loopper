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
    @Autowired OpenCodeClient client;
    @Autowired ObjectMapper json;
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
