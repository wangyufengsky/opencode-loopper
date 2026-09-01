package io.opencode.loopper.persistence;

import org.apache.ibatis.annotations.AutomapConstructor;

/** Frozen owner identity and durable remote-create evidence for a V57 generic candidate launch. */
public record GenericCandidateInternalLaunchRow(
        String id, String candidateRunId, String candidateKind,
        String designerSessionId, String taskId, String projectId,
        String ownerType, String ownerId,
        String analysisReportId, String projectConventionDraftId, String judgeRunId,
        String workflowStep, long sourceRevision, String contractVersion, int maxAttempts,
        String state, long preparedOwnerVersion, Long settledOwnerVersion, String settledAt,
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
    @AutomapConstructor public GenericCandidateInternalLaunchRow { }
}
