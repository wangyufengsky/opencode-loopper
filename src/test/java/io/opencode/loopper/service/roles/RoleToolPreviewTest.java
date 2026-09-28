package io.opencode.loopper.service.roles;

import io.opencode.loopper.runtime.OpenCodeClient.SessionPermissionRule;
import io.opencode.loopper.service.assist.AssistToolPolicyService;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static io.opencode.loopper.service.roles.RoleCapabilities.Capability.*;

class RoleToolPreviewTest {
    @Test void policiesRuntimeDiscoveryAndScopeRemainSeparate() {
        var role = role(List.of(PROJECT_KNOWLEDGE, NATIVE_TOOLS, EXTERNAL_MCP));
        var rules = List.of(rule("*", "deny"), rule("read", "allow"), rule("internal_assist_search_knowledge", "allow"), rule("internal_assist_read_knowledge_source", "allow"));
        var policies = List.of(setting("search_knowledge", false, "PROJECT"), setting("read_knowledge_source", true, "GLOBAL"));
        var tools = RoleToolPreview.tools(role, rules, rules, policies, "internal", "CONNECTED");
        assertThat(find(tools, "@loopper-assist/search_knowledge").status()).isEqualTo("POLICY_DISABLED");
        assertThat(find(tools, "@loopper-assist/search_knowledge").reason()).contains("项目");
        assertThat(find(tools, "@loopper-assist/read_knowledge_source").status()).isEqualTo("DISCOVERY_REQUIRED");
        assertThat(find(tools, "external_search").status()).isEqualTo("DISCOVERY_REQUIRED");
        assertThat(find(tools, "read").status()).isEqualTo("SCOPE_REQUIRED");
        assertThat(tools).allMatch(tool -> !tool.available());
        var inactive = RoleToolPreview.tools(role, rules, rules, policies, "internal", "INACTIVE");
        assertThat(find(inactive, "@loopper-assist/read_knowledge_source").status()).isEqualTo("RUNTIME_UNAVAILABLE");
        var disabled = RoleToolPreview.tools(role(List.of()), rules, rules, policies, "internal", "CONNECTED");
        assertThat(disabled).allMatch(tool -> tool.status().equals("ROLE_DISABLED"));
    }
    @Test void disabledBundledPoliciesRemainVisibleAndReadOnlyCompilationFailureIsNotAvailability() {
        var role = new RoleConfigurationService.ResolvedRole("any", "revision", "sha", "GENERAL_READ_ONLY", "GENERAL_READ_ONLY",
                Map.of(), "BASELINE", List.of(), List.of(), List.of(), "INHERIT_WORKFLOW", "WORKFLOW_ADAPTER", List.of(PROJECT_KNOWLEDGE));
        var policies = List.of(setting("search_knowledge", false, "GLOBAL"));
        var tools = RoleToolPreview.tools(role, List.of(rule("*", "deny")), List.of(), policies, "internal", "CONNECTED");
        assertThat(find(tools, "@loopper-assist/search_knowledge").status()).isEqualTo("POLICY_DISABLED");
        assertThat(find(tools, "@loopper-assist/search_knowledge").reason()).contains("全局");
        assertThat(find(tools, "@loopper-assist/read_knowledge_source").status()).isEqualTo("CONFIGURATION_BLOCKED");
    }
    private static RoleReadService.PreviewTool find(List<RoleReadService.PreviewTool> tools, String name) {
        return tools.stream().filter(tool -> tool.name().equals(name)).findFirst().orElseThrow();
    }
    private static RoleConfigurationService.ResolvedRole role(List<RoleCapabilities.Capability> capabilities) {
        return new RoleConfigurationService.ResolvedRole("name-does-not-authorize", "revision", "sha", "GENERAL_READ_ONLY", "GENERAL_READ_ONLY",
                Map.of(), "INTERSECT", List.of("read"), List.of("@loopper-assist/search_knowledge", "@loopper-assist/read_knowledge_source", "external_search"),
                List.of(), "INHERIT_WORKFLOW", "WORKFLOW_ADAPTER", capabilities);
    }
    private static SessionPermissionRule rule(String name, String action) { return new SessionPermissionRule(name, "*", action); }
    private static AssistToolPolicyService.View setting(String name, boolean enabled, String source) {
        return new AssistToolPolicyService.View(name, true, false, enabled, "INHERIT", enabled, source, 0, -1, "");
    }
}
