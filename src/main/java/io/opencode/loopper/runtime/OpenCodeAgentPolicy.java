package io.opencode.loopper.runtime;

import java.util.Map;

/** One role policy for managed agent selection and the independent message-count guard. */
final class OpenCodeAgentPolicy {
    private OpenCodeAgentPolicy() { }

    /** Zero means Loopper imposes no fixed agentic-step limit. */
    static int stepLimit(OpenCodeClient.SessionProfile profile) {
        if (profile == null || profile == OpenCodeClient.SessionProfile.PPT_AGENT || DocumentTemplateProfiles.contains(profile)) return 0;
        return switch (profile) {
            case KNOWLEDGE_RESEARCH_READ_ONLY, KNOWLEDGE_RESEARCH_INTERACTIVE_READ_ONLY,
                    KNOWLEDGE_INTERACTIVE_READ_ONLY, KNOWLEDGE_READ_ONLY, GENERAL_READ_ONLY, DESIGNER_INTERACTIVE_READ_ONLY, IMPLEMENTATION, SNAPSHOT_CODE_REVIEW_NO_TOOLS, TEMPLATE_ANALYSIS_NO_TOOLS, TEMPLATE_ANALYSIS_CANDIDATE_NO_TOOLS,
                    PACKAGE_DESIGN_CANDIDATE_READ_ONLY, PACKAGE_DESIGN_CANDIDATE_INTERACTIVE_READ_ONLY,
                    PACKAGE_DESIGN_CANDIDATE_V2_READ_ONLY, PACKAGE_DESIGN_CANDIDATE_V2_INTERACTIVE_READ_ONLY,
                    REVIEWER_READ_ONLY, REVIEWER_CANDIDATE_READ_ONLY,
                    JUDGE_READ_ONLY, JUDGE_CANDIDATE_READ_ONLY, JUDGE_FINALIZER_NO_TOOLS -> 0;
            case ROUTER_NO_TOOLS -> OpenCodeClient.ROUTER_AGENT_STEPS;
            default -> OpenCodeClient.STRUCTURED_AGENT_STEPS;
        };
    }

    static String promptAgent(String requested, OpenCodeClient.SessionProfile profile, boolean managed) {
        if (profile == OpenCodeClient.SessionProfile.PPT_AGENT) return managed ? PptAgentProfile.AGENT : null;
        // Old callers may still explicitly request the bounded agent for an exempt role.
        if (requested != null && !requested.isBlank()
                && !(OpenCodeClient.STRUCTURED_AGENT.equals(requested) && profile != null && stepLimit(profile) == 0)) return requested;
        if (!managed || !OpenCodeHttpClientSemantics.machineResponseProfile(profile)) return null;
        if (profile == OpenCodeClient.SessionProfile.ROUTER_NO_TOOLS) return OpenCodeClient.ROUTER_AGENT;
        return stepLimit(profile) == 0 ? OpenCodeClient.UNBOUNDED_STRUCTURED_AGENT : OpenCodeClient.STRUCTURED_AGENT;
    }

    static Map<String, Object> managedDefinitions() {
        return Map.of(
                PptAgentProfile.AGENT, Map.of("description", "Loopper PPT Agent", "mode", "primary",
                        "temperature", 0.2d, "prompt", PptAgentProfile.BASE_PROMPT),
                OpenCodeClient.STRUCTURED_AGENT, Map.of(
                        "description", "Bounded read-only Loopper role for machine-response workflows",
                        "mode", "primary", "steps", OpenCodeClient.STRUCTURED_AGENT_STEPS,
                        "temperature", OpenCodeClient.STRUCTURED_AGENT_TEMPERATURE,
                        "prompt", OpenCodeClient.STRUCTURED_AGENT_PROMPT),
                OpenCodeClient.UNBOUNDED_STRUCTURED_AGENT, Map.of(
                        "description", "Loopper Designer, Reviewer and Judge without a fixed step limit",
                        "mode", "primary", "temperature", OpenCodeClient.STRUCTURED_AGENT_TEMPERATURE,
                        "prompt", OpenCodeClient.STRUCTURED_AGENT_PROMPT),
                OpenCodeClient.ROUTER_AGENT, Map.of(
                        "description", "Single-shot Loopper task classifier without tools or design work",
                        "mode", "primary", "steps", OpenCodeClient.ROUTER_AGENT_STEPS,
                        "temperature", OpenCodeClient.ROUTER_AGENT_TEMPERATURE,
                        "prompt", OpenCodeClient.ROUTER_AGENT_PROMPT));
    }
}
