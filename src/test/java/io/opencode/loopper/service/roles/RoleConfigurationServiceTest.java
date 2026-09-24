package io.opencode.loopper.service.roles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.opencode.loopper.persistence.RoleConfigurationMapper;
import io.opencode.loopper.runtime.OpenCodeClient;
import io.opencode.loopper.service.ConflictException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RoleConfigurationServiceTest {
    private final RoleConfigurationService roles = new RoleConfigurationService(
            mock(RoleConfigurationMapper.class), new ObjectMapper());

    @Test
    void implementationIntersectionStartsWithDenyAllAndPreservesProtectedSpecificDenialsLast() {
        var role = role("IMPLEMENTATION", List.of("bash"), List.of(), List.of());
        var baseline = List.of(rule("bash", "*git*commit*", "deny"),
                rule("external_directory", "*", "deny"), rule("todowrite", "*", "allow"));

        var compiled = roles.compileNarrowedPermissions(role, baseline, Set.of(), "loopper-internal-1");

        assertThat(compiled.getFirst()).isEqualTo(rule("*", "*", "deny"));
        assertThat(compiled).contains(rule("bash", "*", "allow"));
        assertThat(compiled.getLast()).isEqualTo(rule("external_directory", "*", "deny"));
        assertThat(compiled).doesNotContain(rule("todowrite", "*", "allow"));
    }

    @Test
    void intersectionDropsBroadInternalAndExternalWildcardGrants() {
        var role = role("GENERAL_READ_ONLY", List.of("read"), List.of(), List.of());
        var baseline = List.of(rule("*", "*", "deny"), rule("read", "*", "allow"),
                rule("aicoding_*", "*", "allow"), rule("external_*", "*", "allow"),
                rule("external_directory", "*", "deny"));

        var compiled = roles.compileNarrowedPermissions(role, baseline, Set.of(), "loopper-internal-1");

        assertThat(compiled).contains(rule("read", "*", "allow"));
        assertThat(compiled).doesNotContain(rule("aicoding_*", "*", "allow"),
                rule("external_*", "*", "allow"));
        assertThat(compiled).contains(rule("external_directory", "*", "deny"));
    }

    @Test
    void missingRequiredMcpStopsNewSessionEvenWhenBaselineModeIsSelected() {
        var role = new RoleConfigurationService.ResolvedRole("test.role", "revision", "sha",
                "GENERAL_READ_ONLY", "GENERAL_READ_ONLY", Map.of(), "BASELINE", List.of(),
                List.of("@loopper-assist/read_project"), List.of("@loopper-assist/read_project"),
                "INHERIT_WORKFLOW", "WORKFLOW_ADAPTER");
        assertThatThrownBy(() -> roles.compileNarrowedPermissions(role, List.of(), Set.of(), "internal"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("缺少角色要求的 MCP 工具");
    }

    @Test
    void discoveredRequiredToolStillCannotExceedAdapterPermissionCeiling() {
        var role = role("GENERAL_READ_ONLY", List.of(),
                List.of("@loopper-assist/get_execution_context"),
                List.of("@loopper-assist/get_execution_context"));
        assertThatThrownBy(() -> roles.compileNarrowedPermissions(role,
                List.of(rule("*", "*", "deny")), Set.of("server_assist_get_execution_context"), "server"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("超出当前适配器授权");
    }

    @Test
    void exactMcpDeclarationNarrowsServerWildcardAfterToolDiscovery() {
        var role = role("GENERAL_READ_ONLY", List.of(),
                List.of("@loopper-assist/get_execution_context"), List.of());
        var compiled = roles.compileNarrowedPermissions(role,
                List.of(rule("*", "*", "deny"), rule("server_assist_*", "*", "allow")),
                Set.of("server_assist_get_execution_context"), "server");
        assertThat(compiled).contains(rule("server_assist_get_execution_context", "*", "allow"));
        assertThat(compiled).doesNotContain(rule("server_assist_*", "*", "allow"));
    }

    @Test
    void implementationKeepsSpecificNativeDenialsAndAllowsOnlyDeclaredExactMcpAfterBroadDenial() {
        var role = role("IMPLEMENTATION", List.of("bash"),
                List.of("@loopper-assist/get_execution_context"),
                List.of("@loopper-assist/get_execution_context"));
        var broadDeny = rule("server_assist_*", "*", "deny");
        var exactAllow = rule("server_assist_get_execution_context", "*", "allow");
        var protectedBashDeny = rule("bash", "*git*push*", "deny");
        var compiled = roles.compileNarrowedPermissions(role,
                List.of(rule("external_directory", "*", "deny"), protectedBashDeny,
                        broadDeny, exactAllow),
                Set.of("server_assist_get_execution_context"), "server");

        assertThat(compiled).contains(rule("*", "*", "deny"), rule("bash", "*", "allow"),
                protectedBashDeny, broadDeny, exactAllow);
        assertThat(compiled.indexOf(rule("bash", "*", "allow"))).isLessThan(compiled.indexOf(protectedBashDeny));
        assertThat(compiled.indexOf(broadDeny)).isLessThan(compiled.indexOf(exactAllow));
        assertThat(compiled).doesNotContain(rule("server_assist_*", "*", "allow"));
    }

    @Test
    void previewMarksServerOwnedSubmissionAndDescriptionAsRequired() {
        var tools = RoleReadService.systemRequiredTools(
                OpenCodeClient.SessionProfile.DECOMPOSER_CANDIDATE_READ_ONLY);
        assertThat(tools).extracting(RoleReadService.PreviewTool::name)
                .containsExactly("@loopper-internal/submit_decomposition_plan",
                        "@loopper-internal/describe_submission_contract");
        assertThat(tools).allSatisfy(tool -> {
            assertThat(tool.source()).isEqualTo("SYSTEM_REQUIRED");
            assertThat(tool.required()).isTrue();
            assertThat(tool.available()).isFalse();
        });
        assertThat(RoleReadService.systemRequiredTools(OpenCodeClient.SessionProfile.GENERAL_READ_ONLY))
                .isEmpty();
    }

    private static RoleConfigurationService.ResolvedRole role(String profile, List<String> nativeTools,
            List<String> mcpTools, List<String> required) {
        return new RoleConfigurationService.ResolvedRole("test.role", "revision", "sha", profile, profile,
                Map.of(), "INTERSECT", nativeTools, mcpTools, required,
                "INHERIT_WORKFLOW", "WORKFLOW_ADAPTER");
    }

    private static OpenCodeClient.SessionPermissionRule rule(String permission, String pattern, String action) {
        return new OpenCodeClient.SessionPermissionRule(permission, pattern, action);
    }
}
