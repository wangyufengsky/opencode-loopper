package io.opencode.loopper.runtime;

import io.opencode.loopper.service.roles.RoleConfigurationService;
import io.opencode.loopper.service.roles.RolePromptResources;
import java.util.*;
import org.junit.jupiter.api.Test;
import static io.opencode.loopper.runtime.OpenCodeClient.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConfiguredRoleRuntimeTest {
    @Test void onlyProgramFragmentsUseTheFrozenRevisionAndUserFactsStayVerbatim() {
        var configurations = mock(RoleConfigurationService.class);
        var runtime = new ConfiguredRoleRuntime(configurations);
        var role = mock(RoleConfigurationService.ResolvedRole.class);
        String key = "machine-role.designer", original = RolePromptResources.read(key);
        when(configurations.slotFor(SessionProfile.IMPLEMENTATION, null)).thenReturn("IMPLEMENTATION");
        when(configurations.resolveFrozen(new RoleConfigurationService.OwnerRef("TASK", "task-1"), "IMPLEMENTATION"))
                .thenReturn(Optional.of(role));
        when(role.fragments()).thenReturn(Map.of(key, "新的角色说明"));
        String rendered = runtime.render("TASK", "task-1", SessionProfile.IMPLEMENTATION, null,
                () -> RolePromptResources.read(key) + "\n用户原文：" + original);
        assertThat(rendered).isEqualTo("新的角色说明\n用户原文：" + original);
        assertThat(RolePromptResources.read(key)).isEqualTo(original);
        verify(configurations, never()).resolveActive(anyString());
    }

    @Test void oldOwnersKeepTheLegacyPromptAndExceptionsDoNotLeakARevisionToOtherWork() {
        var configurations = mock(RoleConfigurationService.class);
        var runtime = new ConfiguredRoleRuntime(configurations);
        String key = "machine-role.designer", original = RolePromptResources.read(key);
        when(configurations.slotFor(SessionProfile.IMPLEMENTATION, null)).thenReturn("IMPLEMENTATION");
        when(configurations.resolveFrozen(any(), anyString())).thenReturn(Optional.empty());
        assertThat(runtime.render("TASK", "old", SessionProfile.IMPLEMENTATION, null,
                () -> RolePromptResources.read(key))).isEqualTo(original);
        assertThatThrownBy(() -> RolePromptResources.withFragments(Map.of(key, "changed"), () -> {
            throw new IllegalStateException("failed assembly");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(RolePromptResources.read(key)).isEqualTo(original);
    }

    @Test void internalToolChecksUseTheOrderedFrozenPolicy() {
        List<SessionPermissionRule> policy = List.of(new SessionPermissionRule("*", "*", "deny"),
                new SessionPermissionRule("internal_ppt_read_source", "*", "allow"),
                new SessionPermissionRule("internal_ppt_export", "*", "allow"),
                new SessionPermissionRule("internal_ppt_export", "*", "deny"));
        assertThat(ConfiguredRoleRuntime.allowed(policy, "internal_ppt_read_source")).isTrue();
        assertThat(ConfiguredRoleRuntime.allowed(policy, "internal_ppt_export")).isFalse();
        assertThat(ConfiguredRoleRuntime.allowed(policy, "other_ppt_read_source")).isFalse();
    }

    @Test void directInternalToolCheckUsesFrozenSessionAndKeepsLegacySessions() {
        var configurations = mock(RoleConfigurationService.class);
        var runtime = new ConfiguredRoleRuntime(configurations);
        var owner = new RoleConfigurationService.OwnerRef("SOURCE_TEMPLATE_MODEL_RUN", "model-1");
        var context = new RoleConfigurationService.RoleContext(owner, "SOURCE_DETAILED_DESIGN_NO_TOOLS");
        List<SessionPermissionRule> policy = List.of(new SessionPermissionRule("*", "*", "deny"),
                new SessionPermissionRule("private_read_source_template_file", "*", "allow"));
        when(configurations.sessionSnapshot("new-session")).thenReturn(Optional.of(
                new RoleConfigurationService.SessionSnapshot("new-session", context, "rev-1", "hash-1",
                        SessionProfile.SOURCE_DETAILED_DESIGN_NO_TOOLS.name(), policy, "policy-hash", null)));
        runtime.requireInternalTool("new-session", "private", "read_source_template_file");
        assertThatThrownBy(() -> runtime.requireInternalTool("new-session", "private", "get_source_design_work"))
                .isInstanceOf(io.opencode.loopper.service.ConflictException.class)
                .hasMessageContaining("冻结许可");
        runtime.requireInternalTool("legacy-session", "private", "get_source_design_work");
    }
}
