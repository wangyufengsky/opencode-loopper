package io.opencode.loopper.service;

import java.util.List;

/** Semantic-only extension. References identify frozen input, never grant execution authority. */
record PackageDesignV2Document(String contractVersion, String outcome,
        List<PackageDesignCandidateDocument.Requirement> requirements,
        List<PackageDesignCandidateDocument.Scenario> scenarios,
        List<PackageDesignCandidateDocument.Deliverable> deliverables,
        List<PackageDesignCandidateDocument.Review> reviews,
        List<PackageDesignCandidateDocument.Stage> stages, List<String> gapCodes,
        List<SourceBinding> sourceBindings, List<PackageSemanticRelations.Relation> relations, List<GapClaim> gapClaims,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        List<PackageBehaviorContract.Branch> behaviorBranches) {
    static final String VERSION = "PACKAGE_DESIGN_V2";
    record SourceBinding(String key, List<String> candidateRefs, List<String> sourceRefs) { }
    record GapClaim(String key, String code, List<String> sourceRefs, String question, List<String> alternatives) { }
}
