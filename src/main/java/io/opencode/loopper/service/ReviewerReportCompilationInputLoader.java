package io.opencode.loopper.service;

import io.opencode.loopper.persistence.AnalysisReportRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.ReviewerReportSourceSnapshotRow;

/** Loads only the pre-remote Reviewer source manifest frozen in SQLite. */
interface ReviewerReportCompilationInputLoader {
    ReviewerReportCompilation.Input load(
            CandidatePolicy.Context context, ReviewerReportCompilation.Candidate candidate);

    final class MapperLoader implements ReviewerReportCompilationInputLoader {
        private final LoopperMapper mapper;
        private final ReviewerReportCandidateCodec codec;

        MapperLoader(LoopperMapper mapper, ReviewerReportCandidateCodec codec) {
            this.mapper = mapper;
            this.codec = codec;
        }

        @Override
        public ReviewerReportCompilation.Input load(
                CandidatePolicy.Context context, ReviewerReportCompilation.Candidate candidate) {
            AnalysisReportRow owner = mapper.findAnalysisReport(context.scope().id(), context.owner().id())
                    .orElseThrow(() -> new ConflictException(
                            "CANDIDATE_OWNER_MISSING", "Reviewer candidate owner no longer exists"));
            ReviewerReportSourceSnapshotRow snapshot = mapper
                    .findReviewerReportSourceSnapshot(context.runId())
                    .orElseThrow(() -> new ConflictException(
                            "REVIEWER_SOURCE_SNAPSHOT_MISSING",
                            "Frozen Reviewer source manifest is missing"));
            if (context.scope().type()
                        != MachineCandidateSubmission.CandidateScopeType.DESIGNER_SESSION
                    || context.owner().type()
                        != MachineCandidateSubmission.CandidateOwnerType.ANALYSIS_REPORT
                    || !context.scope().id().equals(owner.designerSessionId())
                    || !"RUNNING".equals(owner.state())
                    || owner.version() != context.ownerVersion()
                    || owner.sourceRequirementRevision() == null
                    || owner.sourceRequirementRevision() != context.sourceRevision()
                    || !context.contractVersion().equals(owner.reviewerContractVersion())
                    || !context.owner().id().equals(snapshot.analysisReportId())
                    || context.sourceRevision() != snapshot.sourceRevision()
                    || context.ownerVersion() != snapshot.preparedOwnerVersion() + 1
                    || !context.contractVersion().equals(snapshot.contractVersion())) {
                throw new ConflictException("CANDIDATE_OWNER_REVISION_STALE",
                        "Reviewer candidate owner or frozen source revision has changed");
            }
            return new ReviewerReportCompilation.Input(candidate, codec.requireSourceManifest(
                    snapshot.canonicalSourceManifestJson(), snapshot.sourceManifestSha256()));
        }
    }
}
