package io.opencode.loopper.persistence;

import org.apache.ibatis.annotations.AutomapConstructor;

/** Immutable PROJECT_CONVENTION_V1 compilation result plus its one-way draft settlement marker. */
public record ProjectConventionCandidateAcceptedResultRow(
        String candidateRunId,
        String projectId,
        String projectConventionDraftId,
        long sourceRevision,
        long ownerVersion,
        String contractVersion,
        String canonicalCandidateJson,
        String candidatePayloadSha256,
        String canonicalResultSha256,
        String proposedContent,
        String proposedContentSha256,
        String settledDraftId,
        String createdAt,
        String updatedAt,
        long version) {
    @AutomapConstructor public ProjectConventionCandidateAcceptedResultRow { }
}
