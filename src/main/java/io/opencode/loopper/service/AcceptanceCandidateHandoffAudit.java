package io.opencode.loopper.service;

import io.opencode.loopper.persistence.AcceptanceCandidateLegacyHandoffRow;
import java.util.LinkedHashMap;
import java.util.Map;

/** Non-sensitive lifecycle evidence shared by handoff state owners. */
final class AcceptanceCandidateHandoffAudit {
    private AcceptanceCandidateHandoffAudit() { }

    static Map<String, ?> from(AcceptanceCandidateLegacyHandoffRow row) {
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("compilationId", row.compilationId());
        audit.put("ownerVersion", row.currentOwnerVersion());
        if (row.oldExternalSessionId() != null) audit.put("oldRemoteId", row.oldExternalSessionId());
        if (row.oldRuntimeGenerationId() != null) audit.put("oldGeneration", row.oldRuntimeGenerationId());
        if (row.oldEndpointFingerprint() != null) audit.put("oldFingerprint", row.oldEndpointFingerprint());
        if (row.oldTerminationProof() != null) audit.put("oldProof", row.oldTerminationProof());
        audit.put("creationKey", row.legacyCreationKey());
        audit.put("messageId", row.legacyPromptMessageId());
        if (row.legacyExternalSessionId() != null) audit.put("newRemoteId", row.legacyExternalSessionId());
        if (row.legacyRuntimeGenerationId() != null) audit.put("newGeneration", row.legacyRuntimeGenerationId());
        if (row.legacyEndpointFingerprint() != null) audit.put("newFingerprint", row.legacyEndpointFingerprint());
        if (row.legacyTerminationProof() != null) audit.put("newProof", row.legacyTerminationProof());
        return Map.copyOf(audit);
    }
}
