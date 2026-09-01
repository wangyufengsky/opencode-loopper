package io.opencode.loopper.persistence;

import org.apache.ibatis.annotations.AutomapConstructor;

/** Immutable pre-remote source-manifest metadata for one deterministic Reviewer candidate run ID. */
public record ReviewerReportSourceSnapshotRow(
        String candidateRunId,
        String analysisReportId,
        long sourceRevision,
        long preparedOwnerVersion,
        String contractVersion,
        String canonicalSourceManifestJson,
        String sourceManifestSha256,
        String createdAt) {
    @AutomapConstructor public ReviewerReportSourceSnapshotRow { }
}
