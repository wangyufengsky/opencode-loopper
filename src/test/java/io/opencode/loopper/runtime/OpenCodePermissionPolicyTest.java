package io.opencode.loopper.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OpenCodePermissionPolicyTest {
    @Test
    void candidateRoleAllowsOnlyReadToolsAndTheExactInternalSubmissionTool() {
        var rules = OpenCodePermissionPolicy.rules(
                OpenCodeClient.SessionProfile.DECOMPOSER_CANDIDATE_READ_ONLY,
                java.util.List.of("github", "loopper_internal_generation"),
                "loopper_internal_generation");

        assertThat(rules).contains(
                java.util.Map.of("permission", "read", "pattern", "*", "action", "allow"),
                java.util.Map.of("permission", "glob", "pattern", "*", "action", "allow"),
                java.util.Map.of("permission", "grep", "pattern", "*", "action", "allow"),
                java.util.Map.of("permission", "loopper_internal_generation_submit_candidate",
                        "pattern", "*", "action", "allow"));
        assertThat(rules).noneMatch(rule -> "allow".equals(rule.get("action"))
                && ("github_*".equals(rule.get("permission"))
                || "loopper_internal_generation_*".equals(rule.get("permission"))));
    }

    @Test
    void acceptanceClosedChoiceCandidateAllowsOnlyTheExactInternalSubmissionTool() {
        var rules = OpenCodePermissionPolicy.rules(
                OpenCodeClient.SessionProfile.ACCEPTANCE_CLOSED_CHOICE_CANDIDATE_NO_TOOLS,
                java.util.List.of("github", "loopper_internal_generation"),
                "loopper_internal_generation");

        assertThat(rules).contains(
                java.util.Map.of("permission", "*", "pattern", "*", "action", "deny"),
                java.util.Map.of("permission", "external_directory", "pattern", "*", "action", "deny"),
                java.util.Map.of("permission", "loopper_internal_generation_submit_candidate",
                        "pattern", "*", "action", "allow"));
        assertThat(rules).noneMatch(rule -> "allow".equals(rule.get("action"))
                && (java.util.Set.of("read", "glob", "grep", "question")
                        .contains(rule.get("permission"))
                || "github_*".equals(rule.get("permission"))
                || "loopper_internal_generation_*".equals(rule.get("permission"))));
    }

    @Test
    void packageDesignCandidateAllowsReadEvidenceAndOnlyTheExactInternalSubmissionTool() {
        var rules = OpenCodePermissionPolicy.rules(
                OpenCodeClient.SessionProfile.PACKAGE_DESIGN_CANDIDATE_READ_ONLY,
                java.util.List.of("github", "loopper_internal_generation"),
                "loopper_internal_generation");

        assertThat(rules).contains(
                java.util.Map.of("permission", "read", "pattern", "*", "action", "allow"),
                java.util.Map.of("permission", "glob", "pattern", "*", "action", "allow"),
                java.util.Map.of("permission", "grep", "pattern", "*", "action", "allow"),
                java.util.Map.of("permission", "loopper_internal_generation_submit_candidate",
                        "pattern", "*", "action", "allow"));
        assertThat(rules.stream().filter(rule -> "allow".equals(rule.get("action")))
                .map(rule -> rule.get("permission")).toList())
                .containsExactly("read", "glob", "grep", "read",
                        "loopper_internal_generation_submit_candidate");
    }

    @Test
    void interactivePackageDesignCandidateAddsQuestionWithoutUserMcp() {
        var rules = OpenCodePermissionPolicy.rules(
                OpenCodeClient.SessionProfile.PACKAGE_DESIGN_CANDIDATE_INTERACTIVE_READ_ONLY,
                java.util.List.of("github", "loopper_internal_generation"),
                "loopper_internal_generation");

        assertThat(rules).contains(
                java.util.Map.of("permission", "read", "pattern", "*", "action", "allow"),
                java.util.Map.of("permission", "glob", "pattern", "*", "action", "allow"),
                java.util.Map.of("permission", "grep", "pattern", "*", "action", "allow"),
                java.util.Map.of("permission", "question", "pattern", "*", "action", "allow"),
                java.util.Map.of("permission", "loopper_internal_generation_submit_candidate",
                        "pattern", "*", "action", "allow"));
        assertThat(rules.stream().filter(rule -> "allow".equals(rule.get("action")))
                .map(rule -> rule.get("permission")).toList())
                .containsExactly("read", "glob", "grep", "question", "read",
                        "loopper_internal_generation_submit_candidate");
    }

    @Test
    void ordinaryReadOnlyRolesKeepUserMcpButNeverReceiveTheInternalSubmissionTool() {
        var rules = OpenCodePermissionPolicy.rules(OpenCodeClient.SessionProfile.JUDGE_READ_ONLY,
                java.util.List.of("github", "loopper_internal_generation"),
                "loopper_internal_generation");

        assertThat(rules).contains(java.util.Map.of(
                "permission", "github_*", "pattern", "*", "action", "allow"));
        assertThat(rules).noneMatch(rule -> rule.get("permission").startsWith("loopper_internal_generation"));
    }

    @Test
    void everyNonRouterRoleAllowsConfiguredMcpToolsWithoutRemovingItsBuiltInBoundary() {
        for (OpenCodeClient.SessionProfile profile : OpenCodeClient.SessionProfile.values()) {
            if (profile == OpenCodeClient.SessionProfile.ROUTER_NO_TOOLS
                    || profile == OpenCodeClient.SessionProfile.DECOMPOSER_CANDIDATE_READ_ONLY
                    || profile == OpenCodeClient.SessionProfile.ACCEPTANCE_CLOSED_CHOICE_CANDIDATE_NO_TOOLS
                    || profile == OpenCodeClient.SessionProfile.PACKAGE_DESIGN_CANDIDATE_READ_ONLY
                    || profile == OpenCodeClient.SessionProfile.PACKAGE_DESIGN_CANDIDATE_INTERACTIVE_READ_ONLY) continue;
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
