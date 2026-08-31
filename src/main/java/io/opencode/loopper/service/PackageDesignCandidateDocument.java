package io.opencode.loopper.service;

import java.util.List;

/** Transport-neutral PACKAGE_DESIGN_V1 replacement document. Keys are candidate-local references, not server IDs. */
record PackageDesignCandidateDocument(
        String contractVersion,
        String outcome,
        List<Requirement> requirements,
        List<Scenario> scenarios,
        List<Deliverable> deliverables,
        List<Review> reviews,
        List<Stage> stages,
        List<String> gapCodes) {

    record Requirement(String key, String statement) { }

    record Scenario(
            String key,
            String title,
            String precondition,
            String action,
            String observableResult,
            String invariant,
            List<String> requirementRefs) { }

    record Deliverable(
            String key,
            String kind,
            String target,
            String description,
            List<String> requirementRefs) { }

    record Review(
            String key,
            String title,
            String criteria,
            String humanOnlyReason,
            List<String> requirementRefs) { }

    record Stage(
            String key,
            String title,
            String objective,
            List<String> includes,
            List<String> dependencies) { }
}
