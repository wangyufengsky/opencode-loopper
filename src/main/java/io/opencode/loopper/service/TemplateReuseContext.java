package io.opencode.loopper.service;

import io.opencode.loopper.persistence.RoleConfigurationMapper;
import io.opencode.loopper.persistence.WorkflowKnowledgeMapper;
import org.springframework.stereotype.Component;
import io.opencode.loopper.runtime.OpenCodeClient.SessionProfile;
import io.opencode.loopper.service.roles.RoleConfigurationService;
import io.opencode.loopper.service.roles.RoleCapabilities;

/** Cross-run reuse requires identical frozen roles and a closed evidence set. DB-only and fail closed. */
@Component
public final class TemplateReuseContext {
    private final RoleConfigurationMapper roles;
    private final WorkflowKnowledgeMapper knowledge;
    private final RoleConfigurationService configuration;
    public TemplateReuseContext(RoleConfigurationMapper roles, WorkflowKnowledgeMapper knowledge, RoleConfigurationService configuration) {
        this.roles = roles; this.knowledge = knowledge; this.configuration = configuration;
    }
    public String key(String taskId, SessionProfile profile) {
        var role = roles.ownerSnapshot("TASK", taskId);
        if (role == null || role.bindingCount() == 0 || role.bindingsSha256() == null) return null;
        var resolved = configuration.resolveFrozen(new RoleConfigurationService.OwnerRef("TASK", taskId), profile.name());
        if (resolved.isEmpty() || RoleCapabilities.effective(profile.name(), resolved.get().capabilities()).stream()
                .anyMatch(capability -> capability != RoleCapabilities.Capability.PROJECT_KNOWLEDGE)) return null;
        var sources = knowledge.binding("TASK", taskId);
        // Frozen authorization does not freeze live file bytes, negative searches, or new documents.
        // Even unused authorized sources prevent reuse: absence of a call is not dependency proof.
        if (sources != null && !"[]".equals(sources.sourcesJson())) return null;
        return role.bindingsSha256();
    }
}
