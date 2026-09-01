package io.opencode.loopper.service;

import static io.opencode.loopper.service.AcceptanceCandidateLegacyHandoffRows.copy;
import static io.opencode.loopper.service.AcceptanceCandidateLegacyHandoffRows.withClaims;

import io.opencode.loopper.domain.AcceptanceCandidateHandoffState;
import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.domain.LifecycleMachineType;
import io.opencode.loopper.domain.LifecycleScopeType;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.AcceptanceCandidateLegacyHandoffRow;
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.LoopperMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Positive-proof merge and cancellation fencing for durable acceptance handoffs. */
@Service
class AcceptanceCandidateHandoffTerminationService {
    private final LoopperMapper mapper;
    private final LifecycleTransitionService lifecycle;
    private final AcceptanceCandidateHandoffGuard guard;

    AcceptanceCandidateHandoffTerminationService(LoopperMapper mapper, LifecycleTransitionService lifecycle,
            AcceptanceCandidateHandoffGuard guard) {
        this.mapper = mapper;
        this.lifecycle = lifecycle;
        this.guard = guard;
    }

    @Transactional
    AcceptanceCandidateLegacyHandoffRow complete(String id, String proof) {
        if (!CandidateSessionTerminationProof.persisted(proof)) {
            throw new ConflictException("OPENCODE_ACCEPTANCE_LEGACY_STOP_UNCONFIRMED",
                    "兼容候选会话缺少正向停止证明");
        }
        AcceptanceCandidateLegacyHandoffRow row = get(id);
        DesignerSessionRow session = mapper.findDesignerSession(row.designerSessionId()).orElseThrow();
        String target;
        if ("STOPPING".equals(session.state()) || "CANCELLED".equals(session.state())) {
            target = AcceptanceCandidateHandoffState.CANCELLED.name();
        } else {
            try {
                guard.attachedOrPreparedOwner(row);
                target = AcceptanceCandidateHandoffState.FAILED_STOPPED.name();
            } catch (ConflictException drift) {
                target = AcceptanceCandidateHandoffState.STALE.name();
            }
        }
        String at = now();
        AcceptanceCandidateLegacyHandoffRow update = copy(row, target, row.currentOwnerVersion(),
                row.oldExternalState(), row.oldTerminationProof(), row.oldProofAt(), row.legacyExternalSessionId(),
                row.legacyRuntimeGenerationId(), row.legacyEndpointFingerprint(), proof, proof, at,
                row.legacyPromptSha256(), row.modelCallConsumed(), row.modelCallConsumedAt(),
                row.failurePhase(), row.lastErrorCode(), row.lastErrorDetail());
        update = withClaims(update, null, null, null, row.createFence() + 1,
                null, null, null, row.promptFence() + 1);
        transition(row, update, target.equals(AcceptanceCandidateHandoffState.CANCELLED.name())
                ? LifecycleEvent.CANCEL : target.equals(AcceptanceCandidateHandoffState.STALE.name())
                ? LifecycleEvent.STALE : LifecycleEvent.FAIL, "LEGACY_REMOTE_STOP_CONFIRMED");
        return get(id);
    }

