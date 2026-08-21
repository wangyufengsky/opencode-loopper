package io.opencode.loopper.service;

import java.util.List;

/** Immutable semantic inputs and diagnostics for deterministic acceptance planning. */
final class DesignerAcceptancePlanning {
    static final String CONTRACT_VERSION = "DESIGN_ACCEPTANCE_V5";

    private DesignerAcceptancePlanning() { }

    enum FactKind { SCENARIO, REVIEW, SCOPE, DELIVERABLE, POLICY, DEPENDENCY }

    enum CoverageMode { AUTOMATED, BOTH, JUDGE, UNRESOLVED }

    record Catalog(String contractVersion, String workPackageId, int designRevision, String designSha256,
                   boolean controlledFormat, List<Fact> facts, List<StageHint> stageHints,
                   List<String> issues) {
        Catalog {
            facts = facts == null ? List.of() : List.copyOf(facts);
            stageHints = stageHints == null ? List.of() : List.copyOf(stageHints);
            issues = issues == null ? List.of() : List.copyOf(issues);
        }
    }

    record Fact(int index, FactKind kind, String title, String condition, String action,
                String expected, String invariant, String detail, String sourceRef,
                String sourceExcerpt, String sourceSha256) {
        String acceptanceText() {
            if (kind == FactKind.REVIEW) return detail;
            StringBuilder text = new StringBuilder();
            if (condition != null && !condition.isBlank()) text.append("当").append(condition).append("时，");
            if (action != null && !action.isBlank()) text.append("执行").append(action).append("后，");
            text.append("应").append(expected == null || expected.isBlank() ? title : expected);
            if (invariant != null && !invariant.isBlank()) text.append("；并保持").append(invariant);
            return text.toString();
        }
    }

    record StageHint(String title, String objective, List<Integer> factIndexes, List<Integer> dependsOnIndexes) {
        StageHint {
            factIndexes = factIndexes == null ? List.of() : List.copyOf(factIndexes);
            dependsOnIndexes = dependsOnIndexes == null ? List.of() : List.copyOf(dependsOnIndexes);
        }
    }

    record CapabilityCatalog(String contractVersion, List<Capability> capabilities, List<String> issues) {
        CapabilityCatalog {
            capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
            issues = issues == null ? List.of() : List.copyOf(issues);
        }
    }

    record Capability(int index, String kind, String label, List<String> command,
                      List<Integer> coversFactIndexes, List<String> testTargets,
                      boolean deterministic, boolean mandatory, int strength) {
        Capability {
            command = command == null ? List.of() : List.copyOf(command);
            coversFactIndexes = coversFactIndexes == null ? List.of() : List.copyOf(coversFactIndexes);
            testTargets = testTargets == null ? List.of() : List.copyOf(testTargets);
        }
    }

    record SolverDiagnostics(String algorithm, long exploredNodes, boolean fallbackUsed,
                             int factCount, int capabilityCount, int selectedCapabilityCount,
                             List<Integer> uncoveredFactIndexes, List<String> normalizations) {
        SolverDiagnostics {
            uncoveredFactIndexes = uncoveredFactIndexes == null ? List.of() : List.copyOf(uncoveredFactIndexes);
            normalizations = normalizations == null ? List.of() : List.copyOf(normalizations);
        }
    }

    record ScenarioView(int factIndex, String title, CoverageMode coverage, List<String> capabilities) {
        ScenarioView {
            capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        }
    }
}
