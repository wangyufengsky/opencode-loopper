package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.*;
import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.runtime.*;
import io.opencode.loopper.template.*;
import io.opencode.loopper.template.SnapshotReview.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes=LoopperApplication.class, properties={"loopper.opencode.mode=fake","loopper.monitor-delay=1h"})
class SnapshotReviewIntegrationTest {
    @Autowired Flyway flyway;
    @Autowired TemplateTaskService admission;
    @Autowired TemplateTaskStateService states;
    @Autowired TemplateTaskCoordinator driver;
    @Autowired TemplateRunEvidenceService contracts;
    @Autowired SnapshotReviewStore snapshots;
    @Autowired SnapshotReviewReads reads;
    @Autowired SnapshotReviewProtocol protocol;
    @Autowired TemplateTaskMapper templates;
    @Autowired TemplateBatchStore batchStore;
    @Autowired LoopperMapper mapper;
    @Autowired TemplateTaskReadMapper progress;
    @Autowired TemplateCandidateSubmissionService submissions;
    @Autowired TemplateCandidateSubmissionMapper receipts;
    @Autowired ProjectService projects;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean GitEvidenceProcess git;
    @Autowired SnapshotReviewAuthors authors;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired LoopperProperties properties;
    @Autowired OpenCodeClient client;
    @Autowired InternalMcpRuntimeAccess access;
    @Autowired ObjectMapper json;
    @Autowired TemplateTaskAdmission frozenAdmission;
    @Autowired SnapshotReviewMapper snapshotRecords;
    @Autowired TaskService taskService;
    @Autowired SnapshotReviewPartialReports partialReports;
    @Autowired TemplateTaskContractFactory contractFactory;
    @Autowired ProjectBranchService branches;
    @TempDir Path temporary;
    private Path source;
    private String project;
    private FakeOpenCodeClient fake;
    private boolean supplement;
    private boolean findings;
    private boolean failOne;
    private boolean lightweight;
    private boolean legacyV2;
    private boolean stopAfterAnalysis;
    @BeforeEach void setup() throws Exception {
        flyway.clean(); flyway.migrate(); fake=(FakeOpenCodeClient)client; fake.reset();
        properties.getOpenCode().setModel("fake/test"); properties.setTimeoutEnabled(false);
        var credentials=new InternalMcpCredentialProvider(()->18083).issue();
        access.activate(credentials); access.connected(credentials.generation()); fake.setManagedRuntime(credentials.generation(),credentials.serverName());
        fake.holdProfileOpen(OpenCodeClient.SessionProfile.SNAPSHOT_CODE_REVIEW_NO_TOOLS,true);
        source=Files.createDirectory(temporary.resolve("project")).toRealPath(); git.read(source,"init","-b","main");
        git.read(source,"config","user.name","Alice");git.read(source,"config","user.email","alice@example.test");
        commit("main.java","class Main { int value() { return 1; } }\n","2026-08-30T00:00:00Z");
        project=projects.create("静态审查项目",source.toString(),"test").id();
    }
    private String commit(String path,String content,String time) throws Exception {
        Files.writeString(source.resolve(path),content);git.read(source,"add","--",path);
        var result=new SafeProcessRunner().run(source,List.of("git","-c","core.hooksPath=/dev/null","commit","-m","change"),Duration.ofSeconds(10),Map.of("GIT_AUTHOR_DATE",time,"GIT_COMMITTER_DATE",time));
        assertThat(result.exitCode()).as(result.output()).isZero(); return git.read(source,"rev-parse","HEAD").strip();
    }
    private TaskRow create(Mode mode) {
        if (lightweight && !legacyV2) return admission.create(new TemplateTaskService.Request(UUID.randomUUID().toString(),SnapshotReview.ID,"3",project,"local:refs/heads/main",
                mode==Mode.FULL?null:"2026-09-01",mode==Mode.FULL?null:"2026-09-10",null,null,mode.name()),false);
        // Simulate an already-frozen V1 run. Public creation only accepts the current lightweight version.
        var dates = TemplateDateRange.parse("2026-09-01", "2026-09-10", Clock.systemUTC());
        var base = contractFactory.freezeSnapshot(project, dates, source.resolve("doc").toString(), mode); var d = base.definition();
        var legacy = new TemplateTaskDefinition.View(d.id(), legacyV2 ? "2" : "1", d.title(), d.description(), d.contentRepairLimit(), d.stages(), d.scoringVersion(), d.icon(), d.category());
        var frozen = new TemplateTaskContractFactory.Frozen(legacy, base.spec(), null, null, List.of(), base.timezone(), base.timePolicy(),
                base.repairLimit(), base.reportTemplates(), base.documentPath(), base.analysisConcurrency(), mode.name());
        return frozenAdmission.create(new TemplateTaskAdmission.Command(UUID.randomUUID().toString(), "legacy-fixture", branches.require(project, "local:refs/heads/main"), dates, frozen, false));
    }
    private void start(TaskRow task) { states.start(task.id(),contracts.contract(task.id())); }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void subdirectoryReviewFreezesOnlyModuleFilesWhileRemoteIsUnavailable(boolean existedAtStart) throws Exception {
        Path module = Files.createDirectory(source.resolve("module"));
        commit("module/code.java", "class Module { int value = 1; }\n", existedAtStart ? "2026-08-31T00:00:00Z" : "2026-09-03T00:00:00Z");
        commit("module/code.java", "class Module { int value = 2; }\n", "2026-09-05T00:00:00Z");
        git.read(source, "remote", "add", "origin", "http://127.0.0.1:1/unavailable.git");
        project = projects.create("模块审查", module.toString(), "module").id();
        lightweight = true;
        var task = create(Mode.DATE_INCREMENTAL); start(task); run(task);
        var snapshot = snapshots.snapshot(task.id());
        assertThat(snapshot.files()).extracting(SnapshotReview.File::path).containsOnly("code.java");
        assertThat(snapshot.units()).allMatch(unit -> unit.path().equals("code.java"));
        assertThat(states.task(task.id()).state()).isEqualTo("COMPLETED");
        assertThat(git.read(source, "status", "--porcelain")).isBlank();
    }

