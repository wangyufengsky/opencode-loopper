package io.opencode.loopper.lifecycle;

import static io.opencode.loopper.domain.LifecycleEvent.*;
import static io.opencode.loopper.workflow.WorkflowState.*;
import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.domain.LifecycleMachineType;
import io.opencode.loopper.workflow.WorkflowState;

/** Guards and stop proofs belong to the application; this is the allowed requirement state topology. */
final class WorkflowTopology {
    private WorkflowTopology() { }
    static FiniteStateMachine<WorkflowState, LifecycleEvent> requirement() {
        var builder = FiniteStateMachine.builder(LifecycleMachineType.WORKFLOW_REQUIREMENT, WorkflowState.class, LifecycleEvent.class)
                .transition(PLANNING, CONFIRM, PENDING_START)
                .transition(PENDING_START, UPDATE, PLANNING)
                .transition(PENDING_START, START, RUNNING)
                .transition(RUNNING, PAUSE, PAUSED)
                .transition(RUNNING, REQUIRE_INPUT, STALLED)
                .transition(PAUSED, RESUME, RUNNING)
                .transition(STALLED, RESUME, RUNNING);
        for (var state : new WorkflowState[]{PLANNING, PENDING_START, RUNNING, PAUSED, STALLED})
            builder.transition(state, CANCEL, STOPPING);
        builder.transition(STOPPING, ABORT, CANCELLED);
        builder.transition(STOPPING, SUCCEED, COMPLETED);
        builder.transition(STOPPING, FAIL, FAILED);
        builder.transition(RUNNING, COMPLETE, COMPLETED);
        return builder.build();
    }
}
