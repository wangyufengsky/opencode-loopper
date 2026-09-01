package io.opencode.loopper.persistence;

import org.apache.ibatis.annotations.AutomapConstructor;

/** Durable old-writer proof and successor-writer saga for unopened v7 fallback. */
public record AcceptanceCandidateLegacyHandoffRow(
        String id, String compilationId, String designerSessionId, String workPackageId,
        long sourceDesignRevision, String sourceDesignMessageId, long sourceDraftVersion,
        String sourceDesignSha256, String contractVersion, String state,
        long preparedOwnerVersion, long currentOwnerVersion,
        String oldExternalSessionId, String oldRuntimeGenerationId, String oldEndpointFingerprint,
        String oldExternalState, String oldTerminationProof, String oldProofAt,
        String legacyCreationKey, String successorExactTitle, String successorCanonicalDirectory,
        String successorRuntimeGenerationId, boolean successorManaged, String successorInternalMcpServer,
        String successorEndpointFingerprint, String successorModelProviderId, String successorModelId,
        Boolean successorThinking, String successorProfile, String successorPermissionPolicyJson,
        String successorPermissionPolicyDigest,
        String successorCreateRequestSha256, String successorCreationCredential, String successorAttestationType,
        String createClaimOwner, String createClaimToken,
        String createClaimExpiresAt, long createFence,
        boolean createDispatchAttempted, String createDispatchStartedAt,
        String legacyExternalSessionId, String legacyRuntimeGenerationId,
        String legacyEndpointFingerprint, String legacyExternalState, String legacyTerminationProof,
        String legacyProofAt, String legacyPromptMessageId, String legacyPromptSha256,
        boolean legacyPromptDispatchAttempted, String legacyPromptDispatchStartedAt,
        String promptClaimOwner, String promptClaimToken, String promptClaimExpiresAt, long promptFence,
        boolean modelCallConsumed, String modelCallConsumedAt, String failurePhase,
        String lastErrorCode, String lastErrorDetail, String createdAt, String updatedAt, long version) {
    @AutomapConstructor public AcceptanceCandidateLegacyHandoffRow { }
}
