package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.*;
import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.*;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.runtime.*;
import io.opencode.loopper.template.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = LoopperApplication.class, properties = {
        "loopper.opencode.mode=fake", "loopper.monitor-delay=1h", "loopper.designer-monitor-delay=1h"})
class DocumentModelExecutionIntegrationTest {
    @Autowired Flyway flyway;
    @Autowired DocumentTemplateService service;
    @Autowired DocumentTemplateMapper documents;
    @Autowired DocumentModelStore store;
    @Autowired DocumentModelExecution execution;
    @Autowired DocumentRequirementWorkflow workflow;
    @Autowired DocumentTemplateModelMapper models;
    @Autowired DocumentRequirementMapper requirements;
    @Autowired DocumentTemplateControl control;
    @Autowired DocumentTemplateCoordinator coordinator;
    @Autowired DocumentFrozenReadService reads;
    @Autowired MachineCandidateSubmission submissions;
    @Autowired ProjectService projects;
    @Autowired LoopperProperties properties;
    @Autowired OpenCodeClient client;
    @Autowired InternalMcpRuntimeAccess access;
    @Autowired ObjectMapper json;
    @TempDir Path temporary;
    private FakeOpenCodeClient fake;
    private DocumentTemplateRunRow run;
    private DocumentTemplateService.Contract contract;
    private DocumentTemplateModelRow model;

