package io.opencode.loopper.runtime;

import io.opencode.loopper.domain.MachineCandidateKind;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Stable, server-owned contracts for the private candidate-submission MCP. */
public final class InternalMcpContractCatalog {
    public static final String ENDPOINT_PATH = "/api/internal-mcp-streamable";
    public static final String TOOL_NAME = "submit_candidate";

    private static final Map<MachineCandidateKind, String> ROLE_TOOLS = Map.of(
            MachineCandidateKind.DECOMPOSITION_PLAN_V2, "submit_decomposition_plan",
            MachineCandidateKind.ACCEPTANCE_CLOSED_CHOICE_V7, "submit_acceptance_choice",
            MachineCandidateKind.PACKAGE_DESIGN_V1, "submit_package_design",
            MachineCandidateKind.ROLLING_PACKAGE_PLAN_V1, "submit_rolling_package_plan",
            MachineCandidateKind.REVIEWER_REPORT_V1, "submit_reviewer_report",
            MachineCandidateKind.PROJECT_CONVENTION_V1, "submit_project_convention",
            MachineCandidateKind.JUDGE_DECISION_V1, "submit_judge_decision");

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
                legacyToolName());
    }

    public static String toolName(MachineCandidateKind kind) {
        String toolName = ROLE_TOOLS.get(kind);
        if (toolName == null) throw new IllegalArgumentException("Unsupported candidate kind: " + kind);
        return toolName;
    }

    public static Optional<String> toolName(OpenCodeClient.SessionProfile profile) {
        if (profile == null) return Optional.empty();
        return switch (profile) {
            case DECOMPOSER_CANDIDATE_READ_ONLY -> optional(MachineCandidateKind.DECOMPOSITION_PLAN_V2);
            case ACCEPTANCE_CLOSED_CHOICE_CANDIDATE_NO_TOOLS ->
                    optional(MachineCandidateKind.ACCEPTANCE_CLOSED_CHOICE_V7);
            case PACKAGE_DESIGN_CANDIDATE_READ_ONLY, PACKAGE_DESIGN_CANDIDATE_INTERACTIVE_READ_ONLY ->
                    optional(MachineCandidateKind.PACKAGE_DESIGN_V1);
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

    private static Optional<String> optional(MachineCandidateKind kind) {
        return Optional.of(toolName(kind));
    }
}