    @Test void fullReviewRunsPlanningAnalysisIndependentReadsAndCompletesWithoutTouchingDirtyCheckout() throws Exception {
        Files.writeString(source.resolve("dirty.txt"),"keep");String head=git.read(source,"rev-parse","HEAD");
        var task=create(Mode.FULL); assertThat(mapper.findTaskQueue(task.id())).isEmpty();start(task);run(task);
        assertThat(states.task(task.id()).state()).isEqualTo("COMPLETED");
        assertThat(mapper.listStages(task.id())).hasSize(4).allMatch(s->s.state().equals("SUCCEEDED"));
        assertThat(mapper.listJudgeRuns(task.id())).isEmpty();
        assertThat(progress.snapshotProgress(task.id()).orElseThrow().reviewed()).isPositive();
        assertThat(mapper.listTaskArtifacts(task.id())).anyMatch(a->a.content().contains("# 代码审查报告")&&a.content().contains("未执行目标项目"));
        assertThat(git.read(source,"rev-parse","HEAD")).isEqualTo(head);assertThat(Files.readString(source.resolve("dirty.txt"))).isEqualTo("keep");
        assertThat(mapper.findActiveWorkspaceLeaseByHolder(task.id())).isEmpty();
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void intermediateDefectThatWasRevertedProducesNoFinalDifferenceAndNoModelSessions(boolean light) throws Exception {
        lightweight = light;
        String baseline=git.read(source,"rev-parse","HEAD").strip();
        commit("main.java","class Main { int value() { return 0; } }\n","2026-09-03T00:00:00Z");
        String target=commit("main.java","class Main { int value() { return 1; } }\n","2026-09-10T15:59:59Z");
        commit("outside.txt","after period","2026-09-10T16:00:00Z");
        var task=create(Mode.DATE_INCREMENTAL);start(task);run(task);
        var snapshot=snapshots.snapshot(task.id());
        assertThat(snapshot.baselineSha()).isEqualTo(baseline);assertThat(snapshot.targetSha()).isEqualTo(target);assertThat(snapshot.noChanges()).isTrue();
        assertThat(mapper.listSessions(task.id())).isEmpty();assertThat(states.task(task.id()).state()).isEqualTo("COMPLETED");
    }
    @Test void missingBoundaryIsReportedBeforeCallingModel() {
        var task=admission.create(new TemplateTaskService.Request(UUID.randomUUID().toString(),SnapshotReview.ID,"3",project,"local:refs/heads/main","2026-01-01","2026-01-02",null,null,"DATE_INCREMENTAL"),false);
        start(task);
        for(int i=0;i<8;i++)driver.executeCheckpoint(task.id());
        assertThat(states.task(task.id()).state()).isEqualTo("WAITING_INPUT");assertThat(mapper.listSessions(task.id())).isEmpty();
    }
    @Test void dateModeValidationAndRequestReplayAreStable() {
        var request=new TemplateTaskService.Request(UUID.randomUUID().toString(),SnapshotReview.ID,"3",project,"local:refs/heads/main",null,null,null,null,"FULL");
        assertThat(admission.create(request,false).id()).isEqualTo(admission.create(request,false).id());
        assertThatThrownBy(()->admission.create(new TemplateTaskService.Request(UUID.randomUUID().toString(),SnapshotReview.ID,"3",project,"local:refs/heads/main","2026-09-01","2026-09-10",null,null,"FULL"),false)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(()->admission.create(new TemplateTaskService.Request(UUID.randomUUID().toString(),SnapshotReview.ID,"1",project,"local:refs/heads/main",null,null,null,null,"FULL"),false)).isInstanceOf(ConflictException.class);
    }
    @Test void lightweightFullReviewUsesFixedCapacityBatchesAndSkipsEmptyFindingReviews() throws Exception {
        for (int i = 0; i < 120; i++) Files.writeString(source.resolve("file" + i + ".java"), "class File" + i + " {}\n");
        git.read(source, "add", "."); commit(".env", "TOKEN=fixture-only\n", "2026-09-03T00:00:00Z");
        lightweight = true; var task = create(Mode.FULL); start(task); run(task);
        assertThat(states.task(task.id()).state()).isEqualTo("COMPLETED");
        assertThat(mapper.listSessions(task.id())).hasSize(2);
        var p = progress.snapshotProgress(task.id()).orElseThrow();
        assertThat(p.planning()).isZero(); assertThat(p.reviews()).isZero(); assertThat(p.supplements()).isZero();
        assertThat(p.analyses()).isEqualTo(2); assertThat(p.planRevision()).isEqualTo(1);
        var projected = TemplateTaskProgress.snapshot(p, task.id(), source.toString(), "COMPLETED");
        assertThat(projected.snapshot().lightweight()).isTrue(); assertThat(projected.steps()).hasSize(3);
        assertThat(projected.steps()).allMatch(s -> s.state().equals("COMPLETE"));
        assertThat(mapper.listTaskArtifacts(task.id())).anyMatch(a -> a.content().contains("无问题结论未经独立复核"));
        assertThat(mapper.listTaskArtifacts(task.id())).anyMatch(a -> a.content().contains("已记录排除，未调用模型"));
    }
    @Test void lightweightIncrementalReviewOnlyRechecksFindingFilesAndRejectsExpansion() throws Exception {
        commit("caller.java", "class Caller {}\n", "2026-09-03T00:00:00Z");
        commit("main.java", "class Main { int value() { return 0; } }\n", "2026-09-04T00:00:00Z");
        lightweight = true; findings = true; var task = create(Mode.DATE_INCREMENTAL); start(task); run(task);
        assertThat(states.task(task.id()).state()).isEqualTo("COMPLETED");
        assertThat(mapper.listSessions(task.id())).hasSize(2);
        var rows = mapper.listAttempts(task.id()).stream().flatMap(a -> templates.batches(task.id(), a.id()).stream()).toList();
        assertThat(rows).extracting(TemplateTaskBatchRow::purpose).containsExactlyInAnyOrder("SNAPSHOT_ANALYSIS", "SNAPSHOT_REVIEW");
        var review = rows.stream().filter(b -> b.purpose().equals("SNAPSHOT_REVIEW")).findFirst().orElseThrow();
        assertThat(json.readValue(review.inputJson(), TemplateBatchExecution.Input.class).snapshot().units()).hasSize(1);
        assertThat(mapper.listTaskArtifacts(task.id())).anyMatch(a -> a.content().contains("复核支持问题 1 项；待确认问题 1 项"));
        assertThat(mapper.listTaskArtifacts(task.id())).anyMatch(a -> a.content().contains("代码最后修改记录") && a.content().contains("Alice") && a.content().contains("问题引入者：未确定"));
        assertThat(mapper.findActiveWorkspaceLeaseByHolder(task.id())).isEmpty();
    }
    @Test void lightweightRetryKeepsSuccessfulBatchesAndDoesNotAddReviewsForNoFindings() throws Exception {
        lightweight = true; failOne = true; var task = create(Mode.FULL); start(task); run(task);
        assertThat(states.task(task.id()).state()).isEqualTo("COMPLETED");
        assertThat(mapper.listSessions(task.id())).hasSize(2);
        assertThat(progress.snapshotProgress(task.id()).orElseThrow().reviews()).isZero();
    }
    @Test void supplementsRelationsAndIndependentFindingDecisionsKeepFrozenEvidenceAndAcceptedSiblings() throws Exception {
        commit("caller.java", "class Caller { int get() { return new Main().value(); } }\n", "2026-09-02T00:00:00Z");
        commit("policy.md", "value must return one\n", "2026-09-03T00:00:00Z");
        var task = create(Mode.FULL); start(task); supplement = true; findings = true; run(task);
        assertThat(states.task(task.id()).state()).isEqualTo("COMPLETED");
        assertThat(progress.snapshotProgress(task.id()).orElseThrow().supplements()).isPositive();
        assertThat(progress.snapshotProgress(task.id()).orElseThrow().planRevision()).isGreaterThan(2);
        assertThat(mapper.listTaskArtifacts(task.id())).anyMatch(a -> a.content().contains("复核支持") && a.content().contains("目标代码证据"));
        var frozen = snapshots.snapshot(task.id());
        commit("main.java", "class Main { int value() { return 999; } }\n", "2026-09-12T00:00:00Z");
        driver.executeCheckpoint(task.id());
        assertThat(snapshots.snapshot(task.id())).isEqualTo(frozen);
        assertThat(mapper.listAttempts(task.id()).stream().flatMap(a -> templates.batches(task.id(), a.id()).stream())).allMatch(b -> b.state().equals("VALIDATED"));
    }
    @Test void finalReintroductionUsesTargetTreeInsteadOfTransientPatches() throws Exception {
        commit("main.java", "class Main { int value() { return 0; } }\n", "2026-09-02T00:00:00Z");
        commit("main.java", "class Main { int value() { return 1; } }\n", "2026-09-03T00:00:00Z");
        String target = commit("main.java", "class Main { int value() { return 0; } }\n", "2026-09-04T00:00:00Z");
        var task = create(Mode.DATE_INCREMENTAL); start(task);
        for (int i = 0; i < 3; i++) driver.executeCheckpoint(task.id());
        var frozen = snapshots.snapshot(task.id());
        assertThat(frozen.targetSha()).isEqualTo(target);
        assertThat(frozen.units()).anyMatch(u -> u.excerpt().contains("+class Main { int value() { return 0; } }"));
        commit("main.java", "later\n", "2026-09-08T00:00:00Z");
        assertThat(snapshots.snapshot(task.id())).isEqualTo(frozen);
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void frozenManifestRecordsProtectedSymlinkAndDeletionEvidence(boolean light) throws Exception {
        lightweight = light;
        commit(".env", "TOKEN=fixture-only\n", "2026-09-02T00:00:00Z");
        Files.createSymbolicLink(source.resolve("alias.java"), Path.of("main.java")); git.read(source,"add","--","alias.java");
        git.read(source,"rm","--","main.java");
        commit("caller.java", "new Main();\n", "2026-09-03T00:00:00Z");
        var task=create(Mode.DATE_INCREMENTAL); start(task);
        for(int i=0;i<3;i++) driver.executeCheckpoint(task.id());
        var frozen=snapshots.snapshot(task.id());
        assertThat(frozen.files()).filteredOn(f -> f.path().equals(".env") || f.path().equals("alias.java")).allMatch(f -> f.limitation()!=null);
        assertThat(frozen.units()).anyMatch(u -> u.path().equals("main.java") && u.change().equals("D"));
        assertThat(frozen.units()).noneMatch(u -> u.excerpt().contains("TOKEN=fixture"));
    }
    @Test void failedBatchRetriesWithNewGenerationAndPreservesSuccessfulSiblings() throws Exception {
        commit("caller.java", "class Caller {}\n", "2026-09-03T00:00:00Z");
        var task=create(Mode.FULL); start(task); failOne=true; run(task);
        assertThat(states.task(task.id()).state()).isEqualTo("COMPLETED");
        var all=mapper.listAttempts(task.id()).stream().flatMap(a->templates.batches(task.id(),a.id()).stream()).toList();
        assertThat(all).filteredOn(b->b.generation()==1).hasSize(1);
        assertThat(all).filteredOn(b->b.state().equals("FAILED")).hasSize(1);
        assertThat(mapper.listAttempts(task.id())).hasSize(4);
    }
    @Test void legacyV2StillRequiresReadsAndRunsAfterUpgrade() {
        lightweight = true; legacyV2 = true;
        var task = create(Mode.FULL); start(task); run(task);
        assertThat(states.task(task.id()).state()).isEqualTo("COMPLETED");
        assertThat(snapshots.snapshot(task.id()).units()).allMatch(u -> u.initialEvidence() == null);
    }
    @Test void compactReusesOnlyIdenticalScopeModelAndCompleteDependencyTrees() throws Exception {
        lightweight = true;
        var first = create(Mode.FULL); start(first); run(first);
        assertThat(mapper.listSessions(first.id())).hasSize(1);
        git.read(source, "-c", "core.hooksPath=/dev/null", "commit", "--allow-empty", "-m", "new week same tree");
        var second = create(Mode.FULL); start(second); run(second);
        assertThat(mapper.listSessions(second.id())).isEmpty();
        assertThat(snapshotRecords.reuses(second.id())).hasSize(1);
        assertThat(partialReports.read(second.id()).content()).contains(first.id(), "复用历史有效分析 1 批");
        commit("caller.java", "class Caller {}\n", "2026-09-11T00:00:00Z");
        var changed = create(Mode.FULL); start(changed); run(changed);
        assertThat(mapper.listSessions(changed.id())).hasSize(1);
        assertThat(snapshotRecords.reuses(changed.id())).isEmpty();
        properties.getOpenCode().setModel("fake/other");
        var otherModel = create(Mode.FULL); start(otherModel); run(otherModel);
        assertThat(mapper.listSessions(otherModel.id())).hasSize(1);
    }
    @Test void partialReportShowsPendingBeforeCompletionWithoutAnyModelCall() {
        lightweight = true; var task = create(Mode.FULL); start(task);
        for (int i = 0; i < 10 && snapshots.require(task.id()).snapshotJson() == null; i++) driver.executeCheckpoint(task.id());
        var report = partialReports.read(task.id());
        assertThat(report.pendingUnits()).isEqualTo(1); assertThat(report.analyzedUnits()).isZero();
        assertThat(report.content()).contains("非完整报告", "尚未完成分析");
        assertThat(mapper.listSessions(task.id())).isEmpty();
    }
    @Test void cancelledTaskKeepsAnalyzedCandidateEvidenceWithoutClaimingIndependentSupport() {
        lightweight = true; findings = true; stopAfterAnalysis = true;
        var task = create(Mode.FULL); start(task); run(task);
        taskService.cancel(task.id());
        var report = partialReports.read(task.id());
        assertThat(report.analyzedUnits()).isEqualTo(1);
        assertThat(report.content()).contains("CANCELLED", "尚未独立复核（仅候选）", "class Main", "main.java", "代码最后修改记录", "Alice", "问题引入者：未确定");
        int sessions = mapper.listSessions(task.id()).size();
        assertThat(partialReports.read(task.id()).content()).contains("Alice");
        assertThat(mapper.listSessions(task.id())).hasSize(sessions);
        assertThat(report.content()).doesNotContain("独立复核支持");
        assertThat(mapper.listTaskArtifacts(task.id())).noneMatch(a -> a.kind().equals("TEMPLATE_REPORT"));
    }
    @Test void authorGitReadsSuspendTheReportDatabaseTransaction() {
        lightweight = true; var task = create(Mode.FULL); start(task);
        for (int i = 0; i < 10 && snapshots.require(task.id()).snapshotJson() == null; i++) driver.executeCheckpoint(task.id());
        var snapshot = snapshots.snapshot(task.id()); var file = snapshot.files().getFirst();
        var ref = new Reference(file.version(), file.path(), file.blob(), 1, 1, "class Main");
        org.mockito.Mockito.doAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return call.callRealMethod();
        }).when(git).run(org.mockito.ArgumentMatchers.any(Path.class), org.mockito.ArgumentMatchers.any(Duration.class), org.mockito.ArgumentMatchers.anyList());
        var result = new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .execute(status -> authors.render(task.id(), snapshot, List.of(ref)));
        assertThat(result).contains("Alice").doesNotContain("作者追溯不可用");
    }
    @Test void compactReadBoundarySurvivesReplayAndRejectsUnrelatedResults() {
        lightweight = true; var task = create(Mode.FULL); start(task);
        TemplateTaskBatchRow running = null;
        for (int i = 0; i < 20 && running == null; i++) {
            driver.executeCheckpoint(task.id());
            running = snapshotRecords.currentBatches(task.id()).stream().filter(r -> r.state().equals("RUNNING")).findFirst().orElse(null);
        }
        final var row = Objects.requireNonNull(running);
        var f = snapshots.snapshot(task.id()).files().getFirst();
        for (int i = 0; i < 12; i++) reads.search(row.id(), f.version(), f.path(), f.blob(), "query" + i, 0);
        reads.search(row.id(), f.version(), f.path(), f.blob(), "query0", 0);
        assertThatThrownBy(() -> reads.search(row.id(), f.version(), f.path(), f.blob(), "more", 0)).hasMessageContaining("12 次关联读取");
        assertThatThrownBy(() -> reads.results(row.id(), 0, 10)).hasMessageContaining("不遍历");
        var input = json.readValue(row.inputJson(), TemplateBatchExecution.Input.class).snapshot();
        reads.evidence(row.id(), input.units().getFirst().initialEvidence(), true);
        assertThat(snapshotRecords.readCount(row.id())).isZero();
        assertThat(protocol.prompt(row)).contains("无需重复读取初始证据", "最多 160 字");
    }
    private void run(TaskRow task) {
        Set<String> completed=new HashSet<>();
        for(int turn=0;turn<800&&!states.task(task.id()).state().equals("COMPLETED");turn++) {
            driver.executeCheckpoint(task.id());
            if (stopAfterAnalysis && snapshotRecords.currentBatches(task.id()).stream().anyMatch(r -> r.purpose().equals("SNAPSHOT_ANALYSIS") && r.state().equals("VALIDATED"))) return;
            if (states.task(task.id()).state().equals("WAITING_INPUT") && mapper.listErrors(task.id()).stream().anyMatch(e -> e.code().equals("TEMPLATE_BATCHES_FAILED"))) {
                assertThat(progress.retrySelectionReady(task.id())).isTrue(); states.resumeBatch(task.id());
                for (var a : mapper.listAttempts(task.id())) for (var failed : templates.batches(task.id(),a.id()))
                    if (failed.state().equals("FAILED")) batchStore.retry(failed,failed.version(),true);
            }
            assertThat(states.task(task.id()).state()).as(mapper.listErrors(task.id()).toString()).isNotEqualTo("WAITING_INPUT");
            for(var attempt:mapper.listAttempts(task.id()))for(var row:templates.batches(task.id(),attempt.id())) {
                if(!row.state().equals("RUNNING")||completed.contains(row.id()))continue;
                var input=json.readValue(row.inputJson(),TemplateBatchExecution.Input.class).snapshot();Object candidate;
                if (failOne && input.phase().equals("ANALYSIS")) {
                    fake.setSessionState(mapper.findSession(row.sessionId()).orElseThrow().externalSessionId(),"FAILED");
                    failOne=false; completed.add(row.id()); continue;
                }
                if(input.phase().equals("PLAN")) {
                    candidate=new Plan(input.units().stream().map(u->new Group(u.id(),"功能检查","核对行为与调用约定",List.of(u.id()),List.of(u.path()))).toList(),List.of());
                    assertThat(submissions.submit(row.id(),"bad",0,"{\"groups\":[],\"relations\":[]}")).contains("REJECTED");
                } else if(input.phase().equals("LINKS")) candidate=new Plan(List.of(), supplement && input.groups().size()>1
                        ? List.of(new Relation("call",input.groups().getFirst().key(),input.groups().getLast().key(),"核对调用接口与配置")) : List.of());
                else {
                    List<Reference> references=new ArrayList<>();var snapshot=snapshots.snapshot(task.id());
                    if (input.compact()) input.units().forEach(u -> references.addAll(u.initialEvidence().stream().filter(r -> r.version().equals(snapshot.targetSha())).toList()));
                    else for(var unit:input.units()) {
                        var file=snapshot.files().stream().filter(f->f.version().equals(snapshot.targetSha())&&f.path().equals(unit.path())).findFirst().orElseThrow();
                        var read=reads.read(row.id(),file.version(),file.path(),file.blob(),1,100);
                        references.add((Reference)read.get("reference"));
                    }
                    assertThatThrownBy(() -> reads.evidence(row.id(), List.of(new Reference(snapshot.targetSha(),references.getFirst().path(),references.getFirst().blob(),99,100,"伪造原文")),true)).isInstanceOf(BadRequestException.class);
                    if(input.phase().equals("ANALYSIS")) {
                        if (input.lightweight()) {
                            var extra = new Group(input.units().getFirst().id(), "补充", "不应创建", List.of(input.units().getFirst().id()), List.of(input.units().getFirst().path()));
                            assertThat(submissions.submit(row.id(), "expansion", receipts.revision(row.id()), json.writeValueAsString(new Analysis(List.of(), List.of(), List.of(extra), List.of())))).contains("REJECTED");
                        }
                        List<Group> more=List.of();
                        var contexts=input.groups().stream().flatMap(g->g.contextPaths().stream()).toList();
                        var path=snapshot.files().stream().map(SnapshotReview.File::path).filter(f->!contexts.contains(f)).findFirst();
                        if(supplement && path.isPresent() && !row.purpose().equals("SNAPSHOT_SUPPLEMENT"))
                            more=List.of(new Group(input.units().getFirst().id(),"补充配置检查","检查新的配置关联",List.of(input.units().getFirst().id()),List.of(path.get())));
                        candidate=new Analysis(input.units().stream().map(u->new Coverage(u.id(),"已核对目标代码",input.compact() ? List.of() : references.stream().filter(r -> r.path().equals(u.path())).limit(1).toList(),List.of())).toList(),
                                findings ? List.of("finding", "guarded", "pending", "duplicate").stream().map(key -> new Finding(key,TemplateAnalysis.Severity.HIGH,"标注样本 " + key,"标注触发条件","目标代码证据","修复建议",Attribution.UNDETERMINED,input.lightweight() ? List.of(references.getFirst()) : references)).toList() : List.of(),more,input.compact() ? List.of() : List.of("静态审查未运行测试"));
                    } else {
                        var origin=json.readValue(templates.findBatch(input.analysisBatchId()).orElseThrow().outputJson(),Analysis.class);
                        candidate=new Review(input.units().stream().map(Unit::id).toList(),origin.findings().stream().map(f->new Decision(f.key(), switch(f.key()) { case "guarded" -> Verdict.DISMISSED; case "pending" -> Verdict.UNDETERMINED; case "duplicate" -> Verdict.DUPLICATE; default -> Verdict.SUPPORTED; },"独立读取目标代码证据",f.key().equals("duplicate") ? "finding" : null,references)).toList(),references,"独立读取后给出样本判定",List.of("未运行测试"));
                    }
                    assertThatThrownBy(()->reads.read(row.id(),"wrong", "main.java","wrong",1,10)).isInstanceOf(BadRequestException.class);
                }
                String result=submissions.submit(row.id(),"valid",receipts.revision(row.id()),json.writeValueAsString(candidate));
                assertThat(result).contains("ACCEPTED");
                fake.setSessionState(mapper.findSession(row.sessionId()).orElseThrow().externalSessionId(),"COMPLETED");completed.add(row.id());
            }
        }
    }
}
