package io.opencode.loopper.persistence;

import org.apache.ibatis.annotations.AutomapConstructor;

/** Immutable REVIEWER_REPORT_V1 compiler result plus its one-way owner settlement marker. */
public record ReviewerReportAcceptedResultRow(
        String candidateRunId,
        String analysisReportId,
        long sourceRevision,
        long ownerVersion,
        String contractVersion,
        String canonicalCandidateJson,
        String canonicalFindingsJson,
        String markdown,
        String evidenceJson,
        String contentSha256,
        String sourceSnapshotSha256,
        String candidatePayloadSha256,
        String canonicalResultSha256,
        String settledAnalysisReportId,
        String createdAt,
        String updatedAt,
        long version) {
    @AutomapConstructor public ReviewerReportAcceptedResultRow { }
}
