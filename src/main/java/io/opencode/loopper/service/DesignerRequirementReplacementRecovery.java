package io.opencode.loopper.service;

import io.opencode.loopper.domain.AcceptanceCandidateInternalParentAction;
import org.springframework.stereotype.Service;

/** Resumes a persisted user requirement-replacement command after a crash. */
@Service
final class DesignerRequirementReplacementRecovery {
    private final AcceptanceCandidateInternalTerminationWorkflow terminations;
    private final DesignerSessionService designers;

    DesignerRequirementReplacementRecovery(
            AcceptanceCandidateInternalTerminationWorkflow terminations,
            DesignerSessionService designers) {
        this.terminations = terminations;
        this.designers = designers;
    }

    void recover() {
        terminations.activeParentActionDesigners(AcceptanceCandidateInternalParentAction.OWNER_REPLACEMENT)
                .forEach(sessionId -> {
                    try {
                        var intent = terminations.active(sessionId).stream()
                                .filter(row -> AcceptanceCandidateInternalParentAction.OWNER_REPLACEMENT.name()
                                        .equals(row.parentAction()))
                                .findFirst().orElseThrow();
                        designers.reopenRequirement(sessionId, intent.anchorDiscussionRevision());
                    } catch (RuntimeException ignoredConcurrentRecovery) { }
                });
    }
}
