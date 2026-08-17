package io.opencode.loopper.runtime;

import java.util.Map;

/**
 * Machine-role contract cards. The executable JSON Schema and server compiler are authoritative;
 * prompts use these short cards so the model only has to decide business semantics.
 */
public final class MachineRoleContractCatalog {
    public static final String CONTRACT_VERSION = "2026-08-semantic-v1";

    private static final Map<String, String> CARDS = Map.of(
            "DECOMPOSER", "Return business goal, constraints, 1-6 vertical work packages, index dependencies, and RQ coverage. Do not assign ids or status.",
            "COMPILER", "Return semantic stages, observable criteria with DS-L source refs, and evidence intentions. Do not assign acceptance ids, workPackageId, criterionIds, or testTargets.",
            "JUDGE", "Return one verdict and one non-empty reason. JSON is preferred; explicit VERDICT/REASON labels are accepted.",
            "DESIGNER", "Describe business outcomes, scope, exceptions, and observable acceptance. Do not write LoopSpec JSON or internal verifier ids.");

    private MachineRoleContractCatalog() { }

    public static String card(String role) {
        String card = CARDS.get(role);
        if (card == null) throw new IllegalArgumentException("Unknown machine role: " + role);
        return "Machine role contract " + CONTRACT_VERSION + ": " + card;
    }
}
