package io.opencode.loopper.service;

import io.opencode.loopper.persistence.LoopperMachineCandidateMapper;
import io.opencode.loopper.persistence.ReviewerReportAcceptedResultRow;
import io.opencode.loopper.persistence.ReviewerReportSourceSnapshotRow;
import java.util.List;
import java.util.Optional;

/** Recovery reader and one-way settlement seam for immutable Reviewer accepted results. */
final class ReviewerReportAcceptedResultStore {
    private final LoopperMachineCandidateMapper mapper;
    private final ReviewerReportCandidateCodec codec;
    private final ReviewerReportCompilation compilation;

    ReviewerReportAcceptedResultStore(
            LoopperMachineCandidateMapper mapper,
            ReviewerReportCandidateCodec codec,
            ReviewerReportCompilation compilation) {
        this.mapper = mapper;
        this.codec = codec;
        this.compilation = compilation;
    }

    Optional<Accepted> find(String candidateRunId) {
        return mapper.findReviewerReportAcceptedResult(candidateRunId).map(this::validate);
    }

    List<Accepted> listUnsettled() {
        return mapper.listUnsettledReviewerReportAcceptedResults().stream()
                .map(this::validate).toList();
    }

    Accepted settle(String candidateRunId, long expectedVersion,
                    String analysisReportId, String updatedAt) {
        Accepted current = find(candidateRunId).orElseThrow(() -> new ConflictException(
                "REVIEWER_ACCEPTED_RESULT_MISSING", "Reviewer accepted result is missing"));
        if (!current.row().analysisReportId().equals(analysisReportId)) {
            throw new ConflictException("REVIEWER_ACCEPTED_RESULT_OWNER_MISMATCH",
                    "Reviewer accepted result settlement owner mismatch");
        }
        if (current.row().settledAnalysisReportId() != null) {
            if (current.row().settledAnalysisReportId().equals(analysisReportId)) return current;
            throw new ConflictException("REVIEWER_ACCEPTED_RESULT_OWNER_MISMATCH",
                    "Reviewer accepted result settlement owner mismatch");
        }
        if (mapper.settleReviewerReportAcceptedResult(
                candidateRunId, expectedVersion, analysisReportId, updatedAt) != 1) {
            throw new ConflictException("REVIEWER_ACCEPTED_RESULT_VERSION_CONFLICT",
                    "Reviewer accepted result settlement version conflict");
        }
        return find(candidateRunId).orElseThrow(() -> new ConflictException(
                "REVIEWER_ACCEPTED_RESULT_MISSING", "Reviewer accepted result is missing"));
    }

    private Accepted validate(ReviewerReportAcceptedResultRow row) {
        ReviewerReportSourceSnapshotRow snapshot = mapper
                .findReviewerReportSourceSnapshot(row.candidateRunId())
                .orElseThrow(() -> invalid("Frozen Reviewer source manifest is missing"));
        if (!row.analysisReportId().equals(snapshot.analysisReportId())
                || row.sourceRevision() != snapshot.sourceRevision()
                || row.ownerVersion() != snapshot.preparedOwnerVersion() + 1
                || !row.contractVersion().equals(snapshot.contractVersion())) {
            throw invalid("Reviewer accepted result source anchor is invalid");
        }
        ReviewerReportCompilation.Result compiled = compilation.compile(new ReviewerReportCompilation.Input(
                codec.requireCandidate(row.canonicalCandidateJson()),
                codec.requireSourceManifest(snapshot.canonicalSourceManifestJson(),
                        snapshot.sourceManifestSha256())));
        if (!compiled.accepted()
                || !row.canonicalCandidateJson().equals(compiled.canonicalCandidateJson())
                || !row.canonicalFindingsJson().equals(compiled.canonicalFindingsJson())
                || !row.markdown().equals(compiled.markdown())
                || !row.evidenceJson().equals(codec.canonicalJson(compiled.evidence()))
                || !row.contentSha256().equals(compiled.contentSha256())
                || !row.sourceSnapshotSha256().equals(compiled.sourceSnapshotSha256())
                || !row.candidatePayloadSha256().equals(codec.sha256(row.canonicalCandidateJson()))
                || !row.canonicalResultSha256().equals(compiled.canonicalResultSha256())) {
            throw invalid("Reviewer accepted result does not match deterministic compilation");
        }
        return new Accepted(row, compiled);
    }

    private static ConflictException invalid(String detail) {
        return new ConflictException("REVIEWER_ACCEPTED_RESULT_INVALID", detail);
    }

    record Accepted(ReviewerReportAcceptedResultRow row, ReviewerReportCompilation.Result compiled) { }
}
