package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.opencode.loopper.domain.AcceptanceBindingSource;
import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.domain.MachineCandidateRunState;
import io.opencode.loopper.persistence.DesignAcceptancePlanningRow;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class DesignerAcceptanceCandidatePromptFactoryTest {
    private final ObjectMapper json = new ObjectMapper();
    private final DesignerAcceptanceCandidatePromptFactory prompts =
            new DesignerAcceptanceCandidatePromptFactory(json);

    @Test
    void internalPromptNamesOnlyTheExactToolAndFrozenClosedChoices() throws Exception {
        String prompt = prompts.internal(planning(), routing(), run(), "loopper_internal_xyz_submit_candidate");

        assertThat(prompt).contains(
                "ACCEPTANCE_CLOSED_CHOICE_V7",
                "run-7",
                "expectedSubmissionRevision: 3",
                "loopper_internal_xyz_submit_candidate",
                "\"optimalTieChoiceSets\":[[0],[1]]",
                "\"label\":\"closed-choice-0\"",
                "\"label\":\"closed-choice-1\"");
        assertThat(prompt).doesNotContain(
                "mvn", "ATest", "BTest", "同分 A", "同分 B", "src/", "allowedPaths", "\"permissions\"");
        assertThat(prompt).contains("\"factIndex\":0,\"stageIndex\":0", "\"capabilityIndexes\":[0]",
                "MCP submissions have no count limit", "ACCEPTANCE_CANDIDATE_CONTRACT_INVALID",
                "code, JSON Pointer, detail, allowed values");
        assertThat(prompt.split("loopper_internal_xyz_submit_candidate", -1).length - 1)
                .as("the exact tool may be repeated only as the call target and contract label")
                .isLessThanOrEqualTo(3);
    }

    @Test
    void legacyPromptKeepsTheSameCandidateContractWithoutClaimingMcpAcceptance() throws Exception {
        String prompt = prompts.legacy(planning(), routing(), null);

        assertThat(prompt).contains(
                "LOOPSPEC_COMPILATION_PLAN_JSON_START",
                "LOOPSPEC_COMPILATION_PLAN_JSON_END",
                "factAssignments", "capabilityPreferences",
                "final text is only a candidate");
        assertThat(prompt).doesNotContain("submit_candidate", "mvn", "ATest", "BTest");
    }

    private MachineCandidateSubmission.RunSnapshot run() {
        return new MachineCandidateSubmission.RunSnapshot(
                "run-7", MachineCandidateSubmission.CandidateScope.designerSession("session"),
                MachineCandidateSubmission.CandidateOwnerRef.loopSpecCompilation("cmp"),
                MachineCandidateKind.ACCEPTANCE_CLOSED_CHOICE_V7,
                AcceptanceClosedChoiceCandidateCoordinator.WORKFLOW_STEP, 3, 4,
                MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP,
                AcceptanceClosedChoiceCandidateCoordinator.CONTRACT_VERSION,
                "generation-7", "remote-7", MachineCandidateRunState.OPEN, 2, 0, null, 3);
    }

    private DesignAcceptancePlanningRow planning() throws Exception {
        DesignerAcceptancePlanning.Fact fact = new DesignerAcceptancePlanning.Fact(
                0, DesignerAcceptancePlanning.FactKind.SCENARIO, "成功", "输入合法", "执行",
                "返回成功", "不写外部系统", null, "DS-L001", "成功", "a".repeat(64));
        DesignerAcceptancePlanning.Catalog facts = new DesignerAcceptancePlanning.Catalog(
                DesignerAcceptancePlanning.CONTRACT_VERSION_V7, "WP-1", 3, "a".repeat(64), true,
                List.of(fact), List.of(new DesignerAcceptancePlanning.StageHint(
                "实现", "实现行为", List.of("成功"), List.of(), List.of(), List.of())), List.of());
        DesignerAcceptancePlanning.CapabilityCatalog capabilities =
                new DesignerAcceptancePlanning.CapabilityCatalog(
                        DesignerAcceptancePlanning.CONTRACT_VERSION_V7,
                        List.of(capability(0, "同分 A", "ATest"), capability(1, "同分 B", "BTest")),
                        List.of());
        return new DesignAcceptancePlanningRow(
                "cmp", "session", "WP-1", 3, DesignerAcceptancePlanning.CONTRACT_VERSION_V7,
                "a".repeat(64), "EXTRACTED", AcceptanceBindingSource.AI_DISAMBIGUATION_V6.name(),
                json.writeValueAsString(facts), json.writeValueAsString(capabilities), "{}", "{}",
                null, null, "created", "updated", 2);
    }

    private DesignerAcceptancePlanning.Capability capability(int index, String label, String target) {
        return new DesignerAcceptancePlanning.Capability(index, "FOCUSED_TEST", label,
                List.of("mvn", "-Dtest=" + target, "test"), List.of(0), List.of(target),
                true, false, 100);
    }

    private DesignerAcceptanceWorkflow.RoutingResult routing() {
        DesignerAcceptanceFastPathResolver.Resolution resolution =
                new DesignerAcceptanceFastPathResolver.Resolution(
                        DesignerAcceptanceFastPathResolver.Outcome.NEEDS_COMPILER,
                        List.of(new DesignerSemanticContracts.AcceptanceGroupHint(
                                "实现", "实现行为", List.of(0), List.of())),
                        List.of(), List.of(0), Map.of(0, List.of(0, 1)),
                        List.of(List.of(0), List.of(1)), 2,
                        List.of("AMBIGUOUS_CAPABILITY:0"), List.of());
        return new DesignerAcceptanceWorkflow.RoutingResult(resolution, false, true);
    }
}
