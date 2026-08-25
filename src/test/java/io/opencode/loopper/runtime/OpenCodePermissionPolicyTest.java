package io.opencode.loopper.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OpenCodePermissionPolicyTest {
    @Test
    void everyNonRouterRoleAllowsConfiguredMcpToolsWithoutRemovingItsBuiltInBoundary() {
        for (OpenCodeClient.SessionProfile profile : OpenCodeClient.SessionProfile.values()) {
            if (profile == OpenCodeClient.SessionProfile.ROUTER_NO_TOOLS) continue;
            var rules = OpenCodePermissionPolicy.rules(profile, java.util.List.of("project mcp"));
            assertThat(rules)
                    .as(profile.name())
                    .contains(java.util.Map.of("permission", "project_mcp_*", "pattern", "*", "action", "allow"));
            assertThat(rules)
                    .as(profile.name())
                    .contains(java.util.Map.of("permission", "external_directory", "pattern", "*", "action", "deny"));
        }
    }

    @Test
    void routerDeniesBuiltInAndConfiguredMcpTools() {
        var rules = OpenCodePermissionPolicy.rules(OpenCodeClient.SessionProfile.ROUTER_NO_TOOLS,
                java.util.List.of("project mcp"));

        assertThat(rules).contains(
                java.util.Map.of("permission", "*", "pattern", "*", "action", "deny"),
                java.util.Map.of("permission", "external_directory", "pattern", "*", "action", "deny"));
        assertThat(rules).noneMatch(rule -> "allow".equals(rule.get("action")));
    }

    @Test
    void compilerRepairDeniesBuiltInsButAllowsConfiguredMcpTools() {
        var rules = OpenCodePermissionPolicy.rules(OpenCodeClient.SessionProfile.COMPILER_REPAIR_NO_TOOLS,
                java.util.List.of("github", "internal docs"));

        assertThat(rules).contains(
                java.util.Map.of("permission", "*", "pattern", "*", "action", "deny"),
                java.util.Map.of("permission", "external_directory", "pattern", "*", "action", "deny"),
                java.util.Map.of("permission", "github_*", "pattern", "*", "action", "allow"),
                java.util.Map.of("permission", "internal_docs_*", "pattern", "*", "action", "allow"));
        assertThat(rules).noneMatch(rule -> "allow".equals(rule.get("action"))
                && java.util.Set.of("read", "glob", "grep", "question").contains(rule.get("permission")));
    }

    @Test
    void compilerBindingDeniesAllBuiltInTools() {
        var rules = OpenCodePermissionPolicy.rules(OpenCodeClient.SessionProfile.COMPILER_BINDING_NO_TOOLS);

        assertThat(rules).contains(java.util.Map.of(
                "permission", "*", "pattern", "*", "action", "deny"));
        assertThat(rules).noneMatch(rule -> "allow".equals(rule.get("action"))
                && java.util.Set.of("read", "glob", "grep", "question").contains(rule.get("permission")));
    }

    @Test
    void interactiveDesignerAllowsQuestionButKeepsSecretsAndExternalDirectoriesDenied() {
        var rules = OpenCodePermissionPolicy.rules(
                OpenCodeClient.SessionProfile.DESIGNER_INTERACTIVE_READ_ONLY);

        assertThat(rules).contains(
                java.util.Map.of("permission", "question", "pattern", "*", "action", "allow"),
                java.util.Map.of("permission", "read", "pattern", ".env", "action", "deny"),
                java.util.Map.of("permission", "external_directory", "pattern", "*", "action", "deny"));
    }

    @Test
    void implementationAllowsTodoButDeniesGitMutationAndDestructiveCommands() {
        var rules = OpenCodePermissionPolicy.rules(OpenCodeClient.SessionProfile.IMPLEMENTATION);

        assertThat(rules).contains(
                java.util.Map.of("permission", "todowrite", "pattern", "*", "action", "allow"),
                java.util.Map.of("permission", "bash", "pattern", "*git*commit*", "action", "deny"),
                java.util.Map.of("permission", "bash", "pattern", "rm -rf*", "action", "deny"));
    }
}
