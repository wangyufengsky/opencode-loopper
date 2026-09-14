package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class DocumentPackageAcceptanceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final PackageDesignV2CompilationTest fixture = new PackageDesignV2CompilationTest();
    @Test void fullCompilerPreservesDocumentSourcesAndRequiresExecutableFinalRegression() {
        var input = fixture.input(source()).withContract("PACKAGE_DESIGN_V2");
        var candidate = fixture.candidate();
        ((ObjectNode) candidate.path("sourceBindings").get(0)).set("sourceRefs", json.valueToTree(List.of("RQ-1", DocumentRequirementContext.FINAL_REGRESSION)));
        var result = fixture.compiler.compileCandidate(input, json.writeValueAsString(candidate));
        assertThat(result.problems()).as("%s", result.problems()).isEmpty();
        assertThat(result.accepted()).isTrue();
        assertThat(result.compiledResultJson()).contains("RQ-1", DocumentRequirementContext.FINAL_REGRESSION, "TEST");
        assertThat(result.compiledPlan().handoffSummary()).contains("read_development_requirement");
        assertThat(result.compiledPlan().handoffSummary()).doesNotContain("LOOPPER_DOCUMENT_REQUIREMENT_SOURCES_V1");
    }
    @Test void documentSourceBoundOnlyToDeliverableDoesNotCountAsAnAcceptanceScenario() {
        var candidate = document();
        var noScenario = new PackageDesignV2Document(candidate.contractVersion(), candidate.outcome(), candidate.requirements(),
                candidate.scenarios(), candidate.deliverables(), candidate.reviews(), candidate.stages(), candidate.gapCodes(),
                List.of(new PackageDesignV2Document.SourceBinding("B", List.of("DEL-1"), List.of("RQ-1", DocumentRequirementContext.FINAL_REGRESSION))),
                candidate.relations(), candidate.gapClaims());
        assertThat(DocumentPackageAcceptance.validate(fixture.input(source()), noScenario, plan()))
                .extracting(PackageDesignCompilation.Problem::code).contains("DOCUMENT_REQUIREMENT_SCENARIO_MISSING", "DOCUMENT_FINAL_REGRESSION_REQUIRED");
    }
    @Test void buildAndEarlierStageTestsCannotSubstituteFinalRegression() {
        var candidate = document(); var plan = plan(); var stage = plan.stages().getFirst();
        var withoutTests = new DesignerSemanticContracts.PlannedStage(stage.objective(), stage.allowedPaths(), stage.forbiddenPaths(),
                stage.deliverables(), stage.verifiers().stream().filter(v -> !"TEST".equals(v.processPurpose())).toList(),
                stage.verificationRuntime(), stage.implementationKind(), stage.workPackageId());
        var noTests = new DesignerSemanticContracts.PackageCompilationPlanEnvelope(plan.contractVersion(), plan.status(), plan.summary(),
                List.of(withoutTests), plan.evidenceMappings(), plan.handoffSummary(), plan.designGaps());
        assertThat(DocumentPackageAcceptance.validate(fixture.input(source()), candidate, noTests))
                .extracting(PackageDesignCompilation.Problem::code).contains("DOCUMENT_STAGE_TEST_REQUIRED");
        var two = new PackageDesignV2Document(candidate.contractVersion(), candidate.outcome(), candidate.requirements(), candidate.scenarios(),
                candidate.deliverables(), candidate.reviews(), List.of(candidate.stages().getFirst(),
                new PackageDesignCandidateDocument.Stage("LATER", "后续行为", "改变前包行为", List.of(), List.of(candidate.stages().getFirst().key()))),
                candidate.gapCodes(), candidate.sourceBindings(), candidate.relations(), candidate.gapClaims());
        var laterPlan = new DesignerSemanticContracts.PackageCompilationPlanEnvelope(plan.contractVersion(), plan.status(), plan.summary(),
                List.of(stage, stage), plan.evidenceMappings(), plan.handoffSummary(), plan.designGaps());
        assertThat(DocumentPackageAcceptance.validate(fixture.input(source()), two, laterPlan))
                .extracting(PackageDesignCompilation.Problem::code).contains("DOCUMENT_FINAL_REGRESSION_REQUIRED");
        assertThat(DocumentPackageAcceptance.validate(fixture.input("普通软件需求"), two, noTests)).isEmpty();
    }
    private PackageDesignV2Document document() {
        var node = fixture.candidate();
        ((ObjectNode) node.path("sourceBindings").get(0)).set("sourceRefs", json.valueToTree(List.of("RQ-1", DocumentRequirementContext.FINAL_REGRESSION)));
        return json.treeToValue(node, PackageDesignV2Document.class);
    }
    private DesignerSemanticContracts.PackageCompilationPlanEnvelope plan() {
        var result = fixture.compiler.compileCandidate(fixture.input("补充 EventBusTest 聚焦测试。"), json.writeValueAsString(fixture.candidate()));
        assertThat(result.accepted()).isTrue(); return result.compiledPlan();
    }
    private String source() {
        return "LOOPPER_DOCUMENT_REQUIREMENT_SOURCES_V1\n" + json.writeValueAsString(new DocumentRequirementContext.Envelope("run", 1, "manifest",
                List.of(new DocumentRequirementContext.Entry("RQ-1", "事件验证", "补充 EventBusTest 聚焦测试。", new String[]{"消息仅投递一次"}, "[]"),
                        new DocumentRequirementContext.Entry(DocumentRequirementContext.FINAL_REGRESSION, "最终回归", DocumentRequirementContext.REGRESSION_RULE,
                                new String[]{"所有改动完成后验证回归"}, "[]"))));
    }
}
