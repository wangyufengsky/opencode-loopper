package io.opencode.loopper.service;

import io.opencode.loopper.persistence.AcceptanceCandidateLegacyHandoffRow;
import io.opencode.loopper.persistence.LoopSpecCompilationRow;
import java.time.Instant;

/** Mechanical immutable-row updates for the durable acceptance handoff aggregate. */
final class AcceptanceCandidateLegacyHandoffRows {
    private AcceptanceCandidateLegacyHandoffRows() { }

    static AcceptanceCandidateLegacyHandoffRow copy(AcceptanceCandidateLegacyHandoffRow row,
            String state, long ownerVersion, String oldState, String oldProof, String oldProofAt,
            String newRemote, String newGeneration, String newFingerprint, String newState,
            String newProof, String newProofAt, String promptSha, boolean consumed, String consumedAt,
            String phase, String code, String detail) {
        return new AcceptanceCandidateLegacyHandoffRow(row.id(), row.compilationId(), row.designerSessionId(),
                row.workPackageId(), row.sourceDesignRevision(), row.sourceDesignMessageId(),
                row.sourceDraftVersion(), row.sourceDesignSha256(), row.contractVersion(), state,
                row.preparedOwnerVersion(), ownerVersion, row.oldExternalSessionId(),
                row.oldRuntimeGenerationId(), row.oldEndpointFingerprint(), oldState, oldProof, oldProofAt,
                row.legacyCreationKey(), row.successorExactTitle(), row.successorCanonicalDirectory(),
                row.successorRuntimeGenerationId(), row.successorManaged(), row.successorInternalMcpServer(),
                row.successorEndpointFingerprint(), row.successorModelProviderId(), row.successorModelId(),
                row.successorThinking(), row.successorProfile(), row.successorPermissionPolicyJson(),
                row.successorPermissionPolicyDigest(), row.successorCreateRequestSha256(),
                row.successorCreationCredential(), row.successorAttestationType(),
                row.createClaimOwner(), row.createClaimToken(), row.createClaimExpiresAt(), row.createFence(),
                row.createDispatchAttempted(), row.createDispatchStartedAt(),
                newRemote, newGeneration, newFingerprint, newState, newProof, newProofAt,
                row.legacyPromptMessageId(), promptSha, row.legacyPromptDispatchAttempted(),
                row.legacyPromptDispatchStartedAt(), row.promptClaimOwner(), row.promptClaimToken(),
                row.promptClaimExpiresAt(), row.promptFence(), consumed, consumedAt, phase, code, detail,
                row.createdAt(), now(), row.version());
    }

    static AcceptanceCandidateLegacyHandoffRow withClaims(AcceptanceCandidateLegacyHandoffRow row,
            String createOwner, String createToken, String createExpiresAt, long createFence,
            String promptOwner, String promptToken, String promptExpiresAt, long promptFence) {
        return new AcceptanceCandidateLegacyHandoffRow(row.id(), row.compilationId(), row.designerSessionId(),
                row.workPackageId(), row.sourceDesignRevision(), row.sourceDesignMessageId(),
                row.sourceDraftVersion(), row.sourceDesignSha256(), row.contractVersion(), row.state(),
                row.preparedOwnerVersion(), row.currentOwnerVersion(), row.oldExternalSessionId(),
                row.oldRuntimeGenerationId(), row.oldEndpointFingerprint(), row.oldExternalState(),
                row.oldTerminationProof(), row.oldProofAt(), row.legacyCreationKey(), row.successorExactTitle(),
                row.successorCanonicalDirectory(), row.successorRuntimeGenerationId(), row.successorManaged(),
                row.successorInternalMcpServer(), row.successorEndpointFingerprint(),
                row.successorModelProviderId(), row.successorModelId(), row.successorThinking(),
                row.successorProfile(), row.successorPermissionPolicyJson(), row.successorPermissionPolicyDigest(),
                row.successorCreateRequestSha256(), row.successorCreationCredential(), row.successorAttestationType(),
                createOwner, createToken, createExpiresAt, createFence,
                row.createDispatchAttempted(), row.createDispatchStartedAt(), row.legacyExternalSessionId(),
                row.legacyRuntimeGenerationId(), row.legacyEndpointFingerprint(), row.legacyExternalState(),
                row.legacyTerminationProof(), row.legacyProofAt(), row.legacyPromptMessageId(),
                row.legacyPromptSha256(), row.legacyPromptDispatchAttempted(),
                row.legacyPromptDispatchStartedAt(), promptOwner, promptToken, promptExpiresAt, promptFence,
                row.modelCallConsumed(), row.modelCallConsumedAt(), row.failurePhase(), row.lastErrorCode(),
                row.lastErrorDetail(), row.createdAt(), now(), row.version());
    }

