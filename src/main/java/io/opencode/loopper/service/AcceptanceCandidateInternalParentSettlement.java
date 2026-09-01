package io.opencode.loopper.service;

import io.opencode.loopper.domain.AcceptanceCandidateInternalParentAction;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Couples the final local parent mutation with durable intent completion. */
@Service
class AcceptanceCandidateInternalParentSettlement {
    private final AcceptanceCandidateInternalTerminationWorkflow terminations;

    AcceptanceCandidateInternalParentSettlement(
            AcceptanceCandidateInternalTerminationWorkflow terminations) {
        this.terminations = terminations;
    }

    @Transactional
    void settleOwnerReplacement(String designerSessionId, Runnable parentMutation) {
        if (parentMutation == null) throw new IllegalArgumentException("Parent mutation is required");
        parentMutation.run();
        terminations.completeReadyParentActionInCurrentTransaction(
                designerSessionId, AcceptanceCandidateInternalParentAction.OWNER_REPLACEMENT);
    }

    @Transactional
    void settleInitialFailure(String intentId, Runnable parentMutation) {
        if (parentMutation == null) throw new IllegalArgumentException("Parent mutation is required");
        parentMutation.run();
        terminations.completeReadyInitialFailureInCurrentTransaction(intentId);
    }
}
