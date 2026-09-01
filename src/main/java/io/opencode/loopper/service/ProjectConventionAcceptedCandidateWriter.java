package io.opencode.loopper.service;

import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.persistence.LoopperMachineCandidateMapper;
import io.opencode.loopper.persistence.ProjectConventionCandidateAcceptedResultRow;
import java.time.Instant;

/** Recompiles only from V62 SQLite facts and inserts the immutable result in the ACCEPTED transaction. */
final class ProjectConventionAcceptedCandidateWriter implements AcceptedCandidateWriter {
    private final LoopperMachineCandidateMapper mapper;
    private final ProjectConventionCandidateCodec codec;
    private final ProjectConventionCompilationInputLoader inputs;
    private final ProjectConventionCompilation compilation;

    ProjectConventionAcceptedCandidateWriter(
            LoopperMachineCandidateMapper mapper,
            ProjectConventionCandidateCodec codec,
            ProjectConventionCompilationInputLoader inputs,
            ProjectConventionCompilation compilation) {
        this.mapper = mapper;
        this.codec = codec;
        this.inputs = inputs;
        this.compilation = compilation;
    }

    @Override
    public boolean supports(MachineCandidateKind kind) {
        return kind == MachineCandidateKind.PROJECT_CONVENTION_V1;
    }

    @Override
    public void write(CandidatePolicy.Context context, String canonicalCandidateJson,
                      String candidatePayloadSha256) {
        ProjectConventionCompilation.Result result = compilation.compileCandidate(
                inputs.load(context), canonicalCandidateJson);
        if (!result.accepted()
                || canonicalCandidateJson == null
                || !canonicalCandidateJson.equals(result.canonicalCandidateJson())
                || candidatePayloadSha256 == null
                || !candidatePayloadSha256.matches("[0-9a-f]{64}")
                || !codec.sha256(canonicalCandidateJson).equals(candidatePayloadSha256)
                || result.proposedContent() == null
                || result.contentSha256() == null
                || !codec.sha256(result.proposedContent()).equals(result.contentSha256())
                || result.canonicalResultSha256() == null) {
            throw new ConflictException("PROJECT_CONVENTION_ACCEPTED_RESULT_INVALID",
                    "Accepted project convention no longer compiles from frozen SQLite facts");
        }
        String now = Instant.now().toString();
        ProjectConventionCandidateAcceptedResultRow row =
                new ProjectConventionCandidateAcceptedResultRow(
                        context.runId(), context.scope().id(), context.owner().id(),
                        context.sourceRevision(), context.ownerVersion(), context.contractVersion(),
                        result.canonicalCandidateJson(), candidatePayloadSha256,
                        result.canonicalResultSha256(), result.proposedContent(),
                        result.contentSha256(), null, now, now, 0);
        if (mapper.insertProjectConventionCandidateAcceptedResult(row) != 1) {
            throw new ConflictException("PROJECT_CONVENTION_ACCEPTED_RESULT_CONFLICT",
                    "Accepted project convention result could not be inserted");
        }
    }
}
