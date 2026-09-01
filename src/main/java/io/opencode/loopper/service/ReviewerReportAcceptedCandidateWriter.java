package io.opencode.loopper.service;

import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.persistence.LoopperMachineCandidateMapper;
import io.opencode.loopper.persistence.ReviewerReportAcceptedResultRow;
import java.time.Instant;
import tools.jackson.databind.ObjectMapper;

/** Recompiles and persists the full immutable Reviewer result in the ACCEPTED transaction. */
final class ReviewerReportAcceptedCandidateWriter implements AcceptedCandidateWriter {
    private final LoopperMachineCandidateMapper mapper;
    private final ObjectMapper json;
    private final ReviewerReportCandidateCodec codec;
    private final ReviewerReportCompilationInputLoader inputs;
    private final ReviewerReportCompilation compilation;

    ReviewerReportAcceptedCandidateWriter(
            LoopperMachineCandidateMapper mapper,
            ObjectMapper json,
            ReviewerReportCandidateCodec codec,
            ReviewerReportCompilationInputLoader inputs,
            ReviewerReportCompilation compilation) {
        this.mapper = mapper;
        this.json = json;
        this.codec = codec;
        this.inputs = inputs;
        this.compilation = compilation;
    }

    @Override
    public boolean supports(MachineCandidateKind kind) {
        return kind == MachineCandidateKind.REVIEWER_REPORT_V1;
    }

    @Override
    public void write(CandidatePolicy.Context context, String canonicalCandidateJson,
                      String candidatePayloadSha256) {
        ReviewerReportCompilation.Candidate candidate = codec.requireCandidate(canonicalCandidateJson);
        ReviewerReportCompilation.Result result = compilation.compile(inputs.load(context, candidate));
        if (!result.accepted()
                || !canonicalCandidateJson.equals(result.canonicalCandidateJson())
                || candidatePayloadSha256 == null
                || !codec.sha256(canonicalCandidateJson).equals(candidatePayloadSha256)) {
            throw new ConflictException("REVIEWER_ACCEPTED_RESULT_INVALID",
                    "Accepted Reviewer candidate no longer compiles from frozen source facts");
        }
        String now = Instant.now().toString();
        ReviewerReportAcceptedResultRow row = new ReviewerReportAcceptedResultRow(
                context.runId(), context.owner().id(), context.sourceRevision(), context.ownerVersion(),
                context.contractVersion(), result.canonicalCandidateJson(), result.canonicalFindingsJson(),
                result.markdown(), write(result.evidence()), result.contentSha256(),
                result.sourceSnapshotSha256(), candidatePayloadSha256, result.canonicalResultSha256(),
                null, now, now, 0);
        if (mapper.insertReviewerReportAcceptedResult(row) != 1) {
            throw new ConflictException("REVIEWER_ACCEPTED_RESULT_CONFLICT",
                    "Accepted Reviewer result could not be inserted");
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (RuntimeException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
