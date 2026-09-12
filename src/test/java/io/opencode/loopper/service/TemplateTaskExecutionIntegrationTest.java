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
    @Autowired TaskReadService taskReads;
    @Autowired TemplateReportArtifactService reportArtifacts;
    @Autowired TemplateReportBundleMapper bundles;
    @Autowired TemplateReportDownloadService downloads;
    @Autowired LoopperMapper mapper;
    @Autowired TaskService tasks;
    @Autowired ProjectService projects;
    @Autowired GitEvidenceProcess git;
    @Autowired LoopperProperties properties;
    @Autowired OpenCodeClient client;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired TemplateGitCaptureGuard gitGuard;
    @Autowired SessionLifecycleService sessionLifecycle;
    @Autowired TemplateCandidateSubmissionService candidateSubmissions;
    @Autowired TemplateCandidateSubmissionMapper candidateReceipts;
    @Autowired InternalMcpRuntimeAccess runtimeAccess;
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

    @Test void legacyBoundCodeReviewCompletesOnlyAfterTwoJudgesAndPreservesSource() throws Exception {
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
                && row.content().contains("| 提交覆盖 | 1 / 1 |") && row.content().contains("CODE_REVIEW_V3"));
        String sessionId = mapper.listSessions(task.id()).getFirst().id();
        var owner = mapper.findStoryAccountingOwner(mapper.findSession(sessionId).orElseThrow().externalSessionId()).orElseThrow();
        assertThat(owner.role()).isEqualTo("IMPLEMENTATION");
        assertThat(owner.systemCode()).isEqualTo("001");
        assertThat(owner.storyCode()).isEqualTo("0002");
        assertThatThrownBy(() -> sessionLifecycle.summarize(task.id(), sessionId, false)).isInstanceOf(ConflictException.class).hasMessageContaining("冻结证据");
        assertThatThrownBy(() -> sessionLifecycle.fork(task.id(), sessionId, "message")).isInstanceOf(ConflictException.class).hasMessageContaining("冻结证据");
        assertThatThrownBy(() -> sessionLifecycle.revert(task.id(), sessionId, "message", "part")).isInstanceOf(ConflictException.class).hasMessageContaining("冻结证据");
    }

    @Test void newTemplateAnalysisCompletesWithoutStoryBindingOrAccountingOwners() {
        TaskRow task = create("CODE_REVIEW");
        states.start(task.id(), evidence.contract(task.id()));
        run(task.id(), false);
        assertThat(states.task(task.id()).state()).isEqualTo("COMPLETED");
        assertThat(mapper.findTaskStoryBinding(task.id())).isEmpty();
        assertThat(mapper.listSessions(task.id())).isNotEmpty().allSatisfy(session -> {
            assertThat(mapper.findStoryAccountingOwner(session.externalSessionId())).isEmpty();
            assertThat(mapper.findStoryAccountingSession(session.externalSessionId())).isEmpty();
        });
    }

    @Test void v5McpCodeAndContributionReportsCorrectInSameSessionThenReachDoubleJudgeAcceptance() {
        for (String definition : List.of("CODE_REVIEW", "CONTRIBUTION_REPORT")) {
            TaskRow task = create(definition);
            var contract = (tools.jackson.databind.node.ObjectNode) json.readTree(templates.findRun(task.id()).orElseThrow().contractJson());
            ((tools.jackson.databind.node.ObjectNode) contract.get("definition")).put("version", "5");
            jdbc.update("UPDATE template_task_run SET template_version='5',contract_json=? WHERE task_id=?", json.writeValueAsString(contract), task.id());
            var credentials = new InternalMcpCredentialProvider(() -> 18083).issue();
            runtimeAccess.activate(credentials); runtimeAccess.connected(credentials.generation());
            fake.setManagedRuntime(credentials.generation(), credentials.serverName());
            states.start(task.id(), evidence.contract(task.id()));
            run(task.id(), false);
            assertThat(states.task(task.id()).state()).isEqualTo("COMPLETED");
            assertThat(templates.findRun(task.id()).orElseThrow().repairRound()).isZero();
            assertThat(mapper.listJudgeRuns(task.id()).stream().filter(judge -> "COMPLETED".equals(judge.state())).toList())
                    .hasSize(2).allMatch(judge -> "PASS".equals(judge.verdict()));
            assertThat(mapper.findActiveWorkspaceLeaseByHolder(task.id())).isEmpty();
            var finalAttempt = mapper.latestAttempt(mapper.listStages(task.id()).getLast().id()).orElseThrow();
            assertThat(templates.batches(task.id(), finalAttempt.id())).allSatisfy(batch -> {
                assertThat(batch.state()).isEqualTo("VALIDATED");
                assertThat(candidateReceipts.revision(batch.id())).isEqualTo(2);
                assertThat(batch.promptJson()).contains("submit_template_analysis");
            });
        }
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
        assertThat(reports).hasSize(4);
        assertThat(reports).anyMatch(row -> row.name().startsWith("项目贡献周报_") && row.content().contains("人员贡献与排名") && row.content().contains("CONTRIBUTION_SCORE_V1"));
        exportBrowserFixture(task.id());
    }

    @Test void writesReportsToFrozenProjectDirectoryAndKeepsOtherFiles() throws Exception {
        var project = projects.get(projectId);
        Path destination = temporary.toRealPath().resolve("exported reports");
        Files.createDirectories(destination);
        Files.writeString(destination.resolve("code-review.md"), "user document");
        var configured = projects.updateDocumentPath(projectId, destination.toString(), project.version());
        TaskRow task = create("CODE_REVIEW");
        projects.updateDocumentPath(projectId, "docs/new-default", configured.version());
        states.start(task.id(), evidence.contract(task.id()));
        run(task.id(), false);
        assertThat(states.task(task.id()).state()).isEqualTo("COMPLETED");
        var progress = taskReads.overview(task.id()).templateProgress();
        Path output = Path.of(progress.documentPath()).resolve(main(task.id()).name());
        assertThat(output).startsWith(destination).exists();
        var artifact = main(task.id());
        assertThat(Files.readString(output)).isEqualTo(artifact.content());
        assertThat(Files.readString(destination.resolve("code-review.md"))).isEqualTo("user document");
        assertThat(source.resolve("docs/new-default")).doesNotExist();
        assertThat(progress.reviewBatches()).isEqualTo(1);
        assertThat(progress.completedReviews()).isEqualTo(1);
    }

    @Test void replayedReportWritesAreIdempotentAndNeverReplaceEditedFiles() throws Exception {
        var project = projects.get(projectId);
        projects.updateDocumentPath(projectId, "docs/reports", project.version());
        TaskRow task = create("CODE_REVIEW");
        states.start(task.id(), evidence.contract(task.id()));
        run(task.id(), false, true);
        TaskRow current = states.task(task.id());
        assertThat(current.state()).isEqualTo("JUDGING");
        String attemptId = mapper.latestAttempt(mapper.listStages(task.id()).getLast().id()).orElseThrow().id();
        Path report = Path.of(taskReads.overview(task.id()).templateProgress().documentPath()).resolve(main(task.id()).name());
        String original = Files.readString(report);
        reportArtifacts.materialize(current, attemptId);
        assertThat(Files.readString(report)).isEqualTo(original);
        Files.writeString(report, "external edit");
        assertThatThrownBy(() -> reportArtifacts.materialize(current, attemptId))
                .isInstanceOf(io.opencode.loopper.domain.TaskFailure.class).hasMessageContaining("外部修改");
        assertThat(Files.readString(report)).isEqualTo("external edit");
    }

    @Test void repairRetainsOldBundleAndZipIncludesOnlySelectedAttempt() throws Exception {
        TaskRow task = create("CODE_REVIEW");
        states.start(task.id(), evidence.contract(task.id())); run(task.id(), false, true);
        var oldMain = main(task.id());
        var oldBundle = bundles.find(task.id(), oldMain.attemptId()).orElseThrow();
        assertThat(oldBundle.sequence()).isEqualTo(1);
        states.waiting(task.id(), "JUDGE_REVIEW_NOT_APPROVED", "fixture report needs revision");
        assertThat(states.repair(task.id())).isTrue();
        run(task.id(), false);
        var newAttempt = mapper.latestAttempt(mapper.listStages(task.id()).getLast().id()).orElseThrow();
        var newBundle = bundles.find(task.id(), newAttempt.id()).orElseThrow();
        assertThat(newBundle.sequence()).isEqualTo(2);
        assertThat(newBundle.folderName()).endsWith("_002");
        assertThat(bundles.reports(task.id(), oldMain.attemptId())).hasSize(4);
        var archive = downloads.download(task.id(), oldMain.id());
        assertThat(archive.filename()).isEqualTo(oldBundle.folderName() + ".zip");
        var unpacked = new java.util.LinkedHashMap<String, String>();
        try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(archive.bytes()), java.nio.charset.StandardCharsets.UTF_8)) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) unpacked.put(entry.getName(), new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        assertThat(unpacked).hasSize(4);
        for (var report : bundles.reports(task.id(), oldMain.attemptId())) assertThat(unpacked)
                .containsEntry(oldBundle.folderName() + "/" + report.name(), report.content());
        assertThat(unpacked.keySet()).noneMatch(name -> name.startsWith(newBundle.folderName() + "/"));
        assertThatThrownBy(() -> downloads.download("other-task", oldMain.id())).isInstanceOf(NotFoundException.class);
        assertThat(taskReads.audit(task.id()).artifacts()).anySatisfy(report ->
                assertThat(report.metadataSummary().path("bundleId").asText()).isEqualTo(oldMain.attemptId()));
    }

    @Test void progressIncludesUnstartedBatchesAndResetsCompletedCountsForRepair() throws Exception {
        for (int index = 0; index < 25; index++) Files.writeString(source.resolve("file-" + index + ".txt"), "change " + index);
        git.read(source, "add", ".");
        git.read(source, "-c", "user.name=Test", "-c", "user.email=test@example.test", "commit", "-m", "many files");
        TaskRow task = create("CONTRIBUTION_REPORT");
        states.start(task.id(), evidence.contract(task.id()));
        for (int index = 0; index < 3; index++) driver.advance(task.id());
        var progress = taskReads.overview(task.id()).templateProgress();
        assertThat(progress.reviewBatches()).isEqualTo(3);
        assertThat(progress.contributorBatches()).isEqualTo(1);
        assertThat(progress.completedReviews()).isZero();
        assertThat(progress.completedContributors()).isZero();
        run(task.id(), true);
        var completed = taskReads.overview(task.id()).templateProgress();
        assertThat(completed.repairRound()).isEqualTo(1);
        assertThat(completed.completedReviews()).isEqualTo(3);
        assertThat(completed.completedContributors()).isEqualTo(1);
        assertThat(completed.failedBatches()).isZero();
        assertThat(completed.activeBatches()).isZero();
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
                    "taskId", taskId, "provider", "fake", "templateVersion", templates.findRun(taskId).orElseThrow().templateVersion())));
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

    private TaskArtifactRow main(String taskId) {
        return mapper.listTaskArtifacts(taskId).stream().filter(row -> row.kind().equals("TEMPLATE_REPORT")
                && "SUMMARY".equals(json.readTree(row.metadataJson()).path("reportRole").asText())).findFirst().orElseThrow();
    }

    private TaskRow create(String definition) {
        String today = LocalDate.now(TemplateDateRange.ZONE).toString();
        var task = admission.create(new TemplateTaskService.Request(UUID.randomUUID().toString(), definition, io.opencode.loopper.template.TemplateTaskDefinition.VERSION, projectId,
                "local:refs/heads/main", today, today, StoryBindingConfiguration.disabled()), true);
        LegacyTemplateFixture.freezeV4(jdbc, json, task.id());
        return task;
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
        LegacyTemplateFixture.freezeV4(jdbc, json, second.id());
        states.start(second.id(), evidence.contract(second.id())); run(second.id(), false);
        assertThat(states.task(second.id()).state()).isEqualTo("COMPLETED");
        assertThat(mapper.listSessions(second.id())).isEmpty();
        assertThat(bundles.find(first.id(), main(first.id()).attemptId()).orElseThrow().sequence()).isEqualTo(1);
        assertThat(bundles.find(second.id(), main(second.id()).attemptId()).orElseThrow().sequence()).isEqualTo(2);
        tasks.archive(first.id()); tasks.deleteArchived(first.id());
        assertThat(bundles.find(first.id(), "missing")).isEmpty();
        TaskRow third = create("CODE_REVIEW");
        states.start(third.id(), evidence.contract(third.id())); run(third.id(), false);
        assertThat(bundles.find(third.id(), main(third.id()).attemptId()).orElseThrow().sequence()).isEqualTo(3);
        assertThat(taskReads.overview(second.id()).templateProgress().completedReviews()).isEqualTo(1);
        assertThat(mapper.listJudgeRuns(second.id()).stream().filter(row -> row.state().equals("COMPLETED"))).hasSize(2);
    }

    private void run(String id, boolean malformedFirst) { run(id, malformedFirst, false); }

    private void run(String id, boolean malformedFirst, boolean stopAtJudging) {
        for (int i = 0; i < 90 && !states.task(id).state().equals("COMPLETED"); i++) {
            TaskRow task = states.task(id);
            if (task.state().equals("JUDGING")) {
                // This fixture simulates template MCP only; Judge behavior is covered by its own MCP suite.
                fake.setManagedRuntime(null, null);
                runtimeAccess.current().ifPresent(credentials -> runtimeAccess.clear(credentials.generation()));
                if (stopAtJudging) return;
                fake.setJudgeOutput("{\"verdict\":\"PASS\",\"reason\":\"报告完整，证据与计算可核对\"}");
                driver.advance(id); tasks.pollJudges(id); continue;
            }
            var stage = mapper.listStages(id).get(1);
            var attempt = mapper.latestAttempt(stage.id()).orElse(null);
            if (attempt != null) for (var batch : templates.batches(id, attempt.id())) {
                boolean mcp = "5".equals(templates.findRun(id).orElseThrow().templateVersion());
                if (!batch.state().equals("DISPATCHING") && !(mcp && batch.state().equals("RUNNING"))) continue;
                var input = json.readValue(batch.inputJson(), TemplateBatchExecution.Input.class);
                if (malformedFirst && templates.findRun(id).orElseThrow().repairRound() == 0) fake.setJudgeOutput("{\"reviews\":[]}");
                else if (batch.purpose().equals("REVIEW")) fake.setJudgeOutput(json.writeValueAsString(new TemplateAnalysis.BatchCandidate(input.units().stream()
                        .map(unit -> new TemplateAnalysis.UnitReview(unit.id(), "新增说明内容", List.of(), List.of("未运行测试"))).toList())));
                else {
                    var grade = new ContributionScore.Assessment(1, "变更有直接代码依据", List.of(input.person().commits().getFirst()));
                    fake.setJudgeOutput(json.writeValueAsString(new TemplateAnalysis.ContributorCandidate(input.person().author().identity(), "新增内容", grade, grade, grade, grade)));
                }
                if (mcp && batch.state().equals("RUNNING")) {
                    candidateSubmissions.submit(batch.id(), "bad", 0, "{}");
                    String candidate;
                    if (batch.purpose().equals("REVIEW")) candidate = json.writeValueAsString(new TemplateAnalysis.BatchCandidate(input.units().stream()
                            .map(unit -> new TemplateAnalysis.UnitReview(unit.id(), "新增说明内容", List.of(), List.of("未运行测试"))).toList()));
                    else {
                        var grade = new ContributionScore.Assessment(1, "变更有直接代码依据", List.of(input.person().commits().getFirst()));
                        candidate = json.writeValueAsString(new TemplateAnalysis.ContributorCandidate(input.person().author().identity(), "新增内容", grade, grade, grade, grade));
                    }
                    assertThat(candidateSubmissions.submit(batch.id(), "good", 1, candidate)).contains("ACCEPTED");
                }
            }
            int previousRound = templates.findRun(id).orElseThrow().repairRound();
            driver.advance(id);
            if (templates.findRun(id).orElseThrow().repairRound() > previousRound) {
                var nextProgress = taskReads.overview(id).templateProgress();
                assertThat(nextProgress.completedReviews()).isZero();
                assertThat(nextProgress.completedContributors()).isZero();
                assertThat(nextProgress.failedBatches()).isZero();
            }
        }
    }
}
