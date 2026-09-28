package io.opencode.loopper.service.knowledge;

import io.opencode.loopper.service.roles.RoleCapabilities;
import static io.opencode.loopper.service.roles.RoleCapabilities.Capability.*;

/** Evidence retrieval is distinct from native execution and from live business SQL. */
public final class WorkflowKnowledgePolicy {
    private WorkflowKnowledgePolicy() { }
    public static boolean supports(String profile) { return RoleCapabilities.has(profile, PROJECT_KNOWLEDGE); }
    public static boolean evidenceOnly(String profile) { return !RoleCapabilities.has(profile, KNOWLEDGE_DATABASE_METADATA); }
}
