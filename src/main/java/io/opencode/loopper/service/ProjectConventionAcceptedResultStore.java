package io.opencode.loopper.service;

import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.persistence.LoopperMachineCandidateMapper;
import io.opencode.loopper.persistence.ProjectConventionCandidateAcceptedResultRow;
import java.util.List;
import java.util.Optional;

/** Recovery reader and one-way settlement seam for immutable Convention accepted results. */
final class ProjectConventionAcceptedResultStore {
    private final LoopperMachineCandidateMapper mapper;
    private final ProjectConventionCandidateCodec codec;
    private final ProjectConventionCompilationInputLoader inputs;
    private final ProjectConventionCompilation compilation;

    ProjectConventionAcceptedResultStore(
            LoopperMachineCandidateMapper mapper,
            ProjectConventionCandidateCodec codec,
            ProjectConventionCompilationInputLoader inputs,
            ProjectConventionCompilation compilation) {
        this.mapper = mapper;
        this.codec = codec;
        this.inputs = inputs;
        this.compilation = compilation;
    }

    Optional<Accepted> find(String candidateRunId) {
        return mapper.findProjectConventionCandidateAcceptedResult(candidateRunId)
                .map(this::validate);
    }

    List<Accepted> listUnsettled() {
        return mapper.listUnsettledProjectConventionCandidateAcceptedResults().stream()
                .map(this::validate).toList();
    }

    Accepted settle(String candidateRunId, long expectedVersion, String draftId, String updatedAt) {
        Accepted current = find(candidateRunId).orElseThrow(() -> new ConflictException(
                "PROJECT_CONVENTION_ACCEPTED_RESULT_MISSING",
                "Project convention accepted result is missing"));
        if (!current.row().projectConventionDraftId().equals(draftId)) {
            throw new ConflictException("PROJECT_CONVENTION_ACCEPTED_RESULT_OWNER_MISMATCH",
                    "Project convention accepted result settlement owner mismatch");
        }
        if (current.row().settledDraftId() != null) {
            if (current.row().settledDraftId().equals(draftId)) return current;
            throw new ConflictException("PROJECT_CONVENTION_ACCEPTED_RESULT_OWNER_MISMATCH",
                    "Project convention accepted result settlement owner mismatch");
        }
        if (mapper.settleProjectConventionCandidateAcceptedResult(
                candidateRunId, expectedVersion, draftId, updatedAt) != 1) {
            throw new ConflictException("PROJECT_CONVENTION_ACCEPTED_RESULT_VERSION_CONFLICT",
                    "Project convention accepted result settlement version conflict");
        }
        return find(candidateRunId).orElseThrow(() -> new ConflictException(
                "PROJECT_CONVENTION_ACCEPTED_RESULT_MISSING",
                "Project convention accepted result is missing"));
    }

    private Accepted validate(ProjectConventionCandidateAcceptedResultRow row) {
        if (row.settledDraftId() != null
                && !row.projectConventionDraftId().equals(row.settledDraftId())) {
            throw invalid("Project convention accepted result settlement anchor is invalid");
        }
        CandidatePolicy.Context context = new CandidatePolicy.Context(
                row.candidateRunId(), MachineCandidateSubmission.CandidateScope.project(row.projectId()),
                MachineCandidateSubmission.CandidateOwnerRef.projectConventionDraft(
                        row.projectConventionDraftId()),
                MachineCandidateKind.PROJECT_CONVENTION_V1,
                ProjectConventionCandidatePolicy.WORKFLOW_STEP, row.sourceRevision(), row.ownerVersion(),
                row.contractVersion(), ProjectConventionCandidatePolicy.MAX_ATTEMPTS, 0);
        ProjectConventionCompilation.Input input = inputs.load(context);
        ProjectConventionCompilation.Result compiled = compilation.compileCandidate(
                input, row.canonicalCandidateJson());
        if (!compiled.accepted()
                || !row.canonicalCandidateJson().equals(compiled.canonicalCandidateJson())
                || !row.candidatePayloadSha256().equals(codec.sha256(row.canonicalCandidateJson()))
                || !row.canonicalResultSha256().equals(compiled.canonicalResultSha256())
                || !row.proposedContent().equals(compiled.proposedContent())
                || !row.proposedContentSha256().equals(compiled.contentSha256())
                || !row.proposedContentSha256().equals(codec.sha256(row.proposedContent()))
                || !compiled.sourceSha256().equals(codec.sha256(input.sourceContent()))) {
            throw invalid("Project convention accepted result does not match deterministic compilation");
        }
        return new Accepted(row, compiled);
    }

    private static ConflictException invalid(String detail) {
        return new ConflictException("PROJECT_CONVENTION_ACCEPTED_RESULT_INVALID", detail);
    }

    record Accepted(ProjectConventionCandidateAcceptedResultRow row,
                    ProjectConventionCompilation.Result compiled) { }
}
