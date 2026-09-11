package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.runtime.*;
import io.opencode.loopper.template.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = LoopperApplication.class, properties = {"loopper.opencode.mode=fake", "loopper.monitor-delay=1h"})
class TemplateTaskExecutionIntegrationTest {
    @Autowired Flyway flyway;
    @Autowired TemplateTaskService admission;
    @Autowired TemplateTaskStateService states;
    @Autowired TemplateTaskCoordinator driver;
    @Autowired TemplateRunEvidenceService evidence;
    @Autowired TemplateTaskMapper templates;
    @Autowired LoopperMapper mapper;
    @Autowired TaskService tasks;
    @Autowired ProjectService projects;
    @Autowired GitEvidenceProcess git;
    @Autowired LoopperProperties properties;
    @Autowired OpenCodeClient client;
    @Autowired ObjectMapper json;
    @Autowired TemplateGitCaptureGuard gitGuard;
    @Autowired SessionLifecycleService sessionLifecycle;
    @Autowired StoryBindingService storyBindings;
    @Autowired javax.sql.DataSource dataSource;
    @TempDir Path temporary;
    private String projectId;
    private FakeOpenCodeClient fake;
    private Path source;

    @BeforeEach void prepare() throws Exception {
        flyway.clean(); flyway.migrate();
        fake = (FakeOpenCodeClient) client; fake.reset();
        properties.getOpenCode().setModel("fake/test-model");
        source = Files.createDirectory(temporary.resolve("project"));
        git.read(source, "init", "-b", "main");
        Files.writeString(source.resolve("readme.txt"), "reviewed content\n");
        git.read(source, "add", ".");
        git.read(source, "-c", "user.name=Test", "-c", "user.email=test@example.test", "commit", "-m", "add content");
        projectId = projects.create("project", source.toString(), "test").id();
    }

    @Test void codeReviewCompletesOnlyAfterTwoJudgesAndPreservesSource() throws Exception {
        String head = git.read(source, "rev-parse", "HEAD");
        Files.writeString(source.resolve("local-work.txt"), "uncommitted\n");
        TaskRow task = create("CODE_REVIEW");
        storyBindings.attachTask(task.id(), new StoryBindingConfiguration(true, "001", "0002"));
        assertThat(mapper.findTaskQueue(task.id())).isEmpty();
        states.start(task.id(), evidence.contract(task.id()));
        run(task.id(), false);
        assertThat(states.task(task.id()).state()).isEqualTo("COMPLETED");
        Path workspace = Path.of(states.task(task.id()).worktreePath());
        assertThat(workspace).isEqualTo(workspace.toRealPath());
        var acceptedJudges = mapper.listJudgeRuns(task.id()).stream().filter(row -> row.state().equals("COMPLETED")).toList();
        assertThat(acceptedJudges).hasSize(2).allMatch(row -> "PASS".equals(row.verdict()));
        assertThat(acceptedJudges).extracting(JudgeRunRow::reviewBatchId).containsOnly(acceptedJudges.getFirst().reviewBatchId());
        assertThat(mapper.listStages(task.id())).hasSize(2).allMatch(row -> row.state().equals("SUCCEEDED"));
        assertThat(mapper.findActiveWorkspaceLeaseByHolder(task.id())).isEmpty();
        assertThat(git.read(source, "rev-parse", "HEAD")).isEqualTo(head);
        assertThat(Files.readString(source.resolve("local-work.txt"))).isEqualTo("uncommitted\n");
        assertThat(mapper.listTaskArtifacts(task.id())).anyMatch(row -> row.kind().equals("TEMPLATE_REPORT")
                && row.content().contains("| 提交覆盖 | 1 / 1 |") && row.content().contains("CODE_REVIEW_V2"));
        String sessionId = mapper.listSessions(task.id()).getFirst().id();
        var owner = mapper.findStoryAccountingOwner(mapper.findSession(sessionId).orElseThrow().externalSessionId()).orElseThrow();
        assertThat(owner.role()).isEqualTo("IMPLEMENTATION");
        assertThat(owner.systemCode()).isEqualTo("001");
        assertThat(owner.storyCode()).isEqualTo("0002");
        assertThatThrownBy(() -> sessionLifecycle.summarize(task.id(), sessionId, false)).isInstanceOf(ConflictException.class).hasMessageContaining("冻结证据");
        assertThatThrownBy(() -> sessionLifecycle.fork(task.id(), sessionId, "message")).isInstanceOf(ConflictException.class).hasMessageContaining("冻结证据");
        assertThatThrownBy(() -> sessionLifecycle.revert(task.id(), sessionId, "message", "part")).isInstanceOf(ConflictException.class).hasMessageContaining("冻结证据");
    }

