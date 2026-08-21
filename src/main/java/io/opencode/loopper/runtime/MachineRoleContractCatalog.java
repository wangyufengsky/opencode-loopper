package io.opencode.loopper.runtime;

import java.util.Map;

/**
 * Machine-role contract cards. The executable JSON Schema and server compiler are authoritative;
 * prompts use these short cards so the model only has to decide business semantics.
 */
public final class MachineRoleContractCatalog {
    public static final String CONTRACT_VERSION = "2026-08-semantic-v5";

    private static final Map<String, String> CARDS = Map.of(
            "DECOMPOSER", "Return business goal, constraints, 1-6 vertical work packages, index dependencies, and RQ coverage. Do not assign ids or status.",
            "COMPILER", "Given frozen DesignFacts and verification capabilities, suggest dependency-ordered groups and optional indexed capability preferences. Do not decide outcome or gaps. Do not invent commands, paths, ids, criteria, source refs, or executable verifier fields.",
            "JUDGE", "Return one verdict and one non-empty reason. JSON is preferred; explicit VERDICT/REASON labels are accepted.",
            "DESIGNER", "Describe scope and delivery, EARS-style acceptance scenarios, optional human review, constraints, and stage dependencies in the controlled Markdown sections. Do not write LoopSpec JSON, internal ids, or executable argv.");

    private MachineRoleContractCatalog() { }

    public static String card(String role) {
        String card = CARDS.get(role);
        if (card == null) throw new IllegalArgumentException("Unknown machine role: " + role);
        return "Machine role contract " + CONTRACT_VERSION + ": " + card;
    }
}
