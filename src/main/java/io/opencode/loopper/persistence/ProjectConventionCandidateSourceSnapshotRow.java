package io.opencode.loopper.persistence;

import org.apache.ibatis.annotations.AutomapConstructor;

/** Immutable pre-remote Convention inputs, including the bounded compilation evidence catalog. */
public record ProjectConventionCandidateSourceSnapshotRow(
        String candidateRunId,
        String projectId,
        String projectConventionDraftId,
        long sourceRevision,
        long preparedOwnerVersion,
        String contractVersion,
        int sourceExists,
        String sourceAgentsSha256,
        String sourceContent,
        String sourceContentSha256,
        String projectStackProfileId,
        String stackFingerprint,
        String canonicalEvidenceJson,
        String evidenceSha256,
        String createdAt) {
    @AutomapConstructor public ProjectConventionCandidateSourceSnapshotRow { }
}
