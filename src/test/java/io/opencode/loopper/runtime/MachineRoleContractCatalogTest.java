package io.opencode.loopper.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MachineRoleContractCatalogTest {
    @Test
    void compactSchemasRoleCardsAndHumanContractShareTheSemanticContractVersion() throws Exception {
        assertThat(MachineRoleContractCatalog.card("DECOMPOSER"))
                .contains(MachineRoleContractCatalog.CONTRACT_VERSION, "Do not assign ids");
        assertThat(MachineRoleContractCatalog.card("COMPILER"))
                .contains("DesignFacts", "verification capabilities", "Do not invent commands");
        assertThat(OpenCodeStructuredSchemas.schema(OpenCodeStructuredSchemas.DECOMPOSITION_SEMANTIC_V2))
                .containsEntry("type", "object");
        assertThat(OpenCodeStructuredSchemas.schema(OpenCodeStructuredSchemas.PACKAGE_COMPILATION_SEMANTIC_V3))
                .containsEntry("type", "object");
        assertThat(OpenCodeStructuredSchemas.schema(OpenCodeStructuredSchemas.PACKAGE_ACCEPTANCE_BINDING_V5))
                .containsEntry("type", "object");
        assertThat(Files.readString(Path.of("docs/ai-role-contracts.md")))
                .contains(MachineRoleContractCatalog.CONTRACT_VERSION,
                        "MachineRoleContractCatalog", "OpenCodeStructuredSchemas");
    }

    @Test
    @SuppressWarnings("unchecked")
    void directCompilerSchemaAllowsSixStagesAndSemanticRepairHasItsOwnPatchContract() {
        Map<String, Object> semantic = OpenCodeStructuredSchemas.schema(
                OpenCodeStructuredSchemas.PACKAGE_COMPILATION_SEMANTIC_V3);
        Map<String, Object> properties = (Map<String, Object>) semantic.get("properties");
        Map<String, Object> stages = (Map<String, Object>) properties.get("stages");
        assertThat(stages.get("maxItems")).isEqualTo(6);

        Map<String, Object> patch = OpenCodeStructuredSchemas.schema(
                OpenCodeStructuredSchemas.AI_SEMANTIC_PATCH_V1);
        Map<String, Object> patchProperties = (Map<String, Object>) patch.get("properties");
        Map<String, Object> patches = (Map<String, Object>) patchProperties.get("patches");
        assertThat(patches).containsEntry("minItems", 1).containsEntry("maxItems", 16);
    }

    @Test
    @SuppressWarnings("unchecked")
    void acceptanceBindingSchemaCarriesOnlyIndexesAndAdvisoryGroups() {
        Map<String, Object> binding = OpenCodeStructuredSchemas.schema(
                OpenCodeStructuredSchemas.PACKAGE_ACCEPTANCE_BINDING_V5);
        Map<String, Object> properties = (Map<String, Object>) binding.get("properties");
        assertThat(properties).containsKeys("summary", "groupHints", "capabilityPreferences", "handoffSummary")
                .doesNotContainKeys("outcome", "designGaps", "stages", "commands", "verifiers", "sourceRefs");
        Map<String, Object> groups = (Map<String, Object>) properties.get("groupHints");
        assertThat(groups).containsEntry("maxItems", 6);
    }

    @Test
    @SuppressWarnings("unchecked")
    void taskRouterV2CarriesOnlyTheThreeClassificationLabels() {
        Map<String, Object> schema = OpenCodeStructuredSchemas.schema(
                OpenCodeStructuredSchemas.TASK_PROFILE_ROUTER_V2);
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");

        assertThat(schema.get("required")).isEqualTo(java.util.List.of(
                "intent", "artifactKinds", "complexity"));
        assertThat(properties).containsOnlyKeys("intent", "artifactKinds", "complexity");
        assertThat((Map<String, Object>) properties.get("artifactKinds")).containsEntry("maxItems", 1);
    }
}
