package io.opencode.loopper.lifecycle;

import io.opencode.loopper.domain.*;
import static io.opencode.loopper.domain.LifecycleEvent.*;

final class PptTopology {
    private PptTopology() { }
    static FiniteStateMachine<PptPhase, LifecycleEvent> document() {
        var b = FiniteStateMachine.builder(LifecycleMachineType.PPT_DOCUMENT, PptPhase.class, LifecycleEvent.class)
                .transition(PptPhase.BRIEFING, COMPLETE, PptPhase.DIRECTION)
                .transition(PptPhase.DIRECTION, COMPLETE, PptPhase.DESIGN)
                .transition(PptPhase.DESIGN, START, PptPhase.PRODUCING)
                .transition(PptPhase.PRODUCING, COMPLETE, PptPhase.REVIEW)
                .transition(PptPhase.REVIEW, COMPLETE, PptPhase.EXPORTED)
                .transition(PptPhase.EXPORTED, RESUME, PptPhase.REVIEW)
                .transition(PptPhase.REVIEW, RESUME, PptPhase.DESIGN)
                .transition(PptPhase.EXPORTED, RECOVER, PptPhase.DESIGN);
        for (var phase : PptPhase.values()) if (phase != PptPhase.BRIEFING) b.transition(phase, REOPEN_REQUIREMENT, PptPhase.BRIEFING);
        return b.build();
    }
    static FiniteStateMachine<PptJobState, LifecycleEvent> job() {
        return FiniteStateMachine.builder(LifecycleMachineType.PPT_JOB, PptJobState.class, LifecycleEvent.class)
                .transition(PptJobState.PREPARED, START, PptJobState.RUNNING)
                .transition(PptJobState.PREPARED, CANCEL, PptJobState.CANCELLED)
                .transition(PptJobState.RUNNING, COMPLETE, PptJobState.COMPLETED)
                .transition(PptJobState.RUNNING, FAIL, PptJobState.FAILED)
                .transition(PptJobState.FAILED, RETRY, PptJobState.PREPARED)
                .transition(PptJobState.RUNNING, CANCEL, PptJobState.CANCELLED).build();
    }
}
