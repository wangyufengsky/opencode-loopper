package io.opencode.loopper.runtime;

import java.util.Map;

/** One role policy for managed agent selection and the independent message-count guard. */
final class OpenCodeAgentPolicy {
    private OpenCodeAgentPolicy() { }

    /** Zero means Loopper imposes no fixed agentic-step limit. */
    static int stepLimit(OpenCodeClient.SessionProfile profile) {
        if (profile == null) return 0;
        return switch (profile) {
            case GENERAL_READ_ONLY, DESIGNER_INTERACTIVE_READ_ONLY, IMPLEMENTATION,
                    PACKAGE_DESIGN_CANDIDATE_READ_ONLY, PACKAGE_DESIGN_CANDIDATE_INTERACTIVE_READ_ONLY,
                    REVIEWER_READ_ONLY, REVIEWER_CANDIDATE_READ_ONLY,
                    JUDGE_READ_ONLY, JUDGE_CANDIDATE_READ_ONLY, JUDGE_FINALIZER_NO_TOOLS -> 0;
            case ROUTER_NO_TOOLS -> OpenCodeClient.ROUTER_AGENT_STEPS;
            default -> OpenCodeClient.STRUCTURED_AGENT_STEPS;
        };
    }

    static String promptAgent(String requested, OpenCodeClient.SessionProfile profile, boolean managed) {
        // Old callers may still explicitly request the bounded agent for an exempt role.
        if (requested != null && !requested.isBlank()
                && !(OpenCodeClient.STRUCTURED_AGENT.equals(requested) && profile != null && stepLimit(profile) == 0)) return requested;
        if (!managed || !OpenCodeHttpClientSemantics.machineResponseProfile(profile)) return null;
        if (profile == OpenCodeClient.SessionProfile.ROUTER_NO_TOOLS) return OpenCodeClient.ROUTER_AGENT;
        return stepLimit(profile) == 0 ? OpenCodeClient.UNBOUNDED_STRUCTURED_AGENT : OpenCodeClient.STRUCTURED_AGENT;
    }

    static Map<String, Object> managedDefinitions() {
        return Map.of(
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
