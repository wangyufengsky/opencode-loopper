package io.opencode.loopper.persistence;

import org.apache.ibatis.annotations.AutomapConstructor;

/** Frozen owner/planning identity plus durable remote-create checkpoints for one internal launch. */
public record AcceptanceCandidateInternalLaunchRow(
        String id, String compilationId, String designerSessionId, String workPackageId,
        long sourceDesignRevision, String sourceDesignMessageId, long sourceDraftVersion,
        String sourceDesignSha256, long planningVersion, String planningBindingSource,
        String planningBindingJson, String planningBindingSha256,
        String routePlanJson, String routePlanSha256, String candidateRunId,
        String contractVersion, String workflowStep, String state,
        long preparedOwnerVersion, Long settledOwnerVersion, String settledAt,
        String exactTitle, String canonicalDirectory, String runtimeGenerationId,
        boolean managed, String internalMcpServer, String endpointFingerprint,
        String modelProviderId, String modelId, Boolean thinking, String profile,
        String permissionPolicyJson, String permissionPolicyDigest,
        String createRequestSha256, String creationCredential, String attestationType,
        String createClaimOwner, String createClaimToken, String createClaimExpiresAt, long createFence,
        boolean createDispatchAttempted, String createDispatchStartedAt,
        String externalSessionId, String externalAttestedAt,
        String terminationProof, String proofAt, String failurePhase,
        String lastErrorCode, String lastErrorDetail,
        String createdAt, String updatedAt, long version) {
    @AutomapConstructor public AcceptanceCandidateInternalLaunchRow { }
}
