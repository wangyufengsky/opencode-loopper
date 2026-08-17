package io.opencode.loopper.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MachineRoleContractCatalogTest {
    @Test
    void compactSchemasRoleCardsAndHumanContractShareTheSemanticContractVersion() throws Exception {
        assertThat(MachineRoleContractCatalog.card("DECOMPOSER"))
                .contains(MachineRoleContractCatalog.CONTRACT_VERSION, "Do not assign ids");
        assertThat(MachineRoleContractCatalog.card("COMPILER"))
                .contains("DS-L", "testTargets");
        assertThat(OpenCodeStructuredSchemas.schema(OpenCodeStructuredSchemas.DECOMPOSITION_SEMANTIC_V2))
                .containsEntry("type", "object");
        assertThat(OpenCodeStructuredSchemas.schema(OpenCodeStructuredSchemas.PACKAGE_COMPILATION_SEMANTIC_V3))
                .containsEntry("type", "object");
        assertThat(Files.readString(Path.of("docs/ai-role-contracts.md")))
                .contains(MachineRoleContractCatalog.CONTRACT_VERSION,
                        "MachineRoleContractCatalog", "OpenCodeStructuredSchemas");
    }
}