    @Transactional
    boolean prepareDesignerCancellation(String designerSessionId, Instant instant) {
        boolean blocked = false;
        for (AcceptanceCandidateLegacyHandoffRow original
                : mapper.listAcceptanceCandidateHandoffsForDesigner(designerSessionId)) {
            boolean createActive = active(original.createClaimExpiresAt(), instant);
            boolean promptActive = active(original.promptClaimExpiresAt(), instant);
            boolean createUnknown = original.createDispatchAttempted()
                    && original.legacyExternalSessionId() == null;
            if (createActive || promptActive || createUnknown) {
                blocked = true;
                if (!AcceptanceCandidateHandoffState.STOPPING_LEGACY.name().equals(original.state())) {
                    AcceptanceCandidateLegacyHandoffRow stopping = copy(original,
                            AcceptanceCandidateHandoffState.STOPPING_LEGACY.name(), original.currentOwnerVersion(),
                            original.oldExternalState(), original.oldTerminationProof(), original.oldProofAt(),
                            original.legacyExternalSessionId(), original.legacyRuntimeGenerationId(),
                            original.legacyEndpointFingerprint(), original.legacyExternalState(),
                            original.legacyTerminationProof(), original.legacyProofAt(),
                            original.legacyPromptSha256(), original.modelCallConsumed(),
                            original.modelCallConsumedAt(), "LEGACY_STOP", "DESIGNER_CANCELLED_IO_IN_FLIGHT",
                            "Designer cancellation is waiting for durable candidate I/O reconciliation");
                    transition(original, stopping, LifecycleEvent.ABORT, "DESIGNER_CANCELLED_IO_IN_FLIGHT");
                }
            } else if (original.createClaimOwner() != null || original.promptClaimOwner() != null) {
                AcceptanceCandidateLegacyHandoffRow fenced = withClaims(original, null, null, null,
                        original.createFence() + 1, null, null, null, original.promptFence() + 1);
                mutate(fenced);
            }
        }
        return !blocked;
    }

    @Transactional
    Map<String, String> cancelAfterDesignerRemotesStopped(
            String designerSessionId, Map<String, String> remoteProofs) {
        LinkedHashMap<String, String> merged = new LinkedHashMap<>();
        if (remoteProofs != null) merged.putAll(remoteProofs);
        mapper.listAllAcceptanceCandidateHandoffsForDesigner(designerSessionId).forEach(row -> {
            mergePersisted(merged, row.oldExternalSessionId(), row.oldTerminationProof());
            mergePersisted(merged, row.legacyExternalSessionId(), row.legacyTerminationProof());
        });
        for (AcceptanceCandidateLegacyHandoffRow row
                : mapper.listAcceptanceCandidateHandoffsForDesigner(designerSessionId)) {
            cancel(row, merged);
            AcceptanceCandidateLegacyHandoffRow cancelled = get(row.id());
            mergePersisted(merged, cancelled.oldExternalSessionId(), cancelled.oldTerminationProof());
            mergePersisted(merged, cancelled.legacyExternalSessionId(), cancelled.legacyTerminationProof());
        }
        return Map.copyOf(merged);
    }

    @Transactional
    AcceptanceCandidateLegacyHandoffRow completeRecoveredUnknownCancellation(String id) {
        AcceptanceCandidateLegacyHandoffRow row = get(id);
        if (!AcceptanceCandidateHandoffState.STOPPING_LEGACY.name().equals(row.state())
                || !row.createDispatchAttempted() || row.legacyExternalSessionId() != null
                || row.oldExternalSessionId() != null && row.oldTerminationProof() == null) {
            throw conflict();
        }
        var cleanup = mapper.listAcceptanceCandidateHandoffCleanupRemotes(id);
        if (cleanup.isEmpty() || cleanup.stream().anyMatch(remote ->
                !CandidateSessionTerminationProof.persisted(remote.terminationProof()))) {
            throw new ConflictException("OPENCODE_ACCEPTANCE_LEGACY_STOP_UNCONFIRMED",
                    "兼容候选未知创建结果尚未全部确认停止");
        }
        cancel(row, Map.of());
        return get(id);
    }

    private static void mergePersisted(Map<String, String> proofs, String remoteId, String proof) {
        if (remoteId != null && CandidateSessionTerminationProof.persisted(proof)) proofs.put(remoteId, proof);
    }

