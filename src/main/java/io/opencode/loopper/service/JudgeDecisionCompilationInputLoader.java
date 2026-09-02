package io.opencode.loopper.service;

import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.persistence.JudgeCandidateSourceSnapshotRow;
import io.opencode.loopper.persistence.LoopperJudgeCandidateMapper;

/** Loads JUDGE_DECISION_V1 compilation inputs only from the immutable V63 SQLite snapshot. */
interface JudgeDecisionCompilationInputLoader {
    JudgeDecisionCompilation.Input load(CandidatePolicy.Context context);

    final class MapperLoader implements JudgeDecisionCompilationInputLoader {
        private final LoopperJudgeCandidateMapper mapper;
        private final JudgeDecisionCandidateCodec codec;

        MapperLoader(LoopperJudgeCandidateMapper mapper, JudgeDecisionCandidateCodec codec) {
            this.mapper = mapper;
            this.codec = codec;
        }

        @Override
        public JudgeDecisionCompilation.Input load(CandidatePolicy.Context context) {
            JudgeCandidateSourceSnapshotRow snapshot = mapper
                    .findJudgeCandidateSourceSnapshot(context.runId())
                    .orElseThrow(() -> invalid("Frozen Judge source snapshot is missing"));
            if (context.candidateKind() != MachineCandidateKind.JUDGE_DECISION_V1
                    || context.scope().type() != MachineCandidateSubmission.CandidateScopeType.TASK
                    || context.owner().type() != MachineCandidateSubmission.CandidateOwnerType.JUDGE_RUN
                    || !context.scope().id().equals(snapshot.taskId())
                    || !context.owner().id().equals(snapshot.judgeRunId())
                    || context.sourceRevision() != snapshot.sourceRevision()
                    || context.ownerVersion() != snapshot.preparedOwnerVersion() + 1
                    || !context.contractVersion().equals(snapshot.contractVersion())
                    || !JudgeDecisionCompilation.CONTRACT_VERSION.equals(context.contractVersion())
                    || context.maxAttempts() != MachineCandidateKind.JUDGE_DECISION_V1.maximumAttempts()
                    || !JudgeDecisionCandidateSourceSnapshotStore.sha256(snapshot.sourcePrompt())
                            .equals(snapshot.sourcePromptSha256())
                    || !JudgeDecisionCandidateSourceSnapshotStore.sha256(snapshot.canonicalEvidenceJson())
                            .equals(snapshot.evidenceSha256())) {
                throw invalid("Judge source snapshot owner, revision, or hash anchor is invalid");
            }
            return new JudgeDecisionCompilation.Input(snapshot.role(),
                    codec.requireEvidence(snapshot.canonicalEvidenceJson(), snapshot.evidenceSha256()));
        }

        private static ConflictException invalid(String detail) {
            return new ConflictException("JUDGE_SOURCE_SNAPSHOT_INVALID", detail);
        }
    }
}
