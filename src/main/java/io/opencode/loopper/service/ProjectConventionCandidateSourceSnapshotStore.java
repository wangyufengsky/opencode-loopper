package io.opencode.loopper.service;

import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.persistence.LoopperMachineCandidateMapper;
import io.opencode.loopper.persistence.ProjectConventionCandidateSourceSnapshotRow;
import java.time.Instant;
import java.util.Objects;

/** Canonicalizes and persists complete Convention compilation facts before any remote I/O. */
final class ProjectConventionCandidateSourceSnapshotStore {
    private final LoopperMachineCandidateMapper mapper;
    private final ProjectConventionCandidateCodec codec;
    private final ProjectConventionCompilation compilation;

    ProjectConventionCandidateSourceSnapshotStore(
            LoopperMachineCandidateMapper mapper,
            ProjectConventionCandidateCodec codec,
            ProjectConventionCompilation compilation) {
        this.mapper = mapper;
        this.codec = codec;
        this.compilation = compilation;
    }

    ProjectConventionCandidateSourceSnapshotRow freeze(
            CandidatePolicy.Context plannedContext,
            boolean sourceExists,
            String sourceAgentsSha256,
            String sourceContent,
            String projectStackProfileId,
            String stackFingerprint,
            ProjectConventionCompilation.EvidenceCatalog evidence) {
        validateContract(plannedContext);
        if (sourceContent == null || !sourceExists && !sourceContent.isEmpty()
                || sourceAgentsSha256 == null || !sourceAgentsSha256.matches("[0-9a-f]{64}")
                || projectStackProfileId == null || projectStackProfileId.isBlank()
                || stackFingerprint == null || !stackFingerprint.matches("[0-9a-f]{64}")
                || evidence == null || !stackFingerprint.equals(evidence.stackFingerprint())) {
            throw invalid("Project convention source or evidence identity is invalid");
        }
        String sourceContentSha256 = codec.sha256(sourceContent);
        if (!sourceAgentsSha256.equals(sourceContentSha256)) {
            throw invalid("Project convention AGENTS.md source hash is invalid");
        }
        String canonicalEvidence = codec.canonicalEvidence(evidence);
        ProjectConventionCompilation.EvidenceCatalog verifiedEvidence = codec.requireEvidence(
                canonicalEvidence, codec.sha256(canonicalEvidence));
        ProjectConventionCompilation.Candidate allEvidence = new ProjectConventionCompilation.Candidate(
                ProjectConventionCompilation.CONTRACT_VERSION,
                verifiedEvidence.components().stream()
                        .map(ProjectConventionCompilation.ComponentEvidence::key).toList(),
                verifiedEvidence.commands().stream()
                        .map(ProjectConventionCompilation.CommandEvidence::id).toList(),
                verifiedEvidence.paths().stream()
                        .map(ProjectConventionCompilation.PathEvidence::id).toList());
        ProjectConventionCompilation.Result validation = compilation.compileCandidate(
                new ProjectConventionCompilation.Input(sourceContent, verifiedEvidence),
                codec.canonical(allEvidence));
        if (!validation.accepted()) {
            ProjectConventionCompilation.Problem first = validation.problems().getFirst();
            throw invalid(first.staticDetail());
        }

        ProjectConventionCandidateSourceSnapshotRow row =
                new ProjectConventionCandidateSourceSnapshotRow(
                        plannedContext.runId(), plannedContext.scope().id(), plannedContext.owner().id(),
                        plannedContext.sourceRevision(), plannedContext.ownerVersion() - 1,
                        plannedContext.contractVersion(), sourceExists ? 1 : 0, sourceAgentsSha256,
                        sourceContent, sourceContentSha256, projectStackProfileId, stackFingerprint,
                        canonicalEvidence, codec.sha256(canonicalEvidence), Instant.now().toString());
        try {
            if (mapper.insertProjectConventionCandidateSourceSnapshot(row) != 1) {
                throw new ConflictException("PROJECT_CONVENTION_SOURCE_SNAPSHOT_CONFLICT",
                        "Frozen project convention source snapshot could not be inserted");
            }
            return row;
        } catch (RuntimeException insertFailure) {
            ProjectConventionCandidateSourceSnapshotRow existing;
            try {
                existing = mapper.findProjectConventionCandidateSourceSnapshot(
                        plannedContext.runId()).orElse(null);
            } catch (RuntimeException lookupFailure) {
                insertFailure.addSuppressed(lookupFailure);
                throw insertFailure;
            }
            if (sameFrozenSnapshot(existing, row)) return existing;
            throw insertFailure;
        }
    }

    private static boolean sameFrozenSnapshot(
            ProjectConventionCandidateSourceSnapshotRow left,
            ProjectConventionCandidateSourceSnapshotRow right) {
        return left != null
                && Objects.equals(left.candidateRunId(), right.candidateRunId())
                && Objects.equals(left.projectId(), right.projectId())
                && Objects.equals(left.projectConventionDraftId(), right.projectConventionDraftId())
                && left.sourceRevision() == right.sourceRevision()
                && left.preparedOwnerVersion() == right.preparedOwnerVersion()
                && Objects.equals(left.contractVersion(), right.contractVersion())
                && left.sourceExists() == right.sourceExists()
                && Objects.equals(left.sourceAgentsSha256(), right.sourceAgentsSha256())
                && Objects.equals(left.sourceContent(), right.sourceContent())
                && Objects.equals(left.sourceContentSha256(), right.sourceContentSha256())
                && Objects.equals(left.projectStackProfileId(), right.projectStackProfileId())
                && Objects.equals(left.stackFingerprint(), right.stackFingerprint())
                && Objects.equals(left.canonicalEvidenceJson(), right.canonicalEvidenceJson())
                && Objects.equals(left.evidenceSha256(), right.evidenceSha256());
    }

    private static void validateContract(CandidatePolicy.Context context) {
        if (context.candidateKind() != MachineCandidateKind.PROJECT_CONVENTION_V1
                || context.scope().type() != MachineCandidateSubmission.CandidateScopeType.PROJECT
                || context.owner().type()
                    != MachineCandidateSubmission.CandidateOwnerType.PROJECT_CONVENTION_DRAFT
                || !ProjectConventionCandidatePolicy.WORKFLOW_STEP.equals(context.workflowStep())
                || !ProjectConventionCandidatePolicy.CONTRACT_VERSION.equals(context.contractVersion())
                || context.maxAttempts() != ProjectConventionCandidatePolicy.MAX_ATTEMPTS
                || context.ownerVersion() < 1) {
            throw new ConflictException("PROJECT_CONVENTION_SOURCE_SNAPSHOT_CONTRACT_INVALID",
                    "Project convention snapshot does not belong to the frozen candidate contract");
        }
    }

    private static ConflictException invalid(String detail) {
        return new ConflictException("PROJECT_CONVENTION_SOURCE_SNAPSHOT_INVALID", detail);
    }
}
