package io.opencode.loopper.lifecycle;

import io.opencode.loopper.domain.*;
import io.opencode.loopper.workflow.*;

final class WorkflowNodeTopology {
    private WorkflowNodeTopology() { }
    static FiniteStateMachine<WorkflowNodeState, LifecycleEvent> node() {
        return FiniteStateMachine.builder(LifecycleMachineType.WORKFLOW_NODE, WorkflowNodeState.class, LifecycleEvent.class)
                .transition(WorkflowNodeState.PENDING, LifecycleEvent.START, WorkflowNodeState.ACTIVE)
                .transition(WorkflowNodeState.FAILED, LifecycleEvent.RETRY, WorkflowNodeState.ACTIVE)
                .transition(WorkflowNodeState.PENDING, LifecycleEvent.SKIP, WorkflowNodeState.SKIPPED)
                .transition(WorkflowNodeState.PENDING, LifecycleEvent.CANCEL, WorkflowNodeState.CANCELLED)
                .transition(WorkflowNodeState.ACTIVE, LifecycleEvent.COMPLETE, WorkflowNodeState.SUCCEEDED)
                .transition(WorkflowNodeState.ACTIVE, LifecycleEvent.FAIL, WorkflowNodeState.FAILED)
                .transition(WorkflowNodeState.ACTIVE, LifecycleEvent.ABORT, WorkflowNodeState.CANCELLED).build();
    }
    static FiniteStateMachine<WorkflowAttemptState, LifecycleEvent> attempt() {
        var builder = FiniteStateMachine.builder(LifecycleMachineType.WORKFLOW_ATTEMPT, WorkflowAttemptState.class, LifecycleEvent.class)
                .transition(WorkflowAttemptState.PREPARING, LifecycleEvent.START, WorkflowAttemptState.RUNNING)
                .transition(WorkflowAttemptState.PREPARING, LifecycleEvent.REQUIRE_INPUT, WorkflowAttemptState.WAITING_INPUT)
                .transition(WorkflowAttemptState.RUNNING, LifecycleEvent.REQUIRE_INPUT, WorkflowAttemptState.WAITING_INPUT)
                .transition(WorkflowAttemptState.WAITING_INPUT, LifecycleEvent.RESUME, WorkflowAttemptState.RUNNING);
        for (var state : new WorkflowAttemptState[]{WorkflowAttemptState.PREPARING, WorkflowAttemptState.RUNNING, WorkflowAttemptState.WAITING_INPUT}) {
            builder.transition(state, LifecycleEvent.CANCEL, WorkflowAttemptState.STOPPING);
            builder.transition(state, LifecycleEvent.COMPLETE, WorkflowAttemptState.SUCCEEDED);
            builder.transition(state, LifecycleEvent.FAIL, WorkflowAttemptState.FAILED);
        }
        builder.transition(WorkflowAttemptState.STOPPING, LifecycleEvent.ABORT, WorkflowAttemptState.CANCELLED);
        builder.transition(WorkflowAttemptState.STOPPING, LifecycleEvent.FAIL, WorkflowAttemptState.FAILED);
        builder.transition(WorkflowAttemptState.STOPPING, LifecycleEvent.COMPLETE, WorkflowAttemptState.SUCCEEDED);
        return builder.build();
    }
}
