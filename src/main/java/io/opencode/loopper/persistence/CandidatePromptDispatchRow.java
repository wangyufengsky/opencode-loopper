package io.opencode.loopper.persistence;

import org.apache.ibatis.annotations.AutomapConstructor;

/** Immutable candidate prompt request plus its durable dispatch and termination checkpoints. */
public record CandidatePromptDispatchRow(
        String id, String runId, String internalLaunchId, String candidateLaunchId,
        String dispatchKind, Integer sourceAttemptOrdinal,
        String externalSessionId,
        String runtimeGenerationId, String messageId,
        String requestJson, String requestSha256, String state,
        boolean modelCallConsumed, String modelCallConsumedAt,
        String claimOwner, String claimToken, String claimExpiresAt, long fence,
        boolean dispatchAttempted, String dispatchStartedAt,
        boolean acknowledged, String ackedAt,
        String terminationProof, String terminationProofAt,
        String lastErrorCode, String lastErrorDetail,
        String createdAt, String updatedAt, long version) {
    @AutomapConstructor public CandidatePromptDispatchRow { }
}
