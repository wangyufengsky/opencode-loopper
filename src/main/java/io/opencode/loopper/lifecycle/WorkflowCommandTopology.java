package io.opencode.loopper.lifecycle;
import io.opencode.loopper.domain.*;
import io.opencode.loopper.workflow.WorkflowCommandState;
final class WorkflowCommandTopology {
    private WorkflowCommandTopology() { }
    static FiniteStateMachine<WorkflowCommandState,LifecycleEvent> machine() {
        var builder=FiniteStateMachine.builder(LifecycleMachineType.WORKFLOW_COMMAND,WorkflowCommandState.class,LifecycleEvent.class)
                .transition(WorkflowCommandState.PREPARING,LifecycleEvent.PREPARE,WorkflowCommandState.READY)
                .transition(WorkflowCommandState.READY,LifecycleEvent.START,WorkflowCommandState.RUNNING)
                .transition(WorkflowCommandState.RUNNING,LifecycleEvent.COMPLETE,WorkflowCommandState.SUCCEEDED)
                .transition(WorkflowCommandState.RUNNING,LifecycleEvent.FAIL,WorkflowCommandState.FAILED)
                .transition(WorkflowCommandState.READY,LifecycleEvent.FAIL,WorkflowCommandState.FAILED)
                .transition(WorkflowCommandState.STOPPING,LifecycleEvent.ABORT,WorkflowCommandState.CANCELLED);
        for(var state:new WorkflowCommandState[]{WorkflowCommandState.PREPARING,WorkflowCommandState.READY,WorkflowCommandState.RUNNING})
            builder.transition(state,LifecycleEvent.CANCEL,WorkflowCommandState.STOPPING);
        return builder.build();
    }
}
