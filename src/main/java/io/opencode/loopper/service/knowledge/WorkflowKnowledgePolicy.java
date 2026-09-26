package io.opencode.loopper.service.knowledge;

import java.util.Set;

/** Evidence retrieval is distinct from native execution and from live business SQL. */
public final class WorkflowKnowledgePolicy {
    private WorkflowKnowledgePolicy() { }
    private static final Set<String> EXCLUDED = Set.of("ROUTER_NO_TOOLS", "MACHINE_FINALIZER_NO_TOOLS",
            "JUDGE_FINALIZER_NO_TOOLS", "COMPILER_BINDING_NO_TOOLS", "COMPILER_REPAIR_NO_TOOLS",
            "ACCEPTANCE_CLOSED_CHOICE_CANDIDATE_NO_TOOLS", "PPT_AGENT", "ACCOUNTING_COMMAND");
    public static boolean supports(String profile) { return profile != null && !profile.startsWith("KNOWLEDGE_") && !EXCLUDED.contains(profile); }
    public static boolean evidenceOnly(String profile) {
        return profile.contains("JUDGE") || profile.contains("REVIEWER") || profile.contains("NO_TOOLS")
                || profile.startsWith("PROJECT_CONVENTION_");
    }
}
