package io.opencode.loopper.lifecycle;

import io.opencode.loopper.domain.*;
import io.opencode.loopper.workflow.WorkflowWorkspaceState;
import static io.opencode.loopper.workflow.WorkflowWorkspaceState.*;

final class WorkflowWorkspaceTopology {
    private WorkflowWorkspaceTopology() { }
    static FiniteStateMachine<WorkflowWorkspaceState,LifecycleEvent> machine() {
        return FiniteStateMachine.builder(LifecycleMachineType.WORKFLOW_WORKSPACE,WorkflowWorkspaceState.class,LifecycleEvent.class)
                .transition(PREPARING,LifecycleEvent.PREPARATION_SUCCEEDED,READY)
                .transition(READY,LifecycleEvent.CAPTURE_WORKSPACE,CAPTURING)
                .transition(PREPARING,LifecycleEvent.CAPTURE_WORKSPACE,CAPTURING)
                .transition(CAPTURING,LifecycleEvent.COMPLETE,FROZEN)
                .transition(FROZEN,LifecycleEvent.RESTORE,RESTORING)
                .transition(PREPARING,LifecycleEvent.CANCEL,RESTORING)
                .transition(RESTORING,LifecycleEvent.COMPLETE,RESTORED)
                .transition(RESTORED,LifecycleEvent.RELEASE,RELEASED).build();
    }
}
