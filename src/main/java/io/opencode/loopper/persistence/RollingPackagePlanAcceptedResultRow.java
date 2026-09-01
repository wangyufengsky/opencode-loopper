package io.opencode.loopper.persistence;

import org.apache.ibatis.annotations.AutomapConstructor;

/** Immutable accepted rolling-plan candidate plus its optimistic settlement marker. */
public record RollingPackagePlanAcceptedResultRow(
        String candidateRunId, String taskPackagePlanRevisionId, long sourceRevision, long ownerVersion,
        String contractVersion, String canonicalCandidateJson, String canonicalPlanJson, String impactJson,
        String canonicalResultSha256, String settledPlanRevisionId,
        String createdAt, String updatedAt, long version) {
    @AutomapConstructor public RollingPackagePlanAcceptedResultRow { }
}
