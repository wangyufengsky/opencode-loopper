package io.opencode.loopper.persistence;

import org.apache.ibatis.annotations.AutomapConstructor;

/** Immutable JUDGE_DECISION_V1 compilation output plus its one-way Judge settlement marker. */
public record JudgeCandidateAcceptedResultRow(
        String candidateRunId,
        String judgeRunId,
        String reviewBatchId,
        String role,
        long sourceRevision,
        long ownerVersion,
        String contractVersion,
        String canonicalCandidateJson,
        String candidatePayloadSha256,
        String canonicalDecisionJson,
        String canonicalResultSha256,
        String verdict,
        String reason,
        String evidenceJson,
        String settledJudgeRunId,
        String createdAt,
        String updatedAt,
        long version) {
    @AutomapConstructor public JudgeCandidateAcceptedResultRow { }
}
