package io.opencode.loopper.service;

import io.opencode.loopper.domain.ImplementationKind;
import io.opencode.loopper.domain.LoopSpec;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Machine-readable semantic contracts shared by the Designer workflow and its deterministic compilers.
 * These records contain transport shape only; validation and compilation belong to focused policy classes.
 */
final class DesignerSemanticContracts {
    private DesignerSemanticContracts() { }

    public record GlobalConstraint(String text, List<String> requirementRefs) {
        public GlobalConstraint {
            requirementRefs = requirementRefs == null ? List.of() : List.copyOf(requirementRefs);
        }
    }

    public record DecomposedWorkPackage(String id, String title, String objective,
                                        List<String> scopeIn, List<String> scopeOut, List<String> dependencies,
                                        List<String> deliverables, List<String> acceptanceIntent,
                                        List<String> requirementRefs) {
        public DecomposedWorkPackage {
            scopeIn = scopeIn == null ? List.of() : List.copyOf(scopeIn);
            scopeOut = scopeOut == null ? List.of() : List.copyOf(scopeOut);
            dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
            deliverables = deliverables == null ? List.of() : List.copyOf(deliverables);
            acceptanceIntent = acceptanceIntent == null ? List.of() : List.copyOf(acceptanceIntent);
            requirementRefs = requirementRefs == null ? List.of() : List.copyOf(requirementRefs);
        }
    }

    public record RequirementCoverageMapping(String requirementRef, String targetType,
                                             String targetId, String rationale) {
        public RequirementCoverageMapping {
            targetType = targetType == null ? null : targetType.trim().toUpperCase();
        }
    }

    public record DependencyEvidence(String workPackageId, String dependsOn, String rationale) { }

    public record CompactWorkPackage(String title, String objective, List<String> scopeIn, List<String> scopeOut,
                                     List<String> deliverables, List<String> acceptanceIntent,
                                     List<JsonNode> dependsOn) {
        public CompactWorkPackage {
            scopeIn = scopeIn == null ? List.of() : List.copyOf(scopeIn);
            scopeOut = scopeOut == null ? List.of() : List.copyOf(scopeOut);
            deliverables = deliverables == null ? List.of() : List.copyOf(deliverables);
            acceptanceIntent = acceptanceIntent == null ? List.of() : List.copyOf(acceptanceIntent);
            dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        }
    }

    public record CompactCoverage(String requirementRef, String targetType, int targetIndex, String rationale) {
        public CompactCoverage {
            targetType = targetType == null ? null : targetType.trim().toUpperCase();
        }
    }

    public record CompactDecompositionPlan(String outcome, String normalizedGoal,
                                           List<JsonNode> globalConstraints,
                                           List<CompactWorkPackage> workPackages,
                                           List<CompactCoverage> coverage,
                                           List<JsonNode> designGaps, String reason) {
        CompactDecompositionPlan normalized() {
            return new CompactDecompositionPlan(outcome == null ? null : outcome.trim().toUpperCase(),
                    normalizedGoal, globalConstraints == null ? List.of() : List.copyOf(globalConstraints),
                    workPackages == null ? List.of() : List.copyOf(workPackages),
                    coverage == null ? List.of() : List.copyOf(coverage),
                    designGaps == null ? List.of() : List.copyOf(designGaps), reason);
        }
    }

    public record DecompositionPlanEnvelope(String status, String normalizedGoal,
                                            List<GlobalConstraint> globalConstraints,
                                            List<DecomposedWorkPackage> workPackages,
                                            List<RequirementCoverageMapping> coverageMappings,
                                            List<DependencyEvidence> dependencyEvidence,
                                            List<DesignGap> designGaps, String reason) {
        DecompositionPlanEnvelope normalized() {
            return new DecompositionPlanEnvelope(status == null ? null : status.trim().toUpperCase(), normalizedGoal,
                    globalConstraints == null ? List.of() : List.copyOf(globalConstraints),
                    workPackages == null ? List.of() : List.copyOf(workPackages),
                    coverageMappings == null ? List.of() : List.copyOf(coverageMappings),
                    dependencyEvidence == null ? List.of() : List.copyOf(dependencyEvidence),
                    designGaps == null ? List.of() : List.copyOf(designGaps), reason);
        }

        DecompositionEnvelope toEnvelope() {
            return new DecompositionEnvelope(status, normalizedGoal, globalConstraints, workPackages,
                    designGaps, reason).normalized();
        }
    }

    public record DecompositionEnvelope(String status, String normalizedGoal,
                                        List<GlobalConstraint> globalConstraints,
                                        List<DecomposedWorkPackage> workPackages,
                                        List<DesignGap> designGaps, String reason) {
        DecompositionEnvelope normalized() {
            return new DecompositionEnvelope(status == null ? null : status.trim().toUpperCase(), normalizedGoal,
                    globalConstraints == null ? List.of() : List.copyOf(globalConstraints),
                    workPackages == null ? List.of() : List.copyOf(workPackages),
                    designGaps == null ? List.of() : List.copyOf(designGaps), reason);
        }
    }

