package io.opencode.loopper.service;

import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.persistence.LoopperMachineCandidateMapper;
import io.opencode.loopper.persistence.ReviewerReportSourceSnapshotRow;
import java.time.Instant;
import java.util.List;

/** Persists captured source facts before any Reviewer remote create/prompt I/O. */
final class ReviewerReportSourceSnapshotStore {
    private final LoopperMachineCandidateMapper mapper;
    private final ReviewerReportCandidateCodec codec;

    ReviewerReportSourceSnapshotStore(
            LoopperMachineCandidateMapper mapper, ReviewerReportCandidateCodec codec) {
        this.mapper = mapper;
        this.codec = codec;
    }

    ReviewerReportSourceSnapshotRow freeze(
            CandidatePolicy.Context plannedContext,
            List<ReviewerReportCompilation.SourceFile> sourceFiles) {
        if (plannedContext.candidateKind() != MachineCandidateKind.REVIEWER_REPORT_V1
                || plannedContext.scope().type()
                    != MachineCandidateSubmission.CandidateScopeType.DESIGNER_SESSION
                || plannedContext.owner().type()
                    != MachineCandidateSubmission.CandidateOwnerType.ANALYSIS_REPORT
                || !ReviewerReportCandidatePolicy.WORKFLOW_STEP.equals(plannedContext.workflowStep())
                || !ReviewerReportCandidatePolicy.CONTRACT_VERSION.equals(plannedContext.contractVersion())
                || plannedContext.maxAttempts() != ReviewerReportCandidatePolicy.MAX_ATTEMPTS
                || plannedContext.ownerVersion() < 1) {
            throw new ConflictException("REVIEWER_SOURCE_SNAPSHOT_CONTRACT_INVALID",
                    "Reviewer source manifest does not belong to the frozen candidate contract");
        }
        String canonical = codec.canonicalSourceManifest(sourceFiles);
        ReviewerReportSourceSnapshotRow row = new ReviewerReportSourceSnapshotRow(
                plannedContext.runId(), plannedContext.owner().id(), plannedContext.sourceRevision(),
                plannedContext.ownerVersion() - 1, plannedContext.contractVersion(), canonical,
                codec.sha256(canonical), Instant.now().toString());
        if (mapper.insertReviewerReportSourceSnapshot(row) != 1) {
            throw new ConflictException("REVIEWER_SOURCE_SNAPSHOT_CONFLICT",
                    "Frozen Reviewer source manifest could not be inserted");
        }
        return row;
    }
}
