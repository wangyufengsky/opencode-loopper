package io.opencode.loopper.runtime;

import io.opencode.loopper.domain.MachineCandidateKind;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Stable, server-owned contracts for the private candidate-submission MCP. */
public final class InternalMcpContractCatalog {
    public static final String ENDPOINT_PATH = "/api/internal-mcp-streamable";
    public static final String TOOL_NAME = "submit_candidate";

    private static final Map<MachineCandidateKind, String> ROLE_TOOLS = Map.ofEntries(
            Map.entry(MachineCandidateKind.DECOMPOSITION_PLAN_V2, "submit_decomposition_plan"),
            Map.entry(MachineCandidateKind.ACCEPTANCE_CLOSED_CHOICE_V7, "submit_acceptance_choice"),
            Map.entry(MachineCandidateKind.PACKAGE_DESIGN_V1, "submit_package_design"),
            Map.entry(MachineCandidateKind.ROLLING_PACKAGE_PLAN_V1, "submit_rolling_package_plan"),
            Map.entry(MachineCandidateKind.REVIEWER_REPORT_V1, "submit_reviewer_report"),
            Map.entry(MachineCandidateKind.PROJECT_CONVENTION_V1, "submit_project_convention"),
            Map.entry(MachineCandidateKind.JUDGE_DECISION_V1, "submit_judge_decision"),
            Map.entry(MachineCandidateKind.DOCUMENT_REQUIREMENTS_V1, "submit_document_requirements"),
            Map.entry(MachineCandidateKind.DOCUMENT_REQUIREMENT_REVIEW_V1, "submit_document_requirement_review"),
            Map.entry(MachineCandidateKind.REQUIREMENT_CODE_ASSESSMENT_V1, "submit_requirement_code_assessment"),
            Map.entry(MachineCandidateKind.REQUIREMENT_ASSESSMENT_REVIEW_V1, "submit_requirement_assessment_review"));

    private InternalMcpContractCatalog() { }

    public static List<String> toolNames() {
        return List.of(
                toolName(MachineCandidateKind.DECOMPOSITION_PLAN_V2),
                toolName(MachineCandidateKind.ACCEPTANCE_CLOSED_CHOICE_V7),
                toolName(MachineCandidateKind.PACKAGE_DESIGN_V1),
                toolName(MachineCandidateKind.ROLLING_PACKAGE_PLAN_V1),
                toolName(MachineCandidateKind.REVIEWER_REPORT_V1),
                toolName(MachineCandidateKind.PROJECT_CONVENTION_V1),
                toolName(MachineCandidateKind.JUDGE_DECISION_V1),
                toolName(MachineCandidateKind.DOCUMENT_REQUIREMENTS_V1),
                toolName(MachineCandidateKind.DOCUMENT_REQUIREMENT_REVIEW_V1),
                toolName(MachineCandidateKind.REQUIREMENT_CODE_ASSESSMENT_V1),
                toolName(MachineCandidateKind.REQUIREMENT_ASSESSMENT_REVIEW_V1),
                "list_requirement_documents", "list_document_sections", "read_document_section", "list_requirement_code", "read_requirement_code", "search_requirement_code", "list_requirement_assessments", "read_requirement_assessment",
                "list_development_requirements", "read_development_requirement", "read_development_source",
                PACKAGE_V2_TOOL, TEMPLATE_TOOL, legacyToolName());
    }

    public static String toolName(MachineCandidateKind kind) {
        String toolName = ROLE_TOOLS.get(kind);
        if (toolName == null) throw new IllegalArgumentException("Unsupported candidate kind: " + kind);
        return toolName;
    }

    public static Optional<String> toolName(OpenCodeClient.SessionProfile profile) {
        if (profile == null) return Optional.empty();
        return switch (profile) {
            case DOCUMENT_REQUIREMENTS_NO_TOOLS -> optional(MachineCandidateKind.DOCUMENT_REQUIREMENTS_V1);
            case DOCUMENT_REQUIREMENT_REVIEW_NO_TOOLS -> optional(MachineCandidateKind.DOCUMENT_REQUIREMENT_REVIEW_V1);
            case REQUIREMENT_CODE_ASSESSMENT_NO_TOOLS -> optional(MachineCandidateKind.REQUIREMENT_CODE_ASSESSMENT_V1);
            case REQUIREMENT_ASSESSMENT_REVIEW_NO_TOOLS -> optional(MachineCandidateKind.REQUIREMENT_ASSESSMENT_REVIEW_V1);
            case TEMPLATE_ANALYSIS_CANDIDATE_NO_TOOLS -> Optional.of(TEMPLATE_TOOL);
            case DECOMPOSER_CANDIDATE_READ_ONLY -> optional(MachineCandidateKind.DECOMPOSITION_PLAN_V2);
            case ACCEPTANCE_CLOSED_CHOICE_CANDIDATE_NO_TOOLS ->
                    optional(MachineCandidateKind.ACCEPTANCE_CLOSED_CHOICE_V7);
            case PACKAGE_DESIGN_CANDIDATE_READ_ONLY, PACKAGE_DESIGN_CANDIDATE_INTERACTIVE_READ_ONLY ->
                    optional(MachineCandidateKind.PACKAGE_DESIGN_V1);
            case PACKAGE_DESIGN_CANDIDATE_V2_READ_ONLY, PACKAGE_DESIGN_CANDIDATE_V2_INTERACTIVE_READ_ONLY -> Optional.of(PACKAGE_V2_TOOL);
            case ROLLING_PACKAGE_CANDIDATE_READ_ONLY -> optional(MachineCandidateKind.ROLLING_PACKAGE_PLAN_V1);
            case REVIEWER_CANDIDATE_READ_ONLY -> optional(MachineCandidateKind.REVIEWER_REPORT_V1);
            case PROJECT_CONVENTION_CANDIDATE_READ_ONLY -> optional(MachineCandidateKind.PROJECT_CONVENTION_V1);
            case JUDGE_CANDIDATE_READ_ONLY -> optional(MachineCandidateKind.JUDGE_DECISION_V1);
            default -> Optional.empty();
        };
    }

    public static String legacyToolName() {
        return TOOL_NAME;
    }

    /** Legacy recovery-only schema retained for frozen launch plans. */
    public static Map<String, Object> inputSchema() {
        return InternalMcpCandidateSchemas.legacyInput();
    }

    public static Map<String, Object> inputSchema(MachineCandidateKind kind) {
        return InternalMcpCandidateSchemas.input(kind);
    }

    public static final String TEMPLATE_TOOL = "submit_template_analysis";

    public static final String PACKAGE_V2_TOOL = "submit_package_design_v2";

    public static Map<String, Object> packageDesignV2InputSchema() {
        return InternalMcpCandidateSchemas.packageDesignV2Input();
    }

    private static Optional<String> optional(MachineCandidateKind kind) {
        return Optional.of(toolName(kind));
    }
}
