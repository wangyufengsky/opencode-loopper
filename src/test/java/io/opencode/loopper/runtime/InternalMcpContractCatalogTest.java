package io.opencode.loopper.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.opencode.loopper.domain.MachineCandidateKind;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InternalMcpContractCatalogTest {
    @Test
    void exposesOneStronglyTypedToolPerCandidateRoleAndKeepsTheLegacyNameForRecovery() {
        assertThat(InternalMcpContractCatalog.toolNames()).containsExactly(
                "submit_decomposition_plan",
                "submit_acceptance_choice",
                "submit_package_design",
                "submit_rolling_package_plan",
                "submit_reviewer_report",
                "submit_project_convention",
                "submit_judge_decision",
                "submit_candidate");

        assertThat(InternalMcpContractCatalog.toolName(MachineCandidateKind.DECOMPOSITION_PLAN_V2))
                .isEqualTo("submit_decomposition_plan");
        assertThat(InternalMcpContractCatalog.toolName(MachineCandidateKind.JUDGE_DECISION_V1))
                .isEqualTo("submit_judge_decision");
        assertThat(InternalMcpContractCatalog.legacyToolName()).isEqualTo("submit_candidate");
    }

    @Test
    void roleSchemasDescribeTheExactCandidateShapeInsteadOfAnOpaqueObject() {
        Map<String, Object> judge = InternalMcpContractCatalog.inputSchema(
                MachineCandidateKind.JUDGE_DECISION_V1);
        Map<String, Object> judgeCandidate = candidate(judge);
        assertThat(judgeCandidate).containsEntry("additionalProperties", false);
        assertThat(required(judgeCandidate)).containsExactly(
                "contractVersion", "role", "verdict", "reason", "evidenceIds");
        assertThat(properties(judgeCandidate)).containsOnlyKeys(
                "contractVersion", "role", "verdict", "reason", "evidenceIds");

        Map<String, Object> reviewerCandidate = candidate(InternalMcpContractCatalog.inputSchema(
                MachineCandidateKind.REVIEWER_REPORT_V1));
        assertThat(required(reviewerCandidate)).containsExactly("title", "summary", "findings", "limitations");
        Map<String, Object> finding = items(property(reviewerCandidate, "findings"));
        assertThat(finding).containsEntry("additionalProperties", false);
        assertThat(required(finding)).containsExactly(
                "severity", "title", "detail", "path", "line", "recommendation");
    }

    @Test
    void candidateProfilesResolveToExactlyOneRoleTool() {
        assertThat(InternalMcpContractCatalog.toolName(
                OpenCodeClient.SessionProfile.DECOMPOSER_CANDIDATE_READ_ONLY))
                .contains("submit_decomposition_plan");
        assertThat(InternalMcpContractCatalog.toolName(
                OpenCodeClient.SessionProfile.PACKAGE_DESIGN_CANDIDATE_INTERACTIVE_READ_ONLY))
                .contains("submit_package_design");
        assertThat(InternalMcpContractCatalog.toolName(
                OpenCodeClient.SessionProfile.JUDGE_CANDIDATE_READ_ONLY))
                .contains("submit_judge_decision");
        assertThat(InternalMcpContractCatalog.toolName(
                OpenCodeClient.SessionProfile.GENERAL_READ_ONLY)).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> candidate(Map<String, Object> schema) {
        return (Map<String, Object>) properties(schema).get("candidate");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(Map<String, Object> schema) {
        return (Map<String, Object>) schema.get("properties");
    }

    @SuppressWarnings("unchecked")
    private static List<String> required(Map<String, Object> schema) {
        return (List<String>) schema.get("required");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> property(Map<String, Object> schema, String name) {
        return (Map<String, Object>) properties(schema).get(name);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> items(Map<String, Object> schema) {
        return (Map<String, Object>) schema.get("items");
    }
}
