package io.opencode.loopper.lifecycle;

import io.opencode.loopper.domain.*;
import io.opencode.loopper.service.ppt.agent.PptAgentState;
import static io.opencode.loopper.service.ppt.agent.PptAgentState.*;
import static io.opencode.loopper.domain.LifecycleEvent.*;

final class PptAgentTopology {
    private PptAgentTopology() { }
    static FiniteStateMachine<PptAgentState, LifecycleEvent> machine() {
        var b = FiniteStateMachine.builder(LifecycleMachineType.PPT_AGENT_RUN, PptAgentState.class, LifecycleEvent.class)
                .transition(PREPARED, PREPARE, CREATING).transition(PREPARED, DISPATCH, SENDING)
                .transition(CREATING, DISCONNECT, CREATE_UNKNOWN).transition(CREATE_UNKNOWN, RECOVER, CREATING)
                .transition(CREATING, DISPATCH, SENDING).transition(SENDING, START, RUNNING)
                .transition(SENDING, DISCONNECT, UNKNOWN).transition(UNKNOWN, RECOVER, RUNNING)
                .transition(RUNNING, COMPLETE, COMPLETED).transition(PREPARED, FAIL, FAILED)
                .transition(RUNNING, FAIL, FAILED).transition(STOPPING, ABORT, STOPPED).transition(STOPPING, FAIL, FAILED)
                .transition(STOPPING, REQUIRE_INPUT, WAITING_INPUT).transition(WAITING_INPUT, RESUME, PREPARED);
        for (var state : PptAgentState.values()) if (!state.terminal() && state != STOPPING) b.transition(state, CANCEL, STOPPING);
        return b.build();
    }
}