    @BeforeEach void prepare() throws Exception {
        flyway.clean(); flyway.migrate(); fake = (FakeOpenCodeClient) client; fake.reset();
        var credentials = new InternalMcpCredentialProvider(() -> 18083).issue();
        access.activate(credentials); access.connected(credentials.generation());
        fake.setManagedRuntime(credentials.generation(), credentials.serverName());
        for (MachineCandidateKind kind : MachineCandidateKind.values()) if (DocumentTemplateProfiles.supports(kind))
            fake.holdProfileOpen(DocumentTemplateProfiles.profile(kind), true);
        properties.getOpenCode().setModel("fake/test-model");
        String project = projects.create("需求候选夹具", Files.createDirectory(temporary.resolve("source")).toString(), "test").id();
        run = service.create(new DocumentTemplateService.Request(UUID.randomUUID().toString(), "REQUIREMENT_DEVELOPMENT", "1", project, null),
                List.of(new DocumentTemplateStorage.Incoming("付款.md", "# 付款\n金额必须大于零。不得重复付款。".getBytes(StandardCharsets.UTF_8))));
        contract = json.readValue(run.contractJson(), DocumentTemplateService.Contract.class);
        workflow.advance(run, contract);
        model = models.latest(run.id(), "DOCUMENT_REQUIREMENTS_V1", 0).orElseThrow();
    }
    @Test void recoversExactCreateAndPromptWithoutDuplicatingCallsAndRequiresRemoteCompletion() {
        model = execution.advance(model.id(), contract);
        var plan = json.readValue(model.creationPlanJson(), OpenCodeClient.SessionCreationPlan.class);
        client.createSession(plan); // lost create acknowledgement
        model = execution.advance(model.id(), contract);
        assertThat(fake.createReadOnlySessionCalls()).isEqualTo(1);
        model = execution.advance(model.id(), contract);
        fake.failNextPrompts(1); // fake stores the request identity before reporting an acknowledgement failure
        assertThatThrownBy(() -> execution.advance(model.id(), contract)).isInstanceOf(SessionFailure.class);
        model = execution.advance(model.id(), contract);
        assertThat(fake.promptCalls()).isEqualTo(1);
        var accepted = submit(model, candidate(model, false));
        assertThat(accepted.outcome()).isEqualTo(MachineCandidateOutcome.ACCEPTED);
        assertThat(execution.advance(model.id(), contract).state()).isEqualTo("RUNNING");
        fake.setSessionState(model.externalSessionId(), "COMPLETED");
        assertThat(execution.advance(model.id(), contract).state()).isEqualTo("VALIDATED");
        assertThat(documents.find(run.id()).orElseThrow().state()).isEqualTo("ANALYZING");
    }
    @Test void sourceForgeryIsRejectedAndStopUncertaintyKeepsTheRoleBlocked() {
        start();
        var candidate = candidate(model, false);
        var bad = json.valueToTree(candidate).deepCopy();
        ((tools.jackson.databind.node.ObjectNode) bad.path("requirements").get(0).path("sources").get(0)).put("quote", "伪造的业务规则");
        var rejected = submit(model, bad);
        assertThat(rejected.outcome()).isEqualTo(MachineCandidateOutcome.REJECTED);
        assertThat(rejected.problems()).anyMatch(problem -> problem.detail().contains("逐字"));
        var input = json.readValue(model.inputJson(), DocumentModelInput.class);
        var ref = input.sections().getFirst();
        assertThatThrownBy(() -> reads.section(model.id(), UUID.randomUUID().toString(), ref.section(), ref.sha256()))
                .isInstanceOf(BadRequestException.class);
        fake.failNextAborts(1);
        assertThatThrownBy(() -> execution.stop(model.id())).isInstanceOf(SessionFailure.class);
        assertThat(store.require(model.id()).state()).isEqualTo("STOPPING");
        assertThatThrownBy(() -> submit(model, candidate)).isInstanceOf(ConflictException.class);
        assertThat(execution.stop(model.id())).isTrue();
        assertThat(store.require(model.id()).state()).isEqualTo("STOPPED");
    }
    @Test void independentGoldReviewFindsSemanticOmissionThenRepairedLedgerPublishesOnce() {
        start(); complete(model, candidate(model, true)); // syntactically valid, but misses duplicate-payment rule
        DocumentTemplateModelRow review = awaitRole("DOCUMENT_REQUIREMENT_REVIEW_V1", 0);
        var reviewInput = json.readValue(review.inputJson(), DocumentModelInput.class);
        var ref = reviewInput.sections().getFirst();
        complete(review, new DocumentRequirements.Review(false, List.of("RQ-1"), reviewInput.requirements().coverage(),
                List.of(new DocumentRequirements.Correction(null, "OMISSION", "漏掉不得重复付款的规则",
                        List.of(new DocumentRequirements.Source(ref.fileId(), ref.section(), "不得重复付款"))))));
        model = awaitRole("DOCUMENT_REQUIREMENTS_V1", 1);
        assertThat(model.inputJson()).contains("漏掉不得重复付款");
        complete(model, candidate(model, false));
        review = awaitRole("DOCUMENT_REQUIREMENT_REVIEW_V1", 1);
        reviewInput = json.readValue(review.inputJson(), DocumentModelInput.class);
        complete(review, new DocumentRequirements.Review(true, List.of("RQ-1", "RQ-2"), reviewInput.requirements().coverage(), List.of()));
        for (int i = 0; i < 12 && documents.find(run.id()).orElseThrow().requirementRevision() == 0; i++)
            workflow.advance(documents.find(run.id()).orElseThrow(), contract);
        assertThat(documents.find(run.id()).orElseThrow().requirementRevision()).isEqualTo(1);
        assertThat(requirements.count(run.id(), 1)).isEqualTo(2);
        assertThat(workflow.advance(documents.find(run.id()).orElseThrow(), contract)).isTrue();
        assertThat(requirements.count(run.id(), 1)).isEqualTo(2);
    }
    @Test void cancellationWithLostCreateAcknowledgementRequiresStopProofAndReplaysOneCommand() {
        model = execution.advance(model.id(), contract);
        var plan = json.readValue(model.creationPlanJson(), OpenCodeClient.SessionCreationPlan.class);
        client.createSession(plan);
        var command = new DocumentTemplateControl.Command(UUID.randomUUID().toString(), documents.find(run.id()).orElseThrow().version());
        control.command(run.id(), "cancel", command);
        fake.failNextAborts(1);
        coordinator.checkpoint(run.id());
        assertThat(documents.find(run.id()).orElseThrow().state()).isEqualTo("STOPPING");
        assertThat(store.require(model.id()).state()).isEqualTo("STOPPING");
        assertThatThrownBy(() -> control.command(run.id(), "resume", new DocumentTemplateControl.Command(UUID.randomUUID().toString(),
                documents.find(run.id()).orElseThrow().version()))).isInstanceOf(ConflictException.class);
        coordinator.checkpoint(run.id());
        assertThat(documents.find(run.id()).orElseThrow().state()).isEqualTo("CANCELLED");
        assertThat(control.command(run.id(), "cancel", command).state()).isEqualTo("CANCELLED");
        assertThat(fake.createReadOnlySessionCalls()).isEqualTo(1);
        assertThat(store.require(model.id()).externalSessionId()).isNull();
        assertThat(store.require(model.id()).state()).isEqualTo("STOPPED");
    }
    @Test void explicitRecoveryUsesAnotherAttemptWithoutErasingTheOldSessionOrDuplicatingOnReplay() {
        start(); String oldSession = model.externalSessionId(); String oldPrompt = model.promptSha256();
        control.requestStop(run.id(), false, "DOCUMENT_MODEL_FAILED", "夹具模拟模型故障");
        coordinator.checkpoint(run.id());
        var waiting = documents.find(run.id()).orElseThrow();
        assertThat(waiting.state()).isEqualTo("WAITING_INPUT");
        assertThat(waiting.resumeState()).isEqualTo("ANALYZING");
        var request = new DocumentTemplateControl.Command(UUID.randomUUID().toString(), waiting.version());
        control.command(run.id(), "resume", request);
        var recovered = models.exact(run.id(), model.candidateKind(), model.ordinal(), model.generation()).orElseThrow();
        assertThat(recovered.id()).isNotEqualTo(model.id());
        assertThat(recovered.attempt()).isEqualTo(1);
        assertThat(recovered.inputSha256()).isEqualTo(model.inputSha256());
        assertThat(store.require(model.id()).externalSessionId()).isEqualTo(oldSession);
        assertThat(store.require(model.id()).promptSha256()).isEqualTo(oldPrompt);
        assertThat(store.require(model.id()).state()).isEqualTo("STOPPED");
        assertThat(control.command(run.id(), "resume", request).state()).isEqualTo("ANALYZING");
        assertThat(models.active(run.id())).extracting(DocumentTemplateModelRow::id).containsExactly(recovered.id());
        for (int i = 0; i < 4; i++) coordinator.checkpoint(run.id());
        assertThat(store.require(recovered.id()).state()).isEqualTo("RUNNING");
        assertThat(store.require(recovered.id()).externalSessionId()).isNotEqualTo(oldSession);
        assertThat(fake.createReadOnlySessionCalls()).isEqualTo(2);
    }
    private void start() {
        for (int i = 0; i < 4; i++) model = execution.advance(model.id(), contract);
        assertThat(model.state()).isEqualTo("RUNNING");
    }
    private DocumentTemplateModelRow awaitRole(String kind, int generation) {
        for (int i = 0; i < 20; i++) {
            workflow.advance(documents.find(run.id()).orElseThrow(), contract);
            var value = models.exact(run.id(), kind, 0, generation);
            if (value.isPresent() && value.get().state().equals("RUNNING")) return value.get();
        }
        throw new AssertionError("Role did not start: " + kind);
    }
    private void complete(DocumentTemplateModelRow row, Object candidate) {
        assertThat(submit(row, candidate).outcome()).isEqualTo(MachineCandidateOutcome.ACCEPTED);
        fake.setSessionState(row.externalSessionId(), "COMPLETED"); execution.advance(row.id(), contract);
    }
    private MachineCandidateSubmission.SubmissionResult submit(DocumentTemplateModelRow row, Object candidate) {
        var revision = submissions.find(row.id()).orElseThrow().version();
        return submissions.submit(new MachineCandidateSubmission.SubmitCommand(row.id(), "candidate-" + revision,
                json.writeValueAsString(candidate), revision, MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP,
                MachineCandidateSubmission.SubmissionSchema.ROLE_SPECIFIC_V2));
    }
    private DocumentRequirements.Candidate candidate(DocumentTemplateModelRow row, boolean omitDuplicateRule) {
        var input = json.readValue(row.inputJson(), DocumentModelInput.class); var ref = input.sections().getFirst();
        var amount = new DocumentRequirements.Requirement("RQ-1", "正金额", "付款", DocumentRequirements.Kind.RULE,
                "付款金额必须大于零", List.of(new DocumentRequirements.Source(ref.fileId(), ref.section(), "金额必须大于零")),
                List.of("零金额付款被拒绝"), List.of());
        var duplicate = new DocumentRequirements.Requirement("RQ-2", "重复付款", "付款", DocumentRequirements.Kind.RULE,
                "不得重复付款", List.of(new DocumentRequirements.Source(ref.fileId(), ref.section(), "不得重复付款")),
                List.of("重复请求不再次付款"), List.of());
        var items = omitDuplicateRule ? List.of(amount) : List.of(amount, duplicate);
        return new DocumentRequirements.Candidate(items, input.sections().stream().map(section -> new DocumentRequirements.Coverage(
                section.fileId(), section.section(), DocumentRequirements.Disposition.REQUIREMENT,
                items.stream().map(DocumentRequirements.Requirement::key).toList(), "付款规则")).toList());
    }
}
