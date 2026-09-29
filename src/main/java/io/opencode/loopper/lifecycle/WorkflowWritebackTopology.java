package io.opencode.loopper.lifecycle;

import io.opencode.loopper.domain.*;
import io.opencode.loopper.workflow.WorkflowWritebackState;
import static io.opencode.loopper.workflow.WorkflowWritebackState.*;

final class WorkflowWritebackTopology {
    private WorkflowWritebackTopology() { }
    static FiniteStateMachine<WorkflowWritebackState,LifecycleEvent> machine() {
        return FiniteStateMachine.builder(LifecycleMachineType.WORKFLOW_WRITEBACK,WorkflowWritebackState.class,LifecycleEvent.class)
                .transition(CONFIRMED,LifecycleEvent.START,APPLYING)
                .transition(CONFIRMED,LifecycleEvent.REQUIRE_INPUT,BLOCKED)
                .transition(APPLYING,LifecycleEvent.REQUIRE_INPUT,BLOCKED)
                .transition(APPLYING,LifecycleEvent.COMPLETE,APPLIED)
                .transition(BLOCKED,LifecycleEvent.RETRY,CONFIRMED)
                .transition(BLOCKED,LifecycleEvent.RESUME,APPLYING).build();
    }
}
