package io.opencode.loopper.service.roles;

import static org.assertj.core.api.Assertions.assertThat;

import io.opencode.loopper.runtime.OpenCodeClient;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class RoleBuiltinManifestTest {
    @Test
    void everyLegacySessionProfileHasAnExplicitBuiltInSlotAndFrozenPromptOwnership() throws Exception {
        String yaml;
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream("roles/builtin.yaml")) {
            assertThat(stream).isNotNull();
            yaml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        RoleManifest.Document document = new RoleArchive().parseYaml(yaml);
        Set<String> adapters = document.slots().stream().map(RoleManifest.Slot::adapterProfile)
                .collect(Collectors.toSet());
        assertThat(adapters).containsAll(Arrays.stream(OpenCodeClient.SessionProfile.values())
                .map(Enum::name).toList());
        assertThat(document.slots().stream().map(RoleManifest.Slot::slot).toList())
                .contains("JUDGE_REQUIREMENT_FINALIZER", "JUDGE_RISK_FINALIZER",
                        "GENERAL_READ_ONLY_COMMIT_MESSAGE", "GENERAL_READ_ONLY_MERGE_ADVISOR",
                        "GENERAL_READ_ONLY_REQUIREMENT", "GENERAL_READ_ONLY_DESIGNER");
        for (RoleManifest.Role role : document.roles()) {
            assertThat(role.modelPolicy()).isEqualTo("INHERIT_WORKFLOW");
            assertThat(RolePromptResources.defaultsForRole(role.roleId()).keySet())
                    .doesNotContain("role-pack.2026-08-dynamic-v7.software-java.definition");
        }
    }
}
