package io.opencode.loopper.service.roles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opencode.loopper.service.RolePackRegistry;
import java.util.HashSet;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RolePromptResourcesTest {
    @Test void catalogContainsCompleteUnmodifiedFragments() {
        assertThat(RolePromptResources.catalog()).containsKeys(
                "machine-role.designer", "role-pack.2026-08-dynamic-v7.software-java.implementation",
                "role-pack.2026-08-dynamic-v7.software-java.definition",
                "ppt.automatic", "knowledge.research", "commit.subject");
        assertThat(RolePromptResources.read("knowledge.research")).endsWith("\n");
        assertThat(RolePromptResources.read("ppt.base")).doesNotEndWith("\n");
        assertThat(RolePromptResources.read("role-pack.2026-08-dynamic-v7.software-java.compiler-example"))
                .startsWith("{\"outcome\":\"COMPILED\"");
        assertThat(RolePromptResources.readRolePack("software-java", RolePackRegistry.VERSION, "implementation"))
                .isEqualTo(RolePromptResources.read("role-pack.2026-08-dynamic-v7.software-java.implementation"));
        assertThat(new HashSet<>(RolePromptResources.catalog().values()))
                .hasSize(RolePromptResources.catalog().size());
        assertThatThrownBy(() -> RolePromptResources.catalog().put("new", "text"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> RolePromptResources.read("unknown.fragment"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RolePromptResources.readRolePack("software-java", "2026-08-dynamic-v6", "implementation"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rolePackDefinitionsRequireExactBundledIdAndVersion() {
        RolePackRegistry registry = new RolePackRegistry();
        assertThat(registry.byIdAndVersion("software-java", RolePackRegistry.VERSION).displayName())
                .isEqualTo("Java 软件设计师");
        assertThatThrownBy(() -> registry.byIdAndVersion("software-java", "2026-08-dynamic-v6"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.byIdAndVersion("missing", RolePackRegistry.VERSION))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void scopedFragmentsRestoreOnNestedExitAndFailure() {
        String baseline = RolePromptResources.read("machine-role.designer");
        String definition = RolePromptResources.read("role-pack.2026-08-dynamic-v7.software-java.definition");
        RolePromptResources.withFragments(Map.of(
                "machine-role.designer", "outer",
                "role-pack.2026-08-dynamic-v7.software-java.definition", "tampered"), () -> {
            assertThat(RolePromptResources.read("machine-role.designer")).isEqualTo("outer");
            var child = new java.util.concurrent.atomic.AtomicReference<String>();
            Thread thread = Thread.ofPlatform().start(() -> child.set(RolePromptResources.read("machine-role.designer")));
            try { thread.join(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
            assertThat(child.get()).isEqualTo(baseline);
            assertThat(RolePromptResources.catalog().get("machine-role.designer")).isEqualTo(baseline);
            assertThat(RolePromptResources.read("role-pack.2026-08-dynamic-v7.software-java.definition"))
                    .isEqualTo(definition);
            assertThat(RolePromptResources.withFragments(Map.of("machine-role.designer", "inner"),
                    () -> RolePromptResources.read("machine-role.designer"))).isEqualTo("inner");
            assertThat(RolePromptResources.read("machine-role.designer")).isEqualTo("outer");
            assertThatThrownBy(() -> RolePromptResources.withFragments(Map.of("machine-role.designer", "failed"),
                    () -> { throw new IllegalStateException("failure"); })).isInstanceOf(IllegalStateException.class);
            assertThat(RolePromptResources.read("machine-role.designer")).isEqualTo("outer");
            return null;
        });
        assertThat(RolePromptResources.read("machine-role.designer")).isEqualTo(baseline);
    }

    @Test void builtInRolesOwnTheirSharedPromptSegmentsExplicitly() {
        assertThat(RolePromptResources.defaultsForRole("builtin.decomposer"))
                .containsKeys("machine-role.decomposer",
                        "prompt.v1.DesignerDecompositionPromptFactory.block01.segment0",
                        "role-pack.2026-08-dynamic-v7.default.decomposer");
        assertThat(RolePromptResources.defaultsForRole("builtin.implementation"))
                .containsKeys("prompt.v1.TaskExecutionPromptFactory.workspace-guidance",
                        "role-pack.2026-08-dynamic-v7.software-java.implementation")
                .doesNotContainKey("role-pack.2026-08-dynamic-v7.software-java.definition");
    }
}
