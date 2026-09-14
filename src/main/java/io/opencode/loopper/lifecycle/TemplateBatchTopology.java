package io.opencode.loopper.lifecycle;

import static io.opencode.loopper.domain.LifecycleEvent.*;
import static io.opencode.loopper.domain.TemplateBatchState.*;
import io.opencode.loopper.domain.LifecycleMachineType;
import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.domain.TemplateBatchState;

final class TemplateBatchTopology {
    private TemplateBatchTopology() { }
    static FiniteStateMachine<TemplateBatchState, LifecycleEvent> machine() {
        return machine(LifecycleMachineType.TEMPLATE_BATCH);
    }
    static FiniteStateMachine<TemplateBatchState, LifecycleEvent> machine(LifecycleMachineType type) {
        var builder = FiniteStateMachine.builder(type, TemplateBatchState.class, LifecycleEvent.class)
                .transition(PREPARED, PREPARE, CREATING).transition(CREATING, PREPARATION_SUCCEEDED, PROMPT_READY)
                .transition(PROMPT_READY, DISPATCH, DISPATCHING).transition(DISPATCHING, START, RUNNING)
                .transition(RUNNING, COMPLETE, VALIDATED).transition(PREPARED, COMPLETE, VALIDATED)
                .transition(RUNNING, RETRY, DISPATCHING)
                .transition(PREPARED, FAIL, FAILED).transition(RUNNING, VERIFICATION_FAIL, FAILED)
                .transition(STOPPING, ABORT, STOPPED);
        for (TemplateBatchState state : TemplateBatchState.values()) {
            if (!state.terminal() && state != STOPPING) builder.transition(state, CANCEL, STOPPING);
        }
        return builder.build();
    }
}
