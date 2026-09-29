package io.opencode.loopper.lifecycle;

import io.opencode.loopper.domain.*;
import io.opencode.loopper.workflow.WorkflowPublicationState;
import static io.opencode.loopper.workflow.WorkflowPublicationState.*;

final class WorkflowPublicationTopology {
    private WorkflowPublicationTopology() { }
    static FiniteStateMachine<WorkflowPublicationState,LifecycleEvent> machine() {
        return FiniteStateMachine.builder(LifecycleMachineType.WORKFLOW_PUBLICATION,WorkflowPublicationState.class,LifecycleEvent.class)
                .transition(CONFIRMED,LifecycleEvent.COMPLETE,COMMITTED)
                .transition(CONFIRMED,LifecycleEvent.REQUIRE_INPUT,BLOCKED)
                .transition(BLOCKED,LifecycleEvent.RETRY,CONFIRMED).build();
    }
}
