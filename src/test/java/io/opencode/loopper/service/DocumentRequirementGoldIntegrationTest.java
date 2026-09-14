package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.*;
import static io.opencode.loopper.template.DocumentRequirements.*;
import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.*;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.runtime.*;
import io.opencode.loopper.template.DocumentModelInput;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;

/** Human-labelled semantic errors exercise actual repair/publication, not only JSON validation. */
@SpringBootTest(classes = LoopperApplication.class, properties = {"loopper.opencode.mode=fake", "loopper.monitor-delay=1h"})
class DocumentRequirementGoldIntegrationTest {
    @Autowired org.springframework.context.ApplicationContext applicationContext;
    @Autowired Flyway flyway;
    @Autowired DocumentTemplateService service;
    @Autowired DocumentTemplateMapper runs;
    @Autowired DocumentTemplateModelMapper models;
    @Autowired DocumentRequirementMapper ledger;
    @Autowired DocumentRequirementWorkflow workflow;
    @Autowired DocumentModelExecution execution;
    @Autowired MachineCandidateSubmission submissions;
    @Autowired ProjectService projects;
    @Autowired LoopperProperties properties;
    @Autowired OpenCodeClient client;
    @Autowired InternalMcpRuntimeAccess access;
    @Autowired ObjectMapper json;
    @TempDir Path temporary;
    private FakeOpenCodeClient fake;
    private String id;
    private DocumentTemplateService.Contract contract;
    @BeforeEach void prepare() throws Exception {
        flyway.clean(); flyway.migrate(); fake = (FakeOpenCodeClient) client; fake.reset();
        var credentials = new InternalMcpCredentialProvider(() -> 18083).issue();
        access.activate(credentials); access.connected(credentials.generation()); fake.setManagedRuntime(credentials.generation(), credentials.serverName());
        fake.holdProfileOpen(DocumentTemplateProfiles.profile(MachineCandidateKind.DOCUMENT_REQUIREMENTS_V1), true);
        fake.holdProfileOpen(DocumentTemplateProfiles.profile(MachineCandidateKind.DOCUMENT_REQUIREMENT_REVIEW_V1), true);
        properties.getOpenCode().setModel("fake/test-model");
        var project = projects.create("人工标注订单样本", Files.createDirectory(temporary.resolve("source")).toString(), "test");
        var run = LegacyDocumentFixture.create(applicationContext, new DocumentTemplateService.Request(UUID.randomUUID().toString(), "REQUIREMENT_DEVELOPMENT", "1", project.id(), null),
                List.of(new DocumentTemplateStorage.Incoming("订单.md", "# 订单\n金额必须大于零；只能撤回本人创建的待审批订单。".getBytes(StandardCharsets.UTF_8))));
        id = run.id(); contract = json.readValue(run.contractJson(), DocumentTemplateService.Contract.class);
    }
    @ParameterizedTest @ValueSource(strings = {"OMISSION", "UNSUPPORTED", "WRONG_MERGE"})
    void semanticCorrectionsCannotPublishUntilTheRepairedCandidateIsIndependentlyReviewed(String defect) {
        var first = role("DOCUMENT_REQUIREMENTS_V1", 0);
        var input = json.readValue(first.inputJson(), DocumentModelInput.class); var section = input.sections().getFirst();
        var amountSource = new Source(section.fileId(), section.section(), "金额必须大于零");
        var withdrawSource = new Source(section.fileId(), section.section(), "只能撤回本人创建的待审批订单");
        var amount = new Requirement("RQ-1", "金额约束", "订单", Kind.RULE, "金额必须大于零", List.of(amountSource), List.of("零与负数拒绝"), List.of());
        var withdraw = new Requirement("RQ-2", "撤回约束", "订单", Kind.PERMISSION, "只能撤回本人创建的待审批订单", List.of(withdrawSource), List.of("他人订单拒绝", "已审批订单拒绝"), List.of());
        List<Requirement> flawed = switch (defect) {
            case "OMISSION" -> List.of(amount);
            case "UNSUPPORTED" -> List.of(new Requirement("RQ-1", "金额约束", "订单", Kind.RULE,
                    "金额必须大于零且管理员可以撤回任意订单", List.of(amountSource), List.of("管理员允许撤回他人订单"), List.of()), withdraw);
            default -> List.of(new Requirement("RQ-1", "订单操作", "订单", Kind.RULE,
                    "金额大于零即可撤回订单", List.of(amountSource, withdrawSource), List.of("正金额可以撤回"), List.of()));
        };
        var proposed = candidate(section, flawed); complete(first, proposed);
        var reviewer = role("DOCUMENT_REQUIREMENT_REVIEW_V1", 0);
        complete(reviewer, new Review(false, flawed.stream().map(Requirement::key).toList(), proposed.coverage(), List.of(
                new Correction(null, defect, "人工金标准：金额约束和本人待审批撤回约束应独立保留，文档没有管理员例外", List.of(withdrawSource)))));
        var repairedRole = role("DOCUMENT_REQUIREMENTS_V1", 1);
        assertThat(runs.find(id).orElseThrow().requirementRevision()).isZero();
        assertThat(ledger.count(id, 1)).isZero();
        var feedback = json.readValue(repairedRole.inputJson(), DocumentModelInput.class);
        assertThat(feedback.requirementFeedback().corrections()).singleElement().satisfies(correction -> assertThat(correction.category()).isEqualTo(defect));
        assertThat(feedback.requirements()).isEqualTo(proposed);
        var fixed = candidate(section, List.of(amount, withdraw)); complete(repairedRole, fixed);
        var finalReview = role("DOCUMENT_REQUIREMENT_REVIEW_V1", 1);
        assertThat(finalReview.externalSessionId()).isNotEqualTo(repairedRole.externalSessionId());
        complete(finalReview, new Review(true, List.of("RQ-1", "RQ-2"), fixed.coverage(), List.of()));
        for (int i = 0; i < 10 && runs.find(id).orElseThrow().requirementRevision() == 0; i++) workflow.advance(runs.find(id).orElseThrow(), contract);
        assertThat(runs.find(id).orElseThrow().requirementRevision()).isEqualTo(1);
        assertThat(ledger.page(id, 1, -1, 100)).extracting(DocumentRequirementMapper.Requirement::statement)
                .containsExactly("金额必须大于零", "只能撤回本人创建的待审批订单");
        assertThat(runs.find(id).orElseThrow().designerId()).isNull();
    }
    private Candidate candidate(DocumentModelInput.SectionRef section, List<Requirement> items) {
        return new Candidate(items, List.of(new Coverage(section.fileId(), section.section(), Disposition.REQUIREMENT,
                items.stream().map(Requirement::key).toList(), "订单规则")));
    }
    private DocumentTemplateModelRow role(String kind, int generation) {
        for (int i = 0; i < 24; i++) {
            workflow.advance(runs.find(id).orElseThrow(), contract);
            var model = models.exact(id, kind, 0, generation);
            if (model.isPresent() && model.get().state().equals("RUNNING")) return model.get();
        }
        throw new AssertionError("Role did not start " + kind + "/" + generation);
    }
    private void complete(DocumentTemplateModelRow model, Object output) {
        var revision = submissions.find(model.id()).orElseThrow().version();
        var response = submissions.submit(new MachineCandidateSubmission.SubmitCommand(model.id(), "gold-" + revision,
                json.writeValueAsString(output), revision, MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP,
                MachineCandidateSubmission.SubmissionSchema.ROLE_SPECIFIC_V2));
        assertThat(response.outcome()).as("%s", response.problems()).isEqualTo(MachineCandidateOutcome.ACCEPTED);
        fake.setSessionState(model.externalSessionId(), "COMPLETED"); execution.advance(model.id(), contract);
    }
}
