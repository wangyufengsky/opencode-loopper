package io.opencode.loopper.lifecycle;
import io.opencode.loopper.domain.*;
import io.opencode.loopper.workflow.WorkflowPushState;
import static io.opencode.loopper.workflow.WorkflowPushState.*;
final class WorkflowPushTopology {
    private WorkflowPushTopology() { }
    static FiniteStateMachine<WorkflowPushState,LifecycleEvent> machine(){return FiniteStateMachine.builder(LifecycleMachineType.WORKFLOW_PUSH,WorkflowPushState.class,LifecycleEvent.class)
            .transition(PREPARING,LifecycleEvent.START,RUNNING).transition(PREPARING,LifecycleEvent.REQUIRE_INPUT,BLOCKED)
            .transition(RUNNING,LifecycleEvent.REQUIRE_INPUT,BLOCKED).transition(RUNNING,LifecycleEvent.COMPLETE,PUSHED)
            .transition(BLOCKED,LifecycleEvent.RETRY,PREPARING).transition(BLOCKED,LifecycleEvent.RESUME,RUNNING).build();}
}
