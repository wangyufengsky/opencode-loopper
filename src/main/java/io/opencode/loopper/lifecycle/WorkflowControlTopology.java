package io.opencode.loopper.lifecycle;
import io.opencode.loopper.domain.*;
import io.opencode.loopper.workflow.WorkflowControlState;
import static io.opencode.loopper.domain.LifecycleEvent.*;
import static io.opencode.loopper.workflow.WorkflowControlState.*;
final class WorkflowControlTopology {
    private WorkflowControlTopology() { }
    static FiniteStateMachine<WorkflowControlState,LifecycleEvent> machine() {
        var builder=FiniteStateMachine.builder(LifecycleMachineType.WORKFLOW_CONTROL,WorkflowControlState.class,LifecycleEvent.class);
        for(var state:new WorkflowControlState[]{ACTIVE,PAUSED,WAITING,STALLED}) {
            builder.transition(state,UPDATE,state).transition(state,COMPLETE,DONE);
            if(state!=ACTIVE) builder.transition(state,RESUME,ACTIVE);
            if(state!=PAUSED) builder.transition(state,PAUSE,PAUSED);
            if(state!=WAITING) builder.transition(state,REQUIRE_INPUT,WAITING);
            if(state!=STALLED) builder.transition(state,FAIL,STALLED);
        }
        return builder.build();
    }
}
