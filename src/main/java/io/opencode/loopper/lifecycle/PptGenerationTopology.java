package io.opencode.loopper.lifecycle;

import io.opencode.loopper.domain.*;
import io.opencode.loopper.service.ppt.generation.PptGenerationState;
import static io.opencode.loopper.service.ppt.generation.PptGenerationState.*;
import static io.opencode.loopper.domain.LifecycleEvent.*;

final class PptGenerationTopology {
    private PptGenerationTopology() { }
    static FiniteStateMachine<PptGenerationState,LifecycleEvent> machine() {
        var b=FiniteStateMachine.builder(LifecycleMachineType.PPT_GENERATION,PptGenerationState.class,LifecycleEvent.class)
                .transition(PLANNING,COMPLETE,PRODUCING).transition(PRODUCING,COMPLETE,PREVIEW)
                .transition(PREVIEW,COMPLETE,EXPORT).transition(EXPORT,COMPLETE,COMPLETED)
                .transition(PLANNING,REQUIRE_INPUT,WAITING_INPUT).transition(PRODUCING,REQUIRE_INPUT,WAITING_INPUT)
                .transition(WAITING_INPUT,RESUME,PLANNING).transition(WAITING_INPUT,START,PRODUCING)
                .transition(STOPPING,ABORT,STOPPED);
        for(var state:PptGenerationState.values()) if(!state.terminal()&&state!=STOPPING) {
            b.transition(state,CANCEL,STOPPING); b.transition(state,FAIL,FAILED);
        }
        for(var terminal:new PptGenerationState[]{STOPPED,FAILED}) {
            b.transition(terminal,RESUME,PLANNING).transition(terminal,START,PRODUCING)
                    .transition(terminal,PREPARE,PREVIEW).transition(terminal,DISPATCH,EXPORT);
        }
        return b.build();
    }
}