    private void cancel(AcceptanceCandidateLegacyHandoffRow row, Map<String, String> remoteProofs) {
        String oldProof = row.oldExternalSessionId() == null ? null
                : row.oldTerminationProof() != null ? row.oldTerminationProof()
                : requiredProof(remoteProofs, row.oldExternalSessionId());
        String newProof = row.legacyExternalSessionId() == null ? row.legacyTerminationProof()
                : row.legacyTerminationProof() != null ? row.legacyTerminationProof()
                : requiredProof(remoteProofs, row.legacyExternalSessionId());
        AcceptanceCandidateLegacyHandoffRow stopping;
        String at = now();
        AcceptanceCandidateLegacyHandoffRow proofed = copy(row,
                AcceptanceCandidateHandoffState.STOPPING_LEGACY.name(), row.currentOwnerVersion(),
                row.oldExternalSessionId() == null ? row.oldExternalState() : oldProof, oldProof,
                oldProof == null ? null : row.oldProofAt() == null ? at : row.oldProofAt(),
                row.legacyExternalSessionId(), row.legacyRuntimeGenerationId(), row.legacyEndpointFingerprint(),
                row.legacyExternalSessionId() == null ? row.legacyExternalState() : newProof, newProof,
                row.legacyExternalSessionId() == null ? row.legacyProofAt() : at,
                row.legacyPromptSha256(), row.modelCallConsumed(), row.modelCallConsumedAt(), "LEGACY_STOP",
                "DESIGNER_CANCELLED", "Designer remotes stopped");
        transition(row, proofed, AcceptanceCandidateHandoffState.STOPPING_LEGACY.name().equals(row.state())
                ? LifecycleEvent.UPDATE : LifecycleEvent.ABORT, "DESIGNER_REMOTE_STOP_PROOF_PERSISTED");
        stopping = get(row.id());
        AcceptanceCandidateLegacyHandoffRow cancelled = copy(stopping,
                AcceptanceCandidateHandoffState.CANCELLED.name(), stopping.currentOwnerVersion(),
                stopping.oldExternalState(), stopping.oldTerminationProof(), stopping.oldProofAt(),
                stopping.legacyExternalSessionId(), stopping.legacyRuntimeGenerationId(),
                stopping.legacyEndpointFingerprint(), stopping.legacyExternalState(),
                stopping.legacyTerminationProof(), stopping.legacyProofAt(), stopping.legacyPromptSha256(),
                stopping.modelCallConsumed(), stopping.modelCallConsumedAt(), stopping.failurePhase(),
                "DESIGNER_CANCELLED", "Designer remotes stopped");
        cancelled = withClaims(cancelled, null, null, null, stopping.createFence() + 1,
                null, null, null, stopping.promptFence() + 1);
        transition(stopping, cancelled, LifecycleEvent.CANCEL, "DESIGNER_CANCELLED");
    }

    private String requiredProof(Map<String, String> proofs, String remoteId) {
        String proof = proofs == null ? null : proofs.get(remoteId);
        if (!CandidateSessionTerminationProof.persisted(proof)) {
            throw new ConflictException("DESIGNER_REMOTE_STOP_PROOF_MISSING",
                    "Designer remote Session stop proof is missing for " + remoteId);
        }
        return proof;
    }

    private AcceptanceCandidateLegacyHandoffRow get(String id) {
        return mapper.findAcceptanceCandidateLegacyHandoff(id).orElseThrow();
    }

    private void mutate(AcceptanceCandidateLegacyHandoffRow row) {
        if (mapper.updateAcceptanceCandidateLegacyHandoff(row) != 1) throw conflict();
    }

    private void transition(AcceptanceCandidateLegacyHandoffRow from,
            AcceptanceCandidateLegacyHandoffRow to, LifecycleEvent event, String reason) {
        lifecycle.transition(subject(from), from.state(), to.state(), event, reason,
                AcceptanceCandidateHandoffAudit.from(to),
                () -> mapper.updateAcceptanceCandidateLegacyHandoff(to),
                AcceptanceCandidateHandoffTerminationService::conflict);
    }

    private LifecycleTransitionService.Subject subject(AcceptanceCandidateLegacyHandoffRow row) {
        return new LifecycleTransitionService.Subject(LifecycleMachineType.ACCEPTANCE_CANDIDATE_HANDOFF,
                row.id(), LifecycleScopeType.DESIGNER, row.designerSessionId());
    }

    private static boolean active(String expiresAt, Instant instant) {
        return expiresAt != null && Instant.parse(expiresAt).isAfter(instant);
    }

    private static String now() { return Instant.now().toString(); }
    private static ConflictException conflict() {
        return new ConflictException("ACCEPTANCE_LEGACY_HANDOFF_VERSION_CONFLICT",
                "验收候选兼容交接发生并发变化");
    }
}
