package io.opencode.loopper.persistence;

import org.apache.ibatis.annotations.AutomapConstructor;

/** One attested remote registered before a V57 cleanup stop is dispatched. */
public record GenericCandidateInternalLaunchCleanupRemoteRow(
        String launchId, String externalSessionId, String runtimeGenerationId,
        String endpointFingerprint, String directorySha256, String titleSha256,
        String purpose, String state, String terminationProof, String proofAt,
        String stopClaimOwner, String stopClaimToken, String stopClaimExpiresAt, long stopFence,
        boolean stopDispatchAttempted, String stopDispatchStartedAt,
        String lastErrorCode, String lastErrorDetail,
        String createdAt, String updatedAt, long version) {
    @AutomapConstructor public GenericCandidateInternalLaunchCleanupRemoteRow { }
}
