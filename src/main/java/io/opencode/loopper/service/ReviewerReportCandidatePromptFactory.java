package io.opencode.loopper.service;

import io.opencode.loopper.service.roles.RolePromptResources;

/** Builds the bounded REVIEWER_REPORT_V1 private-submission prompt. */
final class ReviewerReportCandidatePromptFactory {
    String internal(String roleInstructions, String projectRoot, String requirement,
                    MachineCandidateSubmission.RunSnapshot run, String exactToolName) {
        if (run == null || exactToolName == null || exactToolName.isBlank()
                || projectRoot == null || projectRoot.isBlank()
                || requirement == null || requirement.isBlank()) {
            throw new IllegalArgumentException("Complete Reviewer candidate prompt facts are required");
        }
        return (roleInstructions == null ? "" : roleInstructions) + (RolePromptResources.read("prompt.v1.ReviewerReportCandidatePromptFactory.block01.segment0")
                + String.format("%s", (Object) (projectRoot))
                + "\nFrozen review requirement:\n"
                + String.format("%s", (Object) (requirement))
                + RolePromptResources.read("prompt.v1.ReviewerReportCandidatePromptFactory.block01.segment2")
                + String.format("%s", (Object) (run.runId()))
                + "\nexpectedSubmissionRevision: "
                + String.format("%d", (Object) (run.version()))
                + RolePromptResources.read("prompt.v1.ReviewerReportCandidatePromptFactory.block01.segment4")
                + String.format("%s", (Object) (exactToolName))
                + "\n\nCall "
                + String.format("%s", (Object) (exactToolName))
                + RolePromptResources.read("prompt.v1.ReviewerReportCandidatePromptFactory.block01.segment6")
                + String.format("%s", (Object) (CandidateCorrectionPolicy.prompt(run)))
                + RolePromptResources.read("prompt.v1.ReviewerReportCandidatePromptFactory.block01.segment7"));
    }
}