    public record CriterionSource(int stageIndex, String criterionId, String excerpt, List<String> excerpts) {
        public CriterionSource(int stageIndex, String criterionId, String excerpt) {
            this(stageIndex, criterionId, excerpt, blank(excerpt) ? List.of() : List.of(excerpt));
        }

        public CriterionSource {
            excerpts = excerpts == null || excerpts.isEmpty()
                    ? (blank(excerpt) ? List.of() : List.of(excerpt)) : List.copyOf(excerpts);
            excerpt = blank(excerpt) && !excerpts.isEmpty() ? excerpts.getFirst() : excerpt;
        }
    }

    public record DesignGap(DesignGapCode code, String detail) { }

    public enum DesignGapCode {
        MISSING_OBSERVABLE_OUTCOME, MISSING_EXCEPTION_SEMANTICS, MISSING_SCOPE, MISSING_ACCEPTANCE_INTENT,
        AMBIGUOUS_ACCEPTANCE_INTENT, VERIFICATION_CAPABILITY_UNAVAILABLE, LARGE_TASK_MODE_REQUIRED
    }

    public record AcceptanceGroupHint(String title, String objective, List<Integer> factIndexes,
                                      List<Integer> dependsOnHintIndexes) {
        public AcceptanceGroupHint {
            factIndexes = factIndexes == null ? List.of() : List.copyOf(factIndexes);
            dependsOnHintIndexes = dependsOnHintIndexes == null ? List.of() : List.copyOf(dependsOnHintIndexes);
        }
    }

    public record AcceptanceCapabilityPreference(int factIndex, List<Integer> capabilityIndexes) {
        public AcceptanceCapabilityPreference {
            capabilityIndexes = capabilityIndexes == null ? List.of() : List.copyOf(capabilityIndexes);
        }
    }

    /** V4 Compiler output: advisory grouping and preference only; the server owns all executable fields. */
    public record CompactAcceptanceBindingPlan(String outcome, String summary,
                                               List<AcceptanceGroupHint> groupHints,
                                               List<AcceptanceCapabilityPreference> capabilityPreferences,
                                               String handoffSummary, List<DesignGap> designGaps) {
        public CompactAcceptanceBindingPlan normalized() {
            return new CompactAcceptanceBindingPlan(outcome == null ? null : outcome.trim().toUpperCase(), summary,
                    groupHints == null ? List.of() : List.copyOf(groupHints),
                    capabilityPreferences == null ? List.of() : List.copyOf(capabilityPreferences),
                    handoffSummary, designGaps == null ? List.of() : List.copyOf(designGaps));
        }
    }

    public record CompilationEnvelope(String status, String summary, LoopSpec loopSpec,
                                      List<CriterionSource> criterionSources, List<DesignGap> designGaps) {
        CompilationEnvelope normalized() {
            return new CompilationEnvelope(status == null ? null : status.trim().toUpperCase(), summary,
                    loopSpec, criterionSources == null ? List.of() : List.copyOf(criterionSources),
                    designGaps == null ? List.of() : List.copyOf(designGaps));
        }
    }

    public record PackageCompilationEnvelope(String status, String summary,
                                             List<LoopSpec.StageSpec> stages,
                                             List<CriterionSource> criterionSources,
                                             String handoffSummary, List<DesignGap> designGaps) {
        PackageCompilationEnvelope normalized() {
            return new PackageCompilationEnvelope(status == null ? null : status.trim().toUpperCase(), summary,
                    stages == null ? List.of() : List.copyOf(stages),
                    criterionSources == null ? List.of() : List.copyOf(criterionSources), handoffSummary,
                    designGaps == null ? List.of() : List.copyOf(designGaps));
        }
    }

    public record PlannedStage(String objective, List<String> allowedPaths, List<String> forbiddenPaths,
                               List<String> deliverables, List<LoopSpec.VerifierSpec> verifiers,
                               LoopSpec.VerificationRuntime verificationRuntime,
                               ImplementationKind implementationKind, String workPackageId) {
        public PlannedStage {
            allowedPaths = allowedPaths == null ? List.of() : List.copyOf(allowedPaths);
            forbiddenPaths = forbiddenPaths == null ? List.of() : List.copyOf(forbiddenPaths);
            deliverables = deliverables == null ? List.of() : List.copyOf(deliverables);
            verifiers = verifiers == null ? List.of() : List.copyOf(verifiers);
        }
    }

