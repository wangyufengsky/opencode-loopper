package io.opencode.loopper.service;

import java.util.List;

/** Immutable semantic inputs and diagnostics for deterministic acceptance planning. */
final class DesignerAcceptancePlanning {
    static final String CONTRACT_VERSION_V5 = "DESIGN_ACCEPTANCE_V5";
    static final String CONTRACT_VERSION_V6 = "DESIGN_ACCEPTANCE_V6";
    static final String CONTRACT_VERSION_V7 = "DESIGN_ACCEPTANCE_V7";

    private DesignerAcceptancePlanning() { }

    enum FactKind { SCENARIO, REVIEW, SCOPE, DELIVERABLE, POLICY, DEPENDENCY }

    enum CoverageMode { AUTOMATED, BOTH, JUDGE, UNRESOLVED }

    enum MutationOperation { WRITE, DELETE_REQUEST, MOVE_SOURCE, MOVE_DESTINATION }

    enum MutationPathKind { EXACT_PATH, PATH_RULE }

    enum MutationSourceKind { REQUIREMENT, DESIGN_DELIVERABLE, DESIGN_SCOPE }

    record Catalog(String contractVersion, String workPackageId, int designRevision, String designSha256,
                   boolean controlledFormat, List<Fact> facts, List<StageHint> stageHints,
                   List<MutationObligation> mutationObligations, List<String> mutationIssues,
                   List<String> issues) {
        Catalog(String contractVersion, String workPackageId, int designRevision, String designSha256,
                boolean controlledFormat, List<Fact> facts, List<StageHint> stageHints,
                List<String> issues) {
            this(contractVersion, workPackageId, designRevision, designSha256, controlledFormat,
                    facts, stageHints, List.of(), List.of(), issues);
        }

        Catalog {
            facts = facts == null ? List.of() : List.copyOf(facts);
            stageHints = stageHints == null ? List.of() : List.copyOf(stageHints);
            mutationObligations = mutationObligations == null ? List.of() : List.copyOf(mutationObligations);
            mutationIssues = mutationIssues == null ? List.of() : List.copyOf(mutationIssues);
            issues = issues == null ? List.of() : List.copyOf(issues);
        }
    }

    record MutationObligation(int index, String obligationId, String pathRule, MutationPathKind pathKind,
                              MutationOperation operation,
                              MutationSourceKind sourceKind, String sourceRef, String sourceExcerpt,
                              String sourceSha256, List<Integer> candidateStageIndexes,
                              List<Integer> assignedStageIndexes) {
        MutationObligation(int index, String obligationId, String pathRule, MutationOperation operation,
                           MutationSourceKind sourceKind, String sourceRef, String sourceExcerpt,
                           String sourceSha256, List<Integer> candidateStageIndexes,
                           List<Integer> assignedStageIndexes) {
            this(index, obligationId, pathRule,
                    pathRule != null && (pathRule.contains("*") || pathRule.contains("?")
                            || pathRule.contains("[") || pathRule.contains("{"))
                            ? MutationPathKind.PATH_RULE : MutationPathKind.EXACT_PATH,
                    operation, sourceKind, sourceRef, sourceExcerpt, sourceSha256,
                    candidateStageIndexes, assignedStageIndexes);
        }

        MutationObligation {
            pathKind = pathKind == null ? MutationPathKind.PATH_RULE : pathKind;
            candidateStageIndexes = candidateStageIndexes == null ? List.of() : List.copyOf(candidateStageIndexes);
            assignedStageIndexes = assignedStageIndexes == null ? List.of() : List.copyOf(assignedStageIndexes);
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

    record StageHint(String title, String objective, List<String> includedReferences,
                     List<String> dependencyReferences, List<Integer> factIndexes,
                     List<Integer> dependsOnIndexes, List<String> responsiblePaths) {
        StageHint(String title, String objective, List<String> includedReferences,
                  List<String> dependencyReferences, List<Integer> factIndexes,
                  List<Integer> dependsOnIndexes) {
            this(title, objective, includedReferences, dependencyReferences, factIndexes,
                    dependsOnIndexes, List.of());
        }

        StageHint {
            includedReferences = includedReferences == null ? List.of() : List.copyOf(includedReferences);
            dependencyReferences = dependencyReferences == null ? List.of() : List.copyOf(dependencyReferences);
            factIndexes = factIndexes == null ? List.of() : List.copyOf(factIndexes);
            dependsOnIndexes = dependsOnIndexes == null ? List.of() : List.copyOf(dependsOnIndexes);
            responsiblePaths = responsiblePaths == null ? List.of() : List.copyOf(responsiblePaths);
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
