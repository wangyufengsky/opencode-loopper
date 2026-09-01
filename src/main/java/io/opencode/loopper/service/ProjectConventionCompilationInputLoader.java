package io.opencode.loopper.service;

import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.persistence.LoopperMachineCandidateMapper;
import io.opencode.loopper.persistence.ProjectConventionCandidateSourceSnapshotRow;

/** Loads PROJECT_CONVENTION_V1 compilation inputs only from the immutable V62 SQLite snapshot. */
interface ProjectConventionCompilationInputLoader {
    ProjectConventionCompilation.Input load(CandidatePolicy.Context context);

    final class MapperLoader implements ProjectConventionCompilationInputLoader {
        private final LoopperMachineCandidateMapper mapper;
        private final ProjectConventionCandidateCodec codec;

        MapperLoader(LoopperMachineCandidateMapper mapper, ProjectConventionCandidateCodec codec) {
            this.mapper = mapper;
            this.codec = codec;
        }

        @Override
        public ProjectConventionCompilation.Input load(CandidatePolicy.Context context) {
            ProjectConventionCandidateSourceSnapshotRow snapshot = mapper
                    .findProjectConventionCandidateSourceSnapshot(context.runId())
                    .orElseThrow(() -> invalid("Frozen project convention source snapshot is missing"));
            if (context.candidateKind() != MachineCandidateKind.PROJECT_CONVENTION_V1
                    || context.scope().type() != MachineCandidateSubmission.CandidateScopeType.PROJECT
                    || context.owner().type()
                        != MachineCandidateSubmission.CandidateOwnerType.PROJECT_CONVENTION_DRAFT
                    || !ProjectConventionCandidatePolicy.WORKFLOW_STEP.equals(context.workflowStep())
                    || !ProjectConventionCandidatePolicy.CONTRACT_VERSION.equals(context.contractVersion())
                    || context.maxAttempts() != ProjectConventionCandidatePolicy.MAX_ATTEMPTS
                    || !context.scope().id().equals(snapshot.projectId())
                    || !context.owner().id().equals(snapshot.projectConventionDraftId())
                    || context.sourceRevision() != snapshot.sourceRevision()
                    || context.ownerVersion() != snapshot.preparedOwnerVersion() + 1
                    || !context.contractVersion().equals(snapshot.contractVersion())) {
                throw invalid("Project convention source snapshot owner or revision anchor is invalid");
            }
            String sourceSha256 = codec.sha256(snapshot.sourceContent());
            if (snapshot.sourceExists() != 0 && snapshot.sourceExists() != 1
                    || snapshot.sourceExists() == 0 && !snapshot.sourceContent().isEmpty()
                    || !sourceSha256.equals(snapshot.sourceAgentsSha256())
                    || !sourceSha256.equals(snapshot.sourceContentSha256())
                    || snapshot.projectStackProfileId() == null
                    || snapshot.projectStackProfileId().isBlank()
                    || snapshot.stackFingerprint() == null
                    || !snapshot.stackFingerprint().matches("[0-9a-f]{64}")) {
                throw invalid("Project convention source snapshot hashes are invalid");
            }
            ProjectConventionCompilation.EvidenceCatalog evidence = codec.requireEvidence(
                    snapshot.canonicalEvidenceJson(), snapshot.evidenceSha256());
            if (!snapshot.stackFingerprint().equals(evidence.stackFingerprint())) {
                throw invalid("Project convention evidence snapshot fingerprint is invalid");
            }
            return new ProjectConventionCompilation.Input(snapshot.sourceContent(), evidence);
        }

        private static ConflictException invalid(String detail) {
            return new ConflictException("PROJECT_CONVENTION_SOURCE_SNAPSHOT_INVALID", detail);
        }
    }
}
