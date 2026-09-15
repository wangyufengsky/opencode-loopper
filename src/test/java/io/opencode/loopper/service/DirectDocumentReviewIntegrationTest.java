package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.*;
import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.*;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.runtime.*;
import io.opencode.loopper.template.*;
import io.opencode.loopper.template.RequirementCodeAssessment.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipInputStream;
import java.io.ByteArrayInputStream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = LoopperApplication.class, properties = {
        "loopper.opencode.mode=fake", "loopper.monitor-delay=1h", "loopper.designer-monitor-delay=1h"})
class DirectDocumentReviewIntegrationTest {
    @Autowired org.springframework.context.ApplicationContext applicationContext;
    @Autowired Flyway flyway;
    @Autowired DocumentTemplateService service;
    @Autowired DocumentTemplateAdmission admission;
    @Autowired DocumentTemplateMapper runs;
    @Autowired DocumentTemplateModelMapper models;
    @Autowired DocumentRequirementWorkflow requirements;
    @Autowired DirectDocumentReviewWorkflow assessments;
    @Autowired io.opencode.loopper.api.DocumentSourceResources resources;
    @Autowired DocumentModelExecution execution;
    @Autowired MachineCandidateSubmission submissions;
    @Autowired DocumentFrozenReadService reads;
    @Autowired DocumentReviewContextService context;
    @Autowired DocumentRequirementReportService reports;
    @Autowired ProjectService projects;
    @Autowired GitEvidenceProcess git;
    @Autowired LoopperProperties properties;
    @Autowired OpenCodeClient client;
    @Autowired InternalMcpRuntimeAccess access;
    @Autowired ObjectMapper json;
    @TempDir Path temporary;
    private FakeOpenCodeClient fake;
    private DocumentTemplateRunRow run;
    private DocumentTemplateService.Contract contract;
    private Path source;
    @BeforeEach void prepare() throws Exception {
        flyway.clean(); flyway.migrate(); fake = (FakeOpenCodeClient) client; fake.reset();
        var credentials = new InternalMcpCredentialProvider(() -> 18083).issue();
        access.activate(credentials); access.connected(credentials.generation()); fake.setManagedRuntime(credentials.generation(), credentials.serverName());
        for (var kind : MachineCandidateKind.values()) if (DocumentTemplateProfiles.supports(kind)) fake.holdProfileOpen(DocumentTemplateProfiles.profile(kind), true);
        source = Files.createDirectory(temporary.resolve("project"));
        git.read(source, "init", "-b", "main", "--template=");
        Files.writeString(source.resolve("PaymentService.java"), "class PaymentService {\n  public void pay() { charge(); }\n}\n");
        Files.writeString(source.resolve("build.sh"), "#!/bin/sh\ntouch must-not-execute\n");
        git.read(source, "add", "."); git.read(source, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-m", "missing permission fixture");
        String project = projects.create("权限评审", source.toString(), "test").id(); properties.getOpenCode().setModel("fake/test-model");
        run = service.create(new DocumentTemplateService.Request(UUID.randomUUID().toString(), "REQUIREMENT_CODE_REVIEW", io.opencode.loopper.template.DocumentTemplateDefinition.VERSION, project,
                "local:refs/heads/main"), List.of(new DocumentTemplateStorage.Incoming("权限.md", "# 付款\n付款入口必须检查权限。".getBytes(StandardCharsets.UTF_8))));
        contract = json.readValue(run.contractJson(), DocumentTemplateService.Contract.class);
    }
    @Test void confirmedDefectCompletesReviewWithRequirementEvidenceButNeverRunsProjectOrMarksSatisfied() throws Exception {
        assertThat(current().state()).isEqualTo("ASSESSING");
        assertThat(current().sourceRevision()).isEqualTo(1);
        assertThat(current().requirementRevision()).isZero();
        var assessment = awaitRole("DOCUMENT_CODE_ASSESSMENT_V2");
        var input = json.readValue(assessment.inputJson(), DocumentModelInput.class);
        var refSource = input.sections().getFirst();
        var sourceRef = new DirectDocumentAssessment.Source(refSource.fileId(), refSource.section());
        resources.read("loopper-document://review/" + assessment.id() + "/" + refSource.fileId() + "/" + refSource.section());
        var file = reads.files(assessment.id(), "PaymentService", "", 50).items().getFirst();
        var window = reads.read(assessment.id(), file.path(), file.blobSha(), 1, 100);
        assertThat(window.content()).contains("charge()");
        var ref = new CodeReference(file.path(), file.blobSha(), 2, 2, "public void pay() { charge(); }");
        String sha = json.readValue(current().snapshotJson(), DocumentCodeSnapshotStore.Snapshot.class).sha();
        var result = new Candidate(sha, List.of(new Item("RQ-1", Conclusion.INCORRECT,
                "付款入口直接执行 charge，没有检查权限", List.of(ref), List.of(file.path()), null,
                "夹具没有权限测试源码；本次未执行测试", List.of())),
                List.of(new Finding("F-1", FindingKind.DEFECT, Severity.HIGH, "付款入口缺少权限校验", "调用付款入口",
                        "无权限调用可能触发付款", "在付款前校验操作权限", List.of("RQ-1"), List.of(ref), "payment-permission")), List.of());
        var direct = new DirectDocumentAssessment.Candidate(sha, List.of(new DirectDocumentAssessment.Entry(
                "付款权限", "付款入口必须检查权限", List.of(sourceRef), List.of(), result.items().getFirst())),
                result.findings(), List.of(), List.of());
        var work = guide.work(assessment.id(), 0, 1);
        assertThat(work.get("assignedSectionTotal")).isEqualTo(1);
        assertThat(json.writeValueAsString(work)).contains("alreadyRead\":true", "付款");
        var bound = new DirectDocumentAssessment.Candidate(null, direct.entries(), direct.findings(), direct.skippedSections(), direct.limitations());
        var before = submissions.find(assessment.id()).orElseThrow();
        var wrongSnapshot = new DirectDocumentAssessment.Candidate("f".repeat(40), direct.entries(), direct.findings(), List.of(), List.of());
        var wrong = guide.check(assessment.id(), json.convertValue(wrongSnapshot, new tools.jackson.core.type.TypeReference<java.util.Map<String,Object>>() { }));
        assertThat(wrong.get("valid")).isEqualTo(false);
        assertThat(json.writeValueAsString(wrong)).contains("/candidate/snapshotSha");
        var omitted = new DirectDocumentAssessment.Candidate(null, List.of(), List.of(), List.of(), List.of());
        var omission = guide.check(assessment.id(), json.convertValue(omitted, new tools.jackson.core.type.TypeReference<java.util.Map<String,Object>>() { }));
        assertThat(omission.get("valid")).isEqualTo(false);
        assertThat(json.writeValueAsString(omission)).contains("/candidate/entries", refSource.fileId());
        var checkOnly = guide.check(assessment.id(), json.convertValue(bound, new tools.jackson.core.type.TypeReference<java.util.Map<String,Object>>() { }));
        assertThat(checkOnly.get("valid")).isEqualTo(true);
        assertThat(checkOnly.get("accepted")).isEqualTo(false);
        assertThat(submissions.find(assessment.id()).orElseThrow().version()).isEqualTo(before.version());
        assertThat(models.find(assessment.id()).orElseThrow().outputJson()).isNull();
        complete(assessment, bound);
        assertThat(json.readValue(models.find(assessment.id()).orElseThrow().outputJson(), DirectDocumentAssessment.Candidate.class).snapshotSha()).isEqualTo(sha);
        var check = awaitRole("DOCUMENT_CODE_REVIEW_V2");
        var crossBatch = context.list(check.id(), -1, 50).items().getFirst();
        assertThat(((DirectDocumentAssessment.Candidate) context.read(check.id(), crossBatch.ordinal(), crossBatch.sha256())).entries()).hasSize(1);
        var approved = new DirectDocumentAssessment.Review(sha, true, List.of("RQ-1"), List.of("F-1"), List.of(sourceRef), List.of());
        assertThat(submit(check, approved).outcome()).isEqualTo(MachineCandidateOutcome.REJECTED);
        resources.read("loopper-document://review/" + check.id() + "/" + refSource.fileId() + "/" + refSource.section());
        reads.read(check.id(), file.path(), file.blobSha(), 1, 100); complete(check, approved);
        boolean done = false;
        for (int i = 0; i < 10 && !done; i++) done = assessments.advance(current(), contract);
        assertThat(done).isTrue();
        assertThat(current().sourceRevision()).isEqualTo(1);
        assertThat(current().requirementRevision()).isEqualTo(1);
        assertThat(models.exact(run.id(), "DOCUMENT_REQUIREMENTS_V1", 0, 0)).isEmpty();
        assertThat(models.exact(run.id(), "DOCUMENT_REQUIREMENT_REVIEW_V1", 0, 0)).isEmpty();
        admission.transition(current(), DocumentTemplateState.REPORTING, LifecycleEvent.RENDER_REQUIREMENT_REPORT, null, null);
        reports.review(current()); reports.review(current());
        admission.transition(current(), DocumentTemplateState.COMPLETED, LifecycleEvent.COMPLETE, null, null);
        var list = reports.list(run.id(), "", 100).items();
        var matrix = list.stream().filter(item -> item.name().equals("matrix.json")).findFirst().orElseThrow();
        var body = json.readTree(reports.read(run.id(), matrix.id()).content());
        assertThat(body.path("reviewCompleted").asBoolean()).isTrue(); assertThat(body.path("allRequirementsSatisfied").asBoolean()).isFalse();
        assertThat(body.path("testExecution").asText()).isEqualTo("NOT_RUN_STATIC_REVIEW");
        assertThatThrownBy(() -> reports.read(UUID.randomUUID().toString(), matrix.id())).isInstanceOf(NotFoundException.class);
        var names = new ArrayList<String>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(reports.download(run.id()).bytes()), StandardCharsets.UTF_8)) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) names.add(entry.getName());
        }
        assertThat(names).anyMatch(name -> name.endsWith("requirements/RQ-1.md")).anyMatch(name -> name.endsWith("issues/F-1.md"));
        assertThat(Files.exists(source.resolve("must-not-execute"))).isFalse();
        assertThat(git.read(source, "status", "--porcelain")).isEmpty(); assertThat(fake.createSessionCalls()).isZero();
    }
    @Test void eightyBatchesRetainFourSlotsAndRefillOutOfOrderBeforeIndependentReview() {
        byte[] body = "x".repeat(1_920_000).getBytes(StandardCharsets.UTF_8);
        run = service.create(new DocumentTemplateService.Request(UUID.randomUUID().toString(), "REQUIREMENT_CODE_REVIEW", io.opencode.loopper.template.DocumentTemplateDefinition.VERSION,
                run.projectId(), "local:refs/heads/main"), List.of(new DocumentTemplateStorage.Incoming("part-a.md", body),
                new DocumentTemplateStorage.Incoming("part-b.md", body)));
        contract = json.readValue(run.contractJson(), DocumentTemplateService.Contract.class);
        assertThat(contract.analysisConcurrency()).isEqualTo(4);
        properties.setTemplateAnalysisConcurrency(1);
        try {
            assertThat(assessments.plan(run)).hasSize(80);
            var finished = new HashSet<String>(); int peak = 0;
            for (int tick = 0; tick < 700 && finished.size() < 80; tick++) {
                assessments.advance(current(), contract);
                var active = models.active(run.id());
                assertThat(active).hasSizeLessThanOrEqualTo(4); peak = Math.max(peak, active.size());
                assertThat(models.exact(run.id(), "DOCUMENT_CODE_REVIEW_V2", 0, 0)).isEmpty();
                var next = active.stream().filter(row -> row.state().equals("RUNNING"))
                        .max(Comparator.comparingInt(DocumentTemplateModelRow::ordinal)).orElse(null);
                if (next == null) continue;
                assertThat(finished.add(next.id())).isTrue();
                var input = json.readValue(next.inputJson(), DocumentModelInput.class);
                var refs = new ArrayList<DirectDocumentAssessment.Source>();
                for (var section : input.sections()) {
                    reads.section(next.id(), section.fileId(), section.section(), section.sha256());
                    refs.add(new DirectDocumentAssessment.Source(section.fileId(), section.section()));
                }
                var item = new Item("RQ-" + (next.ordinal() * 256 + 1), Conclusion.UNDETERMINED,
                        "夹具未提供可判断的业务行为", List.of(), List.of(), null, "本次未执行测试", List.of());
                complete(next, new DirectDocumentAssessment.Candidate(input.snapshotSha(), List.of(new DirectDocumentAssessment.Entry(
                        "未明确的业务规则", "正文信息不足", refs, List.of("需要说明业务行为"), item)), List.of(), List.of(), List.of()));
                if (finished.size() < 80) assertThat(current().state()).isEqualTo("ASSESSING");
            }
            assertThat(finished).hasSize(80); assertThat(peak).isEqualTo(4);
            assessments.advance(current(), contract);
            assertThat(current().state()).isEqualTo("VERIFYING");
            assessments.advance(current(), contract);
            assertThat(models.active(run.id())).hasSize(4).allSatisfy(row -> assertThat(row.candidateKind()).isEqualTo("DOCUMENT_CODE_REVIEW_V2"));
            assertThat(Files.exists(source.resolve("must-not-execute"))).isFalse();
        } finally { properties.setTemplateAnalysisConcurrency(4); }
    }
    @Autowired DocumentReviewGuideService guide;
    @Autowired DocumentModelStore modelStore;
    @Test void failedDirectBatchExhaustsAutomaticAttemptsAndManualRetryKeepsFrozenInput() {
        var model = awaitRole("DOCUMENT_CODE_ASSESSMENT_V2");
        String originalInput = model.inputSha256();
        for (int i = 0; i < 3; i++) {
            fake.setSessionState(model.externalSessionId(), "COMPLETED");
            var failed = execution.advance(model.id(), contract);
            assertThat(failed.state()).isEqualTo("FAILED");
            assertThat(current().state()).isEqualTo("ASSESSING");
            var next = modelStore.retry(failed.id(), failed.version(), false, contract);
            if (i < 2) {
                for (int tick = 0; tick < 4; tick++) next = execution.advance(next.id(), contract);
                model = next;
            } else {
                assertThat(next.id()).isEqualTo(failed.id());
                var manual = modelStore.retry(failed.id(), failed.version(), true, contract);
                assertThat(manual.attempt()).isEqualTo(3);
                assertThat(manual.inputSha256()).isEqualTo(originalInput);
                assertThat(modelStore.retry(failed.id(), failed.version(), true, contract).id()).isEqualTo(manual.id());
                assertThatThrownBy(() -> guide.work(failed.id(), 0, 50)).isInstanceOf(ConflictException.class);
            }
        }
    }

    @Autowired DocumentBatchRetryService batchRetries;
    @Test void directFailureWaitsForSelectionInsteadOfAutomaticallyRetrying() {
        var model = awaitRole("DOCUMENT_CODE_ASSESSMENT_V2");
        fake.setSessionState(model.externalSessionId(), "COMPLETED");
        var failed = execution.advance(model.id(), contract);
        for (int tick = 0; tick < 4; tick++) assessments.advance(current(), contract);
        assertThat(models.exact(run.id(), model.candidateKind(), 0, 0).orElseThrow().id()).isEqualTo(failed.id());
        assertThat(current().state()).isEqualTo("ASSESSING");
        assertThat(models.retrySelectionReady(run.id())).isTrue();
        assertThat(batchRetries.list(run.id(), null, 50).facets()).containsEntry("retrySelectionReady", 1L);
        var next = batchRetries.retrySelected(run.id(), new BatchRetrySelection(List.of(
                new BatchRetrySelection.Item(failed.id(), failed.version())))).getFirst();
        assertThat(next.attempt()).isEqualTo(1);
        assertThat(next.inputSha256()).isEqualTo(failed.inputSha256());
        assertThat(models.retrySelectionReady(run.id())).isFalse();
    }

    @Test void emptyWindowWithUncreatedBatchesDoesNotOpenManualSelection() {
        properties.setTemplateAnalysisConcurrency(1);
        try {
            run = service.create(new DocumentTemplateService.Request(UUID.randomUUID().toString(), "REQUIREMENT_CODE_REVIEW",
                    io.opencode.loopper.template.DocumentTemplateDefinition.VERSION, run.projectId(), "local:refs/heads/main"),
                    List.of(new DocumentTemplateStorage.Incoming("five-batches.md", "x".repeat(240_000).getBytes(StandardCharsets.UTF_8))));
            contract = json.readValue(run.contractJson(), DocumentTemplateService.Contract.class);
        } finally { properties.setTemplateAnalysisConcurrency(4); }
        assertThat(assessments.plan(run)).hasSize(5);
        var first = awaitRole("DOCUMENT_CODE_ASSESSMENT_V2");
        fake.setSessionState(first.externalSessionId(), "FAILED");
        var failed = execution.advance(first.id(), contract);
        assertThat(models.active(run.id())).isEmpty();
        assertThat(models.retrySelectionReady(run.id())).isFalse();
        assertThat(batchRetries.list(run.id(), null, 50).facets()).containsEntry("retrySelectionReady", 0L);
        assertThatThrownBy(() -> batchRetries.retry(run.id(), failed.id(), failed.version()))
                .isInstanceOf(ConflictException.class).hasMessageContaining("后续批次");
        assessments.advance(current(), contract);
        assertThat(models.exact(run.id(), first.candidateKind(), 0, 0).orElseThrow().id()).isEqualTo(failed.id());
        assertThat(models.active(run.id())).hasSize(1).allMatch(row -> row.ordinal() == 1);
    }

    private DocumentTemplateRunRow current() { return runs.find(run.id()).orElseThrow(); }
    private DocumentTemplateModelRow awaitRole(String kind) {
        for (int i = 0; i < 24; i++) {
            var row = current();
            if (row.state().equals("ANALYZING") || row.state().equals("REVIEWING")) requirements.advance(row, contract);
            else assessments.advance(row, contract);
            var model = models.exact(run.id(), kind, 0, 0);
            if (model.isPresent() && model.get().state().equals("RUNNING")) return model.get();
        }
        throw new AssertionError("Role did not start: " + kind);
    }
    private void complete(DocumentTemplateModelRow model, Object result) {
        var response = submit(model, result); assertThat(response.outcome()).as("%s", response.problems()).isEqualTo(MachineCandidateOutcome.ACCEPTED);
        fake.setSessionState(model.externalSessionId(), "COMPLETED"); execution.advance(model.id(), contract);
    }
    private MachineCandidateSubmission.SubmissionResult submit(DocumentTemplateModelRow model, Object result) {
        var revision = submissions.find(model.id()).orElseThrow().version();
        return submissions.submit(new MachineCandidateSubmission.SubmitCommand(model.id(), "fixture-" + revision,
                json.writeValueAsString(result), revision, MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP,
                MachineCandidateSubmission.SubmissionSchema.ROLE_SPECIFIC_V2));
    }
}
