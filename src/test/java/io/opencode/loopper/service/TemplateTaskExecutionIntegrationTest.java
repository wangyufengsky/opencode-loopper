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
    @Autowired TemplateBatchStore batchStore;
    @Autowired TemplateBatchExecution batchExecution;
    @Autowired TemplateTaskReadMapper batchReads;
    @Autowired TemplateBatchAutomaticRetries automaticRetries;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;

    private TemplateBatchRetryService retryService() {
        return new TemplateBatchRetryService(batchStore, states, org.mockito.Mockito.mock(TemplateTaskCoordinator.class), transactionManager, batchReads, automaticRetries);
    }
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

    @Test void disabledTimeoutCompletesAfterBothHistoricalDeadlinesAndIgnoresLaterGlobalChange() {
        properties.setTimeoutEnabled(false);
        TaskRow task = create("CODE_REVIEW");
        assertThat(evidence.contract(task.id()).spec().limits().timeoutsEnabled()).isFalse();
        states.start(task.id(), evidence.contract(task.id()));
        for (int i=0; i<8 && mapper.latestAttempt(mapper.listStages(task.id()).get(1).id()).isEmpty(); i++) driver.advance(task.id());
        assertThat(mapper.latestAttempt(mapper.listStages(task.id()).get(1).id())).isPresent();
        String old = java.time.Instant.now().minus(java.time.Duration.ofDays(2)).toString();
        jdbc.update("UPDATE task_execution_cycle SET started_at=? WHERE task_id=?", old, task.id());
        jdbc.update("UPDATE attempt SET created_at=? WHERE stage_id IN (SELECT id FROM stage WHERE task_id=?)", old, task.id());
        properties.setTimeoutEnabled(true);
        try { run(task.id(),false); assertThat(states.task(task.id()).state()).isEqualTo("COMPLETED"); }
        finally { properties.setTimeoutEnabled(false); }
    }
    @Test void enabledTimeoutStillStopsAfterItsFrozenDuration() {
        properties.setTimeoutEnabled(true);
        TaskRow task;
        try { task=create("CODE_REVIEW"); } finally { properties.setTimeoutEnabled(false); }
        states.start(task.id(), evidence.contract(task.id()));
        states.prepare(task.id());
        jdbc.update("UPDATE task_execution_cycle SET started_at=? WHERE task_id=?", java.time.Instant.now().minus(java.time.Duration.ofDays(2)).toString(), task.id());
        driver.advance(task.id());
        assertThat(states.task(task.id()).state()).isEqualTo("WAITING_INPUT");
        assertThat(tasks.errors(task.id())).anyMatch(error -> error.code().equals("TEMPLATE_DURATION_EXHAUSTED"));
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"5", "6", "7"})
    void mcpReportsHonorFrozenAcceptanceAndCorrectInSameSession(String version) throws Exception {
        if (version.equals("7")) {
            Files.writeString(source.resolve("large.txt"), ("source evidence " + "x".repeat(100) + "\n").repeat(1500));
            git.read(source, "add", ".");
            git.read(source, "-c", "user.name=Test", "-c", "user.email=test@example.test", "commit", "-m", "large report evidence");
        }
        for (String definition : List.of("CODE_REVIEW", "CONTRIBUTION_REPORT")) {
            TaskRow task = create(definition);
            var contract = (tools.jackson.databind.node.ObjectNode) json.readTree(templates.findRun(task.id()).orElseThrow().contractJson());
            ((tools.jackson.databind.node.ObjectNode) contract.get("definition")).put("version", version);
            jdbc.update("UPDATE template_task_run SET template_version=?,contract_json=? WHERE task_id=?", version, json.writeValueAsString(contract), task.id());
            var credentials = new InternalMcpCredentialProvider(() -> 18083).issue();
            runtimeAccess.activate(credentials); runtimeAccess.connected(credentials.generation());
            fake.setManagedRuntime(credentials.generation(), credentials.serverName());
            states.start(task.id(), evidence.contract(task.id()));
            run(task.id(), false, version.equals("7"));
            if (version.equals("7")) {
                assertThat(states.task(task.id()).state()).isEqualTo("AWAITING_DECISION");
                assertThat(mapper.findActiveWorkspaceLeaseByHolder(task.id())).isPresent();
                // Resume from the committed validation checkpoint without any Judge or model call.
                int calls = fake.promptCalls();
                driver.advance(task.id());
                assertThat(fake.promptCalls()).isEqualTo(calls);
            }
            assertThat(states.task(task.id()).state()).isEqualTo("COMPLETED");
            assertThat(templates.findRun(task.id()).orElseThrow().repairRound()).isZero();
            if (version.equals("7")) {
                assertThat(mapper.listJudgeRuns(task.id())).isEmpty();
                assertThat(mapper.listJudgeReviewBatches(task.id())).isEmpty();
                assertThat(templates.findRun(task.id()).orElseThrow().snapshotJson().length()).isGreaterThan(131072);
                assertThat(mapper.listTaskArtifacts(task.id())).noneMatch(row -> row.kind().equals("TEMPLATE_JUDGE_EVIDENCE"));
                assertThat(taskReads.overview(task.id()).templateProgress().dualReviewRequired()).isFalse();
                assertThatThrownBy(() -> tasks.retryJudges(task.id())).isInstanceOf(ConflictException.class).hasMessageContaining("无需启动双评审");
                long reports = mapper.listTaskArtifacts(task.id()).stream().filter(row -> row.kind().equals("TEMPLATE_REPORT")).count();
                assertThat(taskReads.overview(task.id()).templateProgress().reportCount()).isEqualTo((int) reports);
                assertThat(taskReads.audit(task.id()).artifacts().stream().filter(row -> row.kind().equals("TEMPLATE_REPORT"))).hasSize((int) reports);
                assertThat(tasks.latestExecutionCycle(task.id()).state()).isEqualTo("SUCCEEDED");
                driver.advance(task.id());
                assertThat(states.task(task.id()).state()).isEqualTo("COMPLETED");
                if (definition.equals("CONTRIBUTION_REPORT")) exportBrowserFixture(task.id());
            } else {
                assertThat(mapper.listJudgeRuns(task.id()).stream().filter(judge -> "COMPLETED".equals(judge.state())).toList())
                        .hasSize(2).allMatch(judge -> "PASS".equals(judge.verdict()));
                assertThat(taskReads.overview(task.id()).templateProgress().dualReviewRequired()).isTrue();
            }
            assertThat(mapper.findActiveWorkspaceLeaseByHolder(task.id())).isEmpty();
            var finalAttempt = mapper.latestAttempt(mapper.listStages(task.id()).getLast().id()).orElseThrow();
            assertThat(templates.batches(task.id(), finalAttempt.id())).allSatisfy(batch -> {
                assertThat(batch.state()).isEqualTo("VALIDATED");
                assertThat(candidateReceipts.revision(batch.id())).isEqualTo(2);
                assertThat(batch.promptJson()).contains("submit_template_analysis");
            });
        }
    }

    @Test void newEmptyReportCompletesWithoutAnyModelCallAndKeepsItsFrozenPolicy() {
        var task = admission.create(new TemplateTaskService.Request(UUID.randomUUID().toString(), "CODE_REVIEW",
                TemplateTaskDefinition.VERSION, projectId, "local:refs/heads/main", "2001-01-01", "2001-01-01",
                StoryBindingConfiguration.disabled()), true);
        String frozen = templates.findRun(task.id()).orElseThrow().contractJson();
        assertThat(evidence.contract(task.id()).requiresDualReview()).isFalse();
        assertThat(evidence.contract(task.id()).spec().stages()).allSatisfy(stage ->
                assertThat(stage.acceptanceCriteria()).allMatch(criterion -> "MACHINE".equals(criterion.verificationMode())));
        states.start(task.id(), evidence.contract(task.id()));
        run(task.id(), false);
        assertThat(states.task(task.id()).state()).isEqualTo("COMPLETED");
        assertThat(fake.promptCalls()).isZero();
        assertThat(mapper.listJudgeRuns(task.id())).isEmpty();
        assertThat(mapper.findActiveWorkspaceLeaseByHolder(task.id())).isEmpty();
        assertThat(templates.findRun(task.id()).orElseThrow().contractJson()).isEqualTo(frozen);
        assertThat(taskReads.overview(task.id()).templateProgress().reportCount()).isPositive();
        assertThat(mapper.eventsAfter(task.id(), 0).stream().filter(event -> event.type().equals("verification.template_stage_completed"))).hasSize(2);
        assertThat(mapper.eventsAfter(task.id(), 0)).anyMatch(event -> event.type().equals("artifact.template_reports_saved"));
    }

    @Test void contributionReportWaitsForExplicitBatchRetryBeforeRanking() {
        TaskRow task = create("CONTRIBUTION_REPORT");
        states.start(task.id(), evidence.contract(task.id()));
        run(task.id(), true);
        assertThat(states.task(task.id()).state()).isEqualTo("COMPLETED");
        assertThat(templates.findRun(task.id()).orElseThrow().repairRound()).isZero();
        var finalAttempt = mapper.latestAttempt(mapper.listStages(task.id()).getLast().id()).orElseThrow();
        var history = templates.batches(task.id(), finalAttempt.id());
        assertThat(history.stream().filter(row -> row.state().equals("FAILED"))).hasSize(2);
        assertThat(history.stream().filter(row -> row.state().equals("VALIDATED"))).hasSize(2);
        for (var row : history) assertThat(row.inputSha256()).isEqualTo(templates.findBatchOrdinal(
                task.id(), finalAttempt.id(), row.purpose(), row.ordinal()).orElseThrow().inputSha256());
        var reports = mapper.listTaskArtifacts(task.id()).stream().filter(row -> row.kind().equals("TEMPLATE_REPORT")).toList();
        assertThat(reports).hasSize(4);
        assertThat(reports).anyMatch(row -> row.name().startsWith("项目贡献周报_") && row.content().contains("人员贡献与排名") && row.content().contains("CONTRIBUTION_SCORE_V1"));
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

    @Test void failuresDrainRemainingBatchesThenAtomicSelectionPreservesSuccessAndFinishesReport() throws Exception {
        for (int i = 0; i < 25; i++) Files.writeString(source.resolve("batch-file-" + i + ".txt"), "change " + i);
        git.read(source, "add", ".");
        git.read(source, "-c", "user.name=Test", "-c", "user.email=test@example.test", "commit", "-m", "three batches");
        TaskRow task = create("CODE_REVIEW");
        states.start(task.id(), evidence.contract(task.id()));
        boolean rejectedEarly = false;
        for (int tick = 0; tick < 40 && !states.task(task.id()).state().equals("WAITING_INPUT"); tick++) {
            for (var attempt : mapper.listAttempts(task.id())) for (var batch : templates.batches(task.id(), attempt.id())) {
                if (batch.state().equals("DISPATCHING")) {
                    var input = json.readValue(batch.inputJson(), TemplateBatchExecution.Input.class);
                    fake.setJudgeOutput(batch.ordinal() == 1 ? json.writeValueAsString(new TemplateAnalysis.BatchCandidate(input.units().stream()
                            .map(unit -> new TemplateAnalysis.UnitReview(unit.id(), "保留成功结果", List.of(), List.of())).toList())) : "{\"reviews\":[]}");
                }
                if (batch.state().equals("FAILED") && !batchReads.retrySelectionReady(task.id())) {
                    assertThatThrownBy(() -> retryService().retry(task.id(), batch.id(), batch.version()))
                            .isInstanceOf(ConflictException.class).hasMessageContaining("后续批次");
                    rejectedEarly = true;
                }
            }
            driver.advance(task.id());
        }
        assertThat(rejectedEarly).isTrue();
        assertThat(states.task(task.id()).state()).isEqualTo("WAITING_INPUT");
        var attempt = mapper.latestAttempt(mapper.listStages(task.id()).getLast().id()).orElseThrow();
        var originals = templates.batches(task.id(), attempt.id());
        assertThat(originals).hasSize(3).allMatch(row -> row.generation() == 0);
        var success = originals.stream().filter(row -> row.ordinal() == 1).findFirst().orElseThrow();
        assertThat(success.state()).isEqualTo("VALIDATED");
        assertThat(mapper.listTaskArtifacts(task.id())).noneMatch(row -> row.kind().equals("TEMPLATE_REPORT"));
        var failed = batchReads.failedBatches(task.id(), null, null, 100);
        assertThat(failed).extracting(TemplateTaskReadMapper.FailedBatch::ordinal).containsExactlyInAnyOrder(0, 2);
        assertThat(batchReads.retrySelectionReady(task.id())).isTrue();
        exportFixture(task.id(), System.getenv("LOOPPER_BATCH_FIXTURE_DIR"));
        var choice = failed.stream().map(row -> new BatchRetrySelection.Item(row.id(), row.version())).toList();
        assertThatThrownBy(() -> retryService().retrySelected(task.id(), new BatchRetrySelection(List.of(choice.getFirst(),
                new BatchRetrySelection.Item(choice.getLast().id(), choice.getLast().expectedVersion() + 1)))))
                .isInstanceOf(ConflictException.class);
        assertThat(states.task(task.id()).state()).isEqualTo("WAITING_INPUT");
        assertThat(templates.batches(task.id(), attempt.id())).hasSize(3);
        var selected = new BatchRetrySelection(choice);
        var waitingSnapshot = states.task(task.id());
        assertThat(retryService().retrySelected(task.id(), selected)).hasSize(2).allMatch(row -> row.generation() == 1);
        assertThatThrownBy(() -> retryService().retrySelected(task.id(), selected)).isInstanceOf(ConflictException.class);
        assertThat(templates.batches(task.id(), attempt.id())).hasSize(5);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(driver, "repairIfEligible", waitingSnapshot);
        assertThat(templates.batches(task.id(), attempt.id()).stream().filter(row -> row.generation() == 1))
                .allMatch(row -> row.state().equals("PREPARED"));
        run(task.id(), false);
        assertThat(states.task(task.id()).state()).isEqualTo("COMPLETED");
        assertThat(batchStore.require(success.id())).isEqualTo(success);
        assertThat(taskReads.overview(task.id()).templateProgress().reportCount()).isPositive();
    }

    @Test void legacyStopOfUnstartedBatchDoesNotCreateAnotherFailureChoice() throws Exception {
        for (int i = 0; i < 13; i++) Files.writeString(source.resolve("pending-" + i + ".txt"), "change " + i);
        git.read(source, "add", ".");
        git.read(source, "-c", "user.name=Test", "-c", "user.email=test@example.test", "commit", "-m", "two batches");
        var task = create("CODE_REVIEW");
        states.start(task.id(), evidence.contract(task.id()));
        for (int tick = 0; tick < 20 && batchReads.failedBatches(task.id(), null, null, 100).isEmpty(); tick++) {
            fake.setJudgeOutput("{\"reviews\":[]}"); driver.advance(task.id());
        }
        var attempt = mapper.latestAttempt(mapper.listStages(task.id()).getLast().id()).orElseThrow();
        var pending = templates.findBatchOrdinal(task.id(), attempt.id(), "REVIEW", 1).orElseThrow();
        assertThat(pending.sessionId()).isNull();
        assertThat(batchExecution.stop(pending)).isTrue();
        states.waiting(task.id(), "TEMPLATE_SUBMISSION_MISSING", "历史整轮暂停");
        var choices = batchReads.failedBatches(task.id(), null, null, 100);
        assertThat(choices).hasSize(1).allMatch(row -> row.ordinal() == 0);
        retryService().retry(task.id(), choices.getFirst().id(), choices.getFirst().version());
        run(task.id(), false);
        assertThat(states.task(task.id()).state()).isEqualTo("COMPLETED");
        assertThat(batchStore.require(pending.id()).state()).isEqualTo("STOPPED");
        assertThat(templates.findBatchOrdinal(task.id(), attempt.id(), "REVIEW", 1).orElseThrow().state()).isEqualTo("VALIDATED");
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
        assertThat(completed.repairRound()).isZero();
        assertThat(completed.completedReviews()).isEqualTo(3);
        assertThat(completed.completedContributors()).isEqualTo(1);
        assertThat(completed.failedBatches()).isZero();
        assertThat(completed.activeBatches()).isZero();
    }

    private void exportBrowserFixture(String taskId) {
        exportFixture(taskId, System.getProperty("template.browser.fixtureDir"));
    }
    private void exportFixture(String taskId, String destination) {
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

    @Test void failedContentRemainsWaitingWithoutAutomaticRetriesAndManualCancelClosesLease() {
        TaskRow task = create("CODE_REVIEW");
        states.start(task.id(), evidence.contract(task.id()));
        for (int i = 0; i < 40; i++) {
            fake.setJudgeOutput("{\"reviews\":[]}");
            driver.advance(task.id());
        }
        assertThat(states.task(task.id()).state()).isEqualTo("WAITING_INPUT");
        assertThat(templates.findRun(task.id()).orElseThrow().repairRound()).isZero();
        assertThat(mapper.listAttempts(task.id())).hasSize(2);
        assertThat(tasks.cancel(task.id()).state()).isEqualTo("CANCELLED");
        assertThat(mapper.findActiveWorkspaceLeaseByHolder(task.id())).isEmpty();
        tasks.archive(task.id()); tasks.deleteArchived(task.id());
        assertThat(templates.findRun(task.id())).isEmpty();
        assertThat(mapper.findTask(task.id())).isEmpty();
    }

    @Test void currentReportTemplatesUseIndependentMcpSessionsAndKeepFourSlotsUntilCompletion() throws Exception {
        for (int i = 0; i < 60; i++) Files.writeString(source.resolve("unit-" + i + ".txt"), "bounded evidence\n");
        git.read(source, "add", ".");
        git.read(source, "-c", "user.name=Test", "-c", "user.email=test@example.test", "commit", "-m", "many independent units");
        var credentials = new InternalMcpCredentialProvider(() -> 18083).issue();
        runtimeAccess.activate(credentials); runtimeAccess.connected(credentials.generation());
        fake.setManagedRuntime(credentials.generation(), credentials.serverName());
        fake.holdProfileOpen(OpenCodeClient.SessionProfile.TEMPLATE_ANALYSIS_CANDIDATE_NO_TOOLS, true);
        for (String definition : List.of("CODE_REVIEW", "CONTRIBUTION_REPORT")) {
            String today = LocalDate.now(TemplateDateRange.ZONE).toString();
            var task = admission.create(new TemplateTaskService.Request(UUID.randomUUID().toString(), definition,
                    TemplateTaskDefinition.VERSION, projectId, "local:refs/heads/main", today, today, StoryBindingConfiguration.disabled()), true);
            var contract = evidence.contract(task.id()); assertThat(contract.analysisConcurrency()).isEqualTo(4);
            states.start(task.id(), contract); int peak = 0; var completed = new java.util.HashSet<String>();
            for (int tick = 0; tick < 180 && !states.task(task.id()).state().equals("COMPLETED"); tick++) {
                driver.advance(task.id());
                var attempt = mapper.latestAttempt(mapper.listStages(task.id()).get(1).id()).orElse(null);
                if (attempt == null) continue;
                var batches = templates.batches(task.id(), attempt.id());
                var active = batches.stream().filter(row -> !List.of("PREPARED", "VALIDATED", "FAILED").contains(row.state())).toList();
                assertThat(active).hasSizeLessThanOrEqualTo(4); peak = Math.max(peak, active.size());
                if (batches.stream().anyMatch(row -> row.purpose().equals("REVIEW") && !row.state().equals("VALIDATED")))
                    assertThat(active).noneMatch(row -> row.purpose().equals("CONTRIBUTOR"));
                var next = active.stream().filter(row -> row.state().equals("RUNNING") && !completed.contains(row.id()))
                        .max(java.util.Comparator.comparingInt(TemplateTaskBatchRow::ordinal)).orElse(null);
                if (next == null) continue;
                var input = json.readValue(next.inputJson(), TemplateBatchExecution.Input.class); Object result;
                if (next.purpose().equals("REVIEW")) result = new TemplateAnalysis.BatchCandidate(input.units().stream()
                        .map(unit -> new TemplateAnalysis.UnitReview(unit.id(), "静态证据已检查", List.of(), List.of("未运行测试"))).toList());
                else {
                    var grade = new ContributionScore.Assessment(1, "依据冻结变更", List.of(input.person().commits().getFirst()));
                    result = new TemplateAnalysis.ContributorCandidate(input.person().author().identity(), "新增说明", grade, grade, grade, grade);
                }
                assertThat(candidateSubmissions.submit(next.id(), "accepted", 0, json.writeValueAsString(result))).contains("ACCEPTED");
                fake.setSessionState(mapper.findSession(next.sessionId()).orElseThrow().externalSessionId(), "COMPLETED"); completed.add(next.id());
            }
            assertThat(peak).isEqualTo(4); assertThat(completed.size()).isGreaterThanOrEqualTo(5);
            assertThat(states.task(task.id()).state()).isEqualTo("COMPLETED");
            assertThat(mapper.listJudgeRuns(task.id())).isEmpty();
        }
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
            if (malformedFirst && task.state().equals("WAITING_INPUT")
                    && "TEMPLATE_BATCHES_FAILED".equals(TaskWaitingInputPolicy.reasonCode(task, mapper))) {
                var failed = batchReads.failedBatches(id, null, null, 100);
                retryService().retrySelected(id, new BatchRetrySelection(failed.stream()
                        .map(row -> new BatchRetrySelection.Item(row.id(), row.version())).toList()));
                continue;
            }
            if (stopAtJudging && task.state().equals("AWAITING_DECISION")) return;
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
                boolean mcp = List.of("5", "6", "7").contains(templates.findRun(id).orElseThrow().templateVersion());
                if (!batch.state().equals("DISPATCHING") && !(mcp && batch.state().equals("RUNNING"))) continue;
                var input = json.readValue(batch.inputJson(), TemplateBatchExecution.Input.class);
                if (malformedFirst && batch.generation() == 0) fake.setJudgeOutput("{\"reviews\":[]}");
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
