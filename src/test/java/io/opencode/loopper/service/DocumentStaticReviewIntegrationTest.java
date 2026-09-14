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
class DocumentStaticReviewIntegrationTest {
    @Autowired org.springframework.context.ApplicationContext applicationContext;
    @Autowired Flyway flyway;
    @Autowired DocumentTemplateService service;
    @Autowired DocumentTemplateAdmission admission;
    @Autowired DocumentTemplateMapper runs;
    @Autowired DocumentTemplateModelMapper models;
    @Autowired DocumentRequirementWorkflow requirements;
    @Autowired DocumentAssessmentWorkflow assessments;
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
        run = LegacyDocumentFixture.create(applicationContext, new DocumentTemplateService.Request(UUID.randomUUID().toString(), "REQUIREMENT_CODE_REVIEW", "1", project,
                "local:refs/heads/main"), List.of(new DocumentTemplateStorage.Incoming("权限.md", "# 付款\n付款入口必须检查权限。".getBytes(StandardCharsets.UTF_8))));
        contract = json.readValue(run.contractJson(), DocumentTemplateService.Contract.class);
    }
    @Test void confirmedDefectCompletesReviewWithRequirementEvidenceButNeverRunsProjectOrMarksSatisfied() throws Exception {
        var extraction = awaitRole("DOCUMENT_REQUIREMENTS_V1");
        var input = json.readValue(extraction.inputJson(), DocumentModelInput.class); var sourceRef = input.sections().getFirst();
        var requirement = new DocumentRequirements.Requirement("RQ-1", "付款权限", "付款", DocumentRequirements.Kind.PERMISSION,
                "付款入口必须检查权限", List.of(new DocumentRequirements.Source(sourceRef.fileId(), sourceRef.section(), "付款入口必须检查权限")),
                List.of("无权限时不能付款"), List.of());
        var candidate = new DocumentRequirements.Candidate(List.of(requirement), List.of(new DocumentRequirements.Coverage(
                sourceRef.fileId(), sourceRef.section(), DocumentRequirements.Disposition.REQUIREMENT, List.of("RQ-1"), "付款入口权限规则")));
        complete(extraction, candidate);
        var review = awaitRole("DOCUMENT_REQUIREMENT_REVIEW_V1");
        complete(review, new DocumentRequirements.Review(true, List.of("RQ-1"), candidate.coverage(), List.of()));
        for (int i = 0; i < 10 && current().requirementRevision() == 0; i++) requirements.advance(current(), contract);
        assertThat(current().requirementRevision()).isEqualTo(1);
        admission.transition(current(), DocumentTemplateState.ASSESSING, LifecycleEvent.ASSESS_REQUIREMENT_CODE, null, null);
        var assessment = awaitRole("REQUIREMENT_CODE_ASSESSMENT_V1");
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
        complete(assessment, result);
        var check = awaitRole("REQUIREMENT_ASSESSMENT_REVIEW_V1");
        var crossBatch = context.list(check.id(), -1, 50).items().getFirst();
        assertThat(((Candidate) context.read(check.id(), crossBatch.ordinal(), crossBatch.sha256())).items()).hasSize(1);
        var approved = new Review(sha, true, List.of("RQ-1"), List.of("F-1"), List.of());
        assertThat(submit(check, approved).outcome()).isEqualTo(MachineCandidateOutcome.REJECTED);
        reads.read(check.id(), file.path(), file.blobSha(), 1, 100); complete(check, approved);
        boolean done = false;
        for (int i = 0; i < 10 && !done; i++) done = assessments.advance(current(), contract);
        assertThat(done).isTrue();
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
