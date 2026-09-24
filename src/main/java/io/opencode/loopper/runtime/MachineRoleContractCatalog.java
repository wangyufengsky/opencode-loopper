package io.opencode.loopper.runtime;

import io.opencode.loopper.service.roles.RolePromptResources;
import java.util.Map;

/**
 * Machine-role contract cards. The executable JSON Schema and server compiler are authoritative;
 * prompts use these short cards so the model only has to decide business semantics.
 */
public final class MachineRoleContractCatalog {
    public static final String CONTRACT_VERSION = "2026-08-semantic-v6";
    public static final String CLOSED_CHOICE_CONTRACT_VERSION = "2026-08-semantic-v7";
    public static final String LEGACY_COMPILER_CONTRACT_VERSION = "2026-08-semantic-v5";

    private static final Map<String, String> CARD_IDS = Map.of(
            "DECOMPOSER", "machine-role.decomposer",
            "COMPILER", "machine-role.compiler",
            "JUDGE", "machine-role.judge",
            "DESIGNER", "machine-role.designer");

    private MachineRoleContractCatalog() { }

    public static String card(String role) {
        String id = CARD_IDS.get(role);
        if (id == null) throw new IllegalArgumentException("Unknown machine role: " + role);
        return RolePromptResources.read(id);
    }

    public static String packageDesignerCard(boolean candidateChannel) {
        return candidateChannel ? RolePromptResources.read("machine-role.package-designer") : card("DESIGNER");
    }

    public static String legacyCompilerCard() {
        return RolePromptResources.read("machine-role.legacy-compiler");
    }

    public static String legacySemanticCompilerCard() {
        return RolePromptResources.read("machine-role.legacy-semantic-compiler");
    }

    public static String closedChoiceCompilerCard() {
        return RolePromptResources.read("machine-role.closed-choice-compiler");
    }
}