    public record AcceptanceEvidenceMapping(int stageIndex, String criterionId, String description,
                                            String designerExcerpt, String verificationMode,
                                            String judgeRubric, String judgeOnlyReason,
                                            String verifierStrategy, List<String> testCommand,
                                            List<String> testTargets, List<String> designerExcerpts) {
        public AcceptanceEvidenceMapping(int stageIndex, String criterionId, String description,
                                         String designerExcerpt, String verificationMode,
                                         String judgeRubric, String judgeOnlyReason,
                                         String verifierStrategy, List<String> testCommand,
                                         List<String> testTargets) {
            this(stageIndex, criterionId, description, designerExcerpt, verificationMode, judgeRubric,
                    judgeOnlyReason, verifierStrategy, testCommand, testTargets,
                    blank(designerExcerpt) ? List.of() : List.of(designerExcerpt));
        }

        public AcceptanceEvidenceMapping {
            verificationMode = blank(verificationMode) ? "MACHINE" : verificationMode.trim().toUpperCase();
            testCommand = testCommand == null ? List.of() : List.copyOf(testCommand);
            testTargets = testTargets == null ? List.of() : List.copyOf(testTargets);
            designerExcerpts = designerExcerpts == null || designerExcerpts.isEmpty()
                    ? (blank(designerExcerpt) ? List.of() : List.of(designerExcerpt))
                    : List.copyOf(designerExcerpts);
            designerExcerpt = blank(designerExcerpt) && !designerExcerpts.isEmpty()
                    ? designerExcerpts.getFirst() : designerExcerpt;
        }
    }

    public record CompactCriterion(String description, List<String> sourceRefs,
                                   String judgeRubric, String judgeOnlyReason) {
        public CompactCriterion {
            sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
        }
    }

    public record CompactEvidence(String kind, List<String> command, List<Integer> covers,
                                  String successMarker, String path, Boolean requireChanges,
                                  List<String> allowedPaths, List<String> forbiddenPaths, Boolean forbidDeletes,
                                  String url, String httpMethod, Integer expectedStatus, String jsonPath,
                                  String expectedValue, String matchMode, String expectedContent,
                                  String expectedSha256, String sql, Integer expectedRowCount,
                                  List<LoopSpec.BrowserAssertion> assertions,
                                  List<LoopSpec.DocumentAssertion> documentAssertions,
                                  List<LoopSpec.TabularAssertion> tabularAssertions) {
        public CompactEvidence {
            kind = kind == null ? null : kind.trim().toUpperCase();
            command = command == null ? List.of() : List.copyOf(command);
            covers = covers == null ? List.of() : List.copyOf(covers);
            allowedPaths = allowedPaths == null ? List.of() : List.copyOf(allowedPaths);
            forbiddenPaths = forbiddenPaths == null ? List.of() : List.copyOf(forbiddenPaths);
            assertions = assertions == null ? List.of() : List.copyOf(assertions);
            documentAssertions = documentAssertions == null ? List.of() : List.copyOf(documentAssertions);
            tabularAssertions = tabularAssertions == null ? List.of() : List.copyOf(tabularAssertions);
        }

        CompactEvidence withCovers(List<Integer> value) {
            return new CompactEvidence(kind, command, value, successMarker, path, requireChanges, allowedPaths,
                    forbiddenPaths, forbidDeletes, url, httpMethod, expectedStatus, jsonPath, expectedValue,
                    matchMode, expectedContent, expectedSha256, sql, expectedRowCount, assertions,
                    documentAssertions, tabularAssertions);
        }
    }

    public record CompactStage(String objective, ImplementationKind implementationKind,
                               List<String> allowedPaths, List<String> forbiddenPaths,
                               List<String> deliverables, List<CompactCriterion> criteria,
                               List<CompactEvidence> evidence,
                               LoopSpec.VerificationRuntime verificationRuntime) {
        public CompactStage {
            allowedPaths = allowedPaths == null ? List.of() : List.copyOf(allowedPaths);
            forbiddenPaths = forbiddenPaths == null ? List.of() : List.copyOf(forbiddenPaths);
            deliverables = deliverables == null ? List.of() : List.copyOf(deliverables);
            criteria = criteria == null ? List.of() : List.copyOf(criteria);
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }
    }

    public record CompactPackageCompilationPlan(String outcome, String summary, List<CompactStage> stages,
                                                String handoffSummary, List<DesignGap> designGaps) {
        CompactPackageCompilationPlan normalized() {
            return new CompactPackageCompilationPlan(outcome == null ? null : outcome.trim().toUpperCase(), summary,
                    stages == null ? List.of() : List.copyOf(stages), handoffSummary,
                    designGaps == null ? List.of() : List.copyOf(designGaps));
        }
    }

    public record PackageCompilationPlanEnvelope(Integer contractVersion, String status, String summary,
                                                 List<PlannedStage> stages,
                                                 List<AcceptanceEvidenceMapping> evidenceMappings,
                                                 String handoffSummary, List<DesignGap> designGaps) {
        PackageCompilationPlanEnvelope normalized() {
            return new PackageCompilationPlanEnvelope(contractVersion == null ? 0 : contractVersion,
                    status == null ? null : status.trim().toUpperCase(), summary,
                    stages == null ? List.of() : List.copyOf(stages),
                    evidenceMappings == null ? List.of() : List.copyOf(evidenceMappings), handoffSummary,
                    designGaps == null ? List.of() : List.copyOf(designGaps));
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
