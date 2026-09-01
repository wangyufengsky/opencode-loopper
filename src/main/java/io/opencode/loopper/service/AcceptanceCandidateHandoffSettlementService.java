package io.opencode.loopper.service;

import io.opencode.loopper.domain.AcceptanceCandidateHandoffState;
import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.domain.LifecycleMachineType;
import io.opencode.loopper.domain.LifecycleScopeType;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.AcceptanceCandidateLegacyHandoffRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.LoopSpecCompilationRow;
import io.opencode.loopper.persistence.DesignWorkPackageRow;
import io.opencode.loopper.persistence.DesignRequirementRevisionRow;
import org.springframework.stereotype.Service;

/** Atomically retires the durable Legacy handoff after its exact candidate writer has stopped. */
@Service
final class AcceptanceCandidateHandoffSettlementService {
    private final LoopperMapper mapper;
    private final LifecycleTransitionService lifecycle;

    AcceptanceCandidateHandoffSettlementService(LoopperMapper mapper, LifecycleTransitionService lifecycle) {
        this.mapper = mapper;
        this.lifecycle = lifecycle;
    }

    void settle(MachineCandidateSubmission.RunSnapshot run, String proof, LoopSpecCompilationRow proofedOwner) {
        AcceptanceCandidateLegacyHandoffRow handoff = mapper
                .findAcceptanceCandidateLegacyHandoffForCompilation(run.owner().id()).orElse(null);
        if (handoff == null) return;
        requireExactBinding(handoff, run, proof, proofedOwner);
        if (AcceptanceCandidateHandoffState.SETTLED.name().equals(handoff.state())) {
            if (!same(handoff.legacyTerminationProof(), proof)
                    || handoff.currentOwnerVersion() != proofedOwner.version()) throw stale();
            return;
        }
        if (!AcceptanceCandidateHandoffState.HANDED_OFF.name().equals(handoff.state())
                || handoff.currentOwnerVersion() != run.ownerVersion()
                || proofedOwner.version() <= run.ownerVersion()
                || handoff.legacyTerminationProof() != null) throw stale();

        String proofAt = java.time.Instant.now().toString();
        AcceptanceCandidateLegacyHandoffRow settled = AcceptanceCandidateLegacyHandoffRows.copy(handoff,
                AcceptanceCandidateHandoffState.SETTLED.name(), proofedOwner.version(),
                handoff.oldExternalState(), handoff.oldTerminationProof(), handoff.oldProofAt(),
                handoff.legacyExternalSessionId(), handoff.legacyRuntimeGenerationId(),
                handoff.legacyEndpointFingerprint(), proof, proof, proofAt, handoff.legacyPromptSha256(),
                handoff.modelCallConsumed(), handoff.modelCallConsumedAt(), null, null, null);
        lifecycle.transition(subject(handoff), handoff.state(), settled.state(), LifecycleEvent.COMPLETE,
                "LEGACY_SUCCESS_REMOTE_STOP_CONFIRMED", AcceptanceCandidateHandoffAudit.from(settled),
                () -> mapper.settleAcceptanceCandidateLegacyHandoff(settled, run.ownerVersion()),
                AcceptanceCandidateHandoffSettlementService::stale);
    }

    private void requireExactBinding(AcceptanceCandidateLegacyHandoffRow handoff,
            MachineCandidateSubmission.RunSnapshot run, String proof, LoopSpecCompilationRow proofedOwner) {
        DesignWorkPackageRow workPackage = mapper.findLatestDesignWorkPackage(
                handoff.designerSessionId(), handoff.workPackageId()).orElse(null);
        DesignRequirementRevisionRow revision = workPackage == null ? null
                : mapper.findDesignRequirementRevision(workPackage.requirementRevisionId()).orElse(null);
        if (!CandidateSessionTerminationProof.persisted(proof)
                || !run.owner().id().equals(handoff.compilationId())
                || !run.scope().id().equals(handoff.designerSessionId())
                || run.sourceRevision() != handoff.sourceDesignRevision()
                || !run.externalSessionId().equals(handoff.legacyExternalSessionId())
                || !run.runtimeGenerationId().equals(handoff.legacyRuntimeGenerationId())
                || !proofedOwner.id().equals(handoff.compilationId())
                || !proofedOwner.designerSessionId().equals(handoff.designerSessionId())
                || !handoff.workPackageId().equals(proofedOwner.workPackageId())
                || proofedOwner.designRevision() != handoff.sourceDesignRevision()
                || !handoff.sourceDesignMessageId().equals(proofedOwner.sourceDesignMessageId())
                || proofedOwner.sourceDraftVersion() != handoff.sourceDraftVersion()
                || workPackage == null
                || !handoff.designerSessionId().equals(workPackage.designerSessionId())
                || !handoff.workPackageId().equals(workPackage.packageId())
                || workPackage.designRevision() != handoff.sourceDesignRevision()
                || !handoff.sourceDesignMessageId().equals(workPackage.designMessageId())
                || revision == null
                || !handoff.designerSessionId().equals(revision.designerSessionId())
                || revision.sourceDraftVersion() != handoff.sourceDraftVersion()
                || !run.externalSessionId().equals(proofedOwner.externalSessionId())
                || !same(proofedOwner.externalSessionState(), proof)
                || !"RUNNING".equals(proofedOwner.state())) throw stale();
    }

    private LifecycleTransitionService.Subject subject(AcceptanceCandidateLegacyHandoffRow row) {
        return new LifecycleTransitionService.Subject(LifecycleMachineType.ACCEPTANCE_CANDIDATE_HANDOFF,
                row.id(), LifecycleScopeType.DESIGNER, row.designerSessionId());
    }

    private static ConflictException stale() {
        return new ConflictException("ACCEPTANCE_LEGACY_HANDOFF_SETTLEMENT_STALE",
                "验收候选正向停止证明与兼容交接的 owner/source/session/generation 不一致");
    }

    private static boolean same(String left, String right) {
        return left == null ? right == null : left.equalsIgnoreCase(right);
    }
}