    static AcceptanceCandidateLegacyHandoffRow withCreateDispatch(
            AcceptanceCandidateLegacyHandoffRow row, boolean attempted, String startedAt) {
        AcceptanceCandidateLegacyHandoffRow claims = withClaims(row, row.createClaimOwner(), row.createClaimToken(),
                row.createClaimExpiresAt(), row.createFence(), row.promptClaimOwner(), row.promptClaimToken(),
                row.promptClaimExpiresAt(), row.promptFence());
        return new AcceptanceCandidateLegacyHandoffRow(claims.id(), claims.compilationId(), claims.designerSessionId(),
                claims.workPackageId(), claims.sourceDesignRevision(), claims.sourceDesignMessageId(),
                claims.sourceDraftVersion(), claims.sourceDesignSha256(), claims.contractVersion(), claims.state(),
                claims.preparedOwnerVersion(), claims.currentOwnerVersion(), claims.oldExternalSessionId(),
                claims.oldRuntimeGenerationId(), claims.oldEndpointFingerprint(), claims.oldExternalState(),
                claims.oldTerminationProof(), claims.oldProofAt(), claims.legacyCreationKey(), claims.successorExactTitle(),
                claims.successorCanonicalDirectory(), claims.successorRuntimeGenerationId(), claims.successorManaged(),
                claims.successorInternalMcpServer(), claims.successorEndpointFingerprint(),
                claims.successorModelProviderId(), claims.successorModelId(), claims.successorThinking(),
                claims.successorProfile(), claims.successorPermissionPolicyJson(),
                claims.successorPermissionPolicyDigest(), claims.successorCreateRequestSha256(),
                claims.successorCreationCredential(), claims.successorAttestationType(), claims.createClaimOwner(),
                claims.createClaimToken(), claims.createClaimExpiresAt(), claims.createFence(), attempted, startedAt,
                claims.legacyExternalSessionId(), claims.legacyRuntimeGenerationId(),
                claims.legacyEndpointFingerprint(), claims.legacyExternalState(), claims.legacyTerminationProof(),
                claims.legacyProofAt(), claims.legacyPromptMessageId(), claims.legacyPromptSha256(),
                claims.legacyPromptDispatchAttempted(), claims.legacyPromptDispatchStartedAt(),
                claims.promptClaimOwner(), claims.promptClaimToken(), claims.promptClaimExpiresAt(),
                claims.promptFence(), claims.modelCallConsumed(), claims.modelCallConsumedAt(), claims.failurePhase(),
                claims.lastErrorCode(), claims.lastErrorDetail(), claims.createdAt(), now(), claims.version());
    }

    static AcceptanceCandidateLegacyHandoffRow withPromptDispatch(
            AcceptanceCandidateLegacyHandoffRow row, boolean attempted, String startedAt) {
        return new AcceptanceCandidateLegacyHandoffRow(row.id(), row.compilationId(), row.designerSessionId(),
                row.workPackageId(), row.sourceDesignRevision(), row.sourceDesignMessageId(),
                row.sourceDraftVersion(), row.sourceDesignSha256(), row.contractVersion(), row.state(),
                row.preparedOwnerVersion(), row.currentOwnerVersion(), row.oldExternalSessionId(),
                row.oldRuntimeGenerationId(), row.oldEndpointFingerprint(), row.oldExternalState(),
                row.oldTerminationProof(), row.oldProofAt(), row.legacyCreationKey(), row.successorExactTitle(),
                row.successorCanonicalDirectory(), row.successorRuntimeGenerationId(), row.successorManaged(),
                row.successorInternalMcpServer(), row.successorEndpointFingerprint(), row.successorModelProviderId(),
                row.successorModelId(), row.successorThinking(), row.successorProfile(),
                row.successorPermissionPolicyJson(), row.successorPermissionPolicyDigest(),
                row.successorCreateRequestSha256(), row.successorCreationCredential(), row.successorAttestationType(),
                row.createClaimOwner(), row.createClaimToken(), row.createClaimExpiresAt(), row.createFence(),
                row.createDispatchAttempted(), row.createDispatchStartedAt(), row.legacyExternalSessionId(),
                row.legacyRuntimeGenerationId(), row.legacyEndpointFingerprint(), row.legacyExternalState(),
                row.legacyTerminationProof(), row.legacyProofAt(), row.legacyPromptMessageId(),
                row.legacyPromptSha256(), attempted, startedAt, row.promptClaimOwner(), row.promptClaimToken(),
                row.promptClaimExpiresAt(), row.promptFence(), row.modelCallConsumed(), row.modelCallConsumedAt(),
                row.failurePhase(), row.lastErrorCode(), row.lastErrorDetail(), row.createdAt(), now(), row.version());
    }

    static LoopSpecCompilationRow copyCompilation(LoopSpecCompilationRow row,
            String remoteId, String remoteState, String code, String detail, long version) {
        return new LoopSpecCompilationRow(row.id(), row.designerSessionId(), row.designRevision(), row.state(),
                remoteId, remoteState, row.repairCount(), row.sourceDesignMessageId(), row.sourceDraftVersion(),
                code, detail, row.createdAt(), now(), version, row.workPackageId(), row.transportRetryCount(),
                row.compiledPackageJson(), row.workflowStep(), row.planningJson(), row.planningRepairCount(),
                row.planningResponseMode(), row.planningResponseSchemaId(), row.planningFormatFallbackUsed(),
                row.finalResponseMode(), row.finalResponseSchemaId(), row.finalFormatFallbackUsed(),
                row.semanticPlanJson(), row.formatRepairCount(), row.semanticRepairCount(), row.serverCompiled(),
                row.compilationSource(), row.fallbackReason());
    }

    private static String now() { return Instant.now().toString(); }
}
