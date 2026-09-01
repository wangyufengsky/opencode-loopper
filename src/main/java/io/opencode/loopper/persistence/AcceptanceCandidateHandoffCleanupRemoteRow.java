package io.opencode.loopper.persistence;

import org.apache.ibatis.annotations.AutomapConstructor;

/** One durably remembered remote in an ambiguous successor-creation result. */
public record AcceptanceCandidateHandoffCleanupRemoteRow(
        String handoffId, String externalSessionId, String runtimeGenerationId,
        String endpointFingerprint, String directorySha256, String titleSha256,
        String state, String terminationProof, String proofAt,
        String stopClaimOwner, String stopClaimToken, String stopClaimExpiresAt, long stopFence,
        String lastErrorCode,
        String lastErrorDetail, String createdAt, String updatedAt, long version) {
    @AutomapConstructor public AcceptanceCandidateHandoffCleanupRemoteRow { }
}
