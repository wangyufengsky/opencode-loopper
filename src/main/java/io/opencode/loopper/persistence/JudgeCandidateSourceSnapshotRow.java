package io.opencode.loopper.persistence;

import org.apache.ibatis.annotations.AutomapConstructor;

/** Immutable, DB-only JUDGE_DECISION_V1 prompt and evidence frozen before remote create I/O. */
public record JudgeCandidateSourceSnapshotRow(
        String candidateRunId,
        String judgeRunId,
        String taskId,
        String executionCycleId,
        String finalAttemptId,
        String reviewBatchId,
        String role,
        int ordinal,
        long sourceRevision,
        long preparedOwnerVersion,
        String contractVersion,
        String sourcePrompt,
        String sourcePromptSha256,
        String canonicalEvidenceJson,
        String evidenceSha256,
        String createdAt) {
    @AutomapConstructor public JudgeCandidateSourceSnapshotRow { }
}