    @Test void contributionReportRanksAndRepairsMalformedCandidateAtMostTwice() {
        TaskRow task = create("CONTRIBUTION_REPORT");
        states.start(task.id(), evidence.contract(task.id()));
        run(task.id(), true);
        assertThat(states.task(task.id()).state()).isEqualTo("COMPLETED");
        assertThat(templates.findRun(task.id()).orElseThrow().repairRound()).isEqualTo(1);
        var finalAttempt = mapper.latestAttempt(mapper.listStages(task.id()).getLast().id()).orElseThrow();
        assertThat(templates.batches(task.id(), finalAttempt.id())).allSatisfy(batch ->
                assertThat(batch.promptJson()).contains("报告必须逐项覆盖本批全部证据"));
        var reports = mapper.listTaskArtifacts(task.id()).stream().filter(row -> row.kind().equals("TEMPLATE_REPORT")).toList();
        assertThat(reports).hasSize(2);
        assertThat(reports).anyMatch(row -> row.name().equals("contribution-report.md") && row.content().contains("贡献排名") && row.content().contains("CONTRIBUTION_SCORE_V1"));
        exportBrowserFixture(task.id());
    }

    private void exportBrowserFixture(String taskId) {
        String destination = System.getProperty("template.browser.fixtureDir");
        if (destination == null) return;
        try {
            Path directory = Files.createDirectories(Path.of(destination));
            Path database = directory.resolve("runtime.db").toAbsolutePath();
            assertThat(database).doesNotExist();
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                statement.execute("VACUUM INTO '" + database.toString().replace("'", "''") + "'");
            }
            Files.writeString(directory.resolve("fixture.json"), json.writeValueAsString(java.util.Map.of(
                    "taskId", taskId, "provider", "fake", "templateVersion", TemplateTaskDefinition.VERSION)));
        } catch (Exception failure) { throw new AssertionError("Unable to export verified browser fixture", failure); }
    }

    @Test void exhaustedContentRepairsRemainWaitingAndManualCancelClosesLease() {
        TaskRow task = create("CODE_REVIEW");
        states.start(task.id(), evidence.contract(task.id()));
        for (int i = 0; i < 40; i++) {
            fake.setJudgeOutput("{\"reviews\":[]}");
            driver.advance(task.id());
        }
        assertThat(states.task(task.id()).state()).isEqualTo("WAITING_INPUT");
        assertThat(templates.findRun(task.id()).orElseThrow().repairRound()).isEqualTo(2);
        assertThat(mapper.listAttempts(task.id())).hasSize(4);
        assertThat(tasks.cancel(task.id()).state()).isEqualTo("CANCELLED");
        assertThat(mapper.findActiveWorkspaceLeaseByHolder(task.id())).isEmpty();
        tasks.archive(task.id()); tasks.deleteArchived(task.id());
        assertThat(templates.findRun(task.id())).isEmpty();
        assertThat(mapper.findTask(task.id())).isEmpty();
    }

    private TaskRow create(String definition) {
        String today = LocalDate.now(TemplateDateRange.ZONE).toString();
        return admission.create(new TemplateTaskService.Request(UUID.randomUUID().toString(), definition, io.opencode.loopper.template.TemplateTaskDefinition.VERSION, projectId,
                "local:refs/heads/main", today, today, StoryBindingConfiguration.disabled()), true);
    }

    @Test void interruptedGitCaptureCannotRestartOrReleaseLeaseWithoutStopProof() {
        TaskRow task = create("CODE_REVIEW");
        states.start(task.id(), evidence.contract(task.id())); states.prepare(task.id());
        assertThatThrownBy(() -> gitGuard.capture(task.id(), () -> {
            throw new io.opencode.loopper.domain.TaskFailure("TEMPLATE_GIT_STOP_UNCONFIRMED", "fixture unknown stop");
        })).isInstanceOf(io.opencode.loopper.domain.TaskFailure.class);
        driver.executeCheckpoint(task.id());
        assertThat(states.task(task.id()).state()).isEqualTo("WAITING_INPUT");
        assertThat(mapper.listSessions(task.id())).isEmpty();
        assertThat(gitGuard.stopped(task.id())).isFalse();
        assertThat(tasks.cancel(task.id()).state()).isEqualTo("STOPPING");
        assertThat(mapper.findActiveWorkspaceLeaseByHolder(task.id())).isPresent();
    }

    @Test void onlyAcceptedSameInputCacheSkipsAnalysisSessions() {
        TaskRow first = create("CODE_REVIEW");
        states.start(first.id(), evidence.contract(first.id())); run(first.id(), false);
        String today = LocalDate.now(TemplateDateRange.ZONE).toString();
        TaskRow second = admission.create(new TemplateTaskService.Request(UUID.randomUUID().toString(), "CODE_REVIEW", io.opencode.loopper.template.TemplateTaskDefinition.VERSION, projectId,
                "local:refs/heads/main", today, today, StoryBindingConfiguration.disabled()), false);
        states.start(second.id(), evidence.contract(second.id())); run(second.id(), false);
        assertThat(states.task(second.id()).state()).isEqualTo("COMPLETED");
        assertThat(mapper.listSessions(second.id())).isEmpty();
        assertThat(mapper.listJudgeRuns(second.id()).stream().filter(row -> row.state().equals("COMPLETED"))).hasSize(2);
    }

    private void run(String id, boolean malformedFirst) {
        for (int i = 0; i < 90 && !states.task(id).state().equals("COMPLETED"); i++) {
            TaskRow task = states.task(id);
            if (task.state().equals("JUDGING")) {
                fake.setJudgeOutput("{\"verdict\":\"PASS\",\"reason\":\"报告完整，证据与计算可核对\"}");
                driver.advance(id); tasks.pollJudges(id); continue;
            }
            var stage = mapper.listStages(id).get(1);
            var attempt = mapper.latestAttempt(stage.id()).orElse(null);
            if (attempt != null) for (var batch : templates.batches(id, attempt.id())) {
                if (!batch.state().equals("DISPATCHING")) continue;
                var input = json.readValue(batch.inputJson(), TemplateBatchExecution.Input.class);
                if (malformedFirst && templates.findRun(id).orElseThrow().repairRound() == 0) fake.setJudgeOutput("{\"reviews\":[]}");
                else if (batch.purpose().equals("REVIEW")) fake.setJudgeOutput(json.writeValueAsString(new TemplateAnalysis.BatchCandidate(input.units().stream()
                        .map(unit -> new TemplateAnalysis.UnitReview(unit.id(), "新增说明内容", List.of(), List.of("未运行测试"))).toList())));
                else {
                    var grade = new ContributionScore.Assessment(1, "变更有直接代码依据", List.of(input.person().commits().getFirst()));
                    fake.setJudgeOutput(json.writeValueAsString(new TemplateAnalysis.ContributorCandidate(input.person().author().identity(), "新增内容", grade, grade, grade, grade)));
                }
            }
            driver.advance(id);
        }
    }
}
