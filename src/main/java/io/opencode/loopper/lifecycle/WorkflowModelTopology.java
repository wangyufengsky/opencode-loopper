package io.opencode.loopper.lifecycle;

import io.opencode.loopper.domain.*;
import io.opencode.loopper.workflow.WorkflowModelState;

final class WorkflowModelTopology {
    private WorkflowModelTopology() { }
    static FiniteStateMachine<TaskQueueState, LifecycleEvent> writerQueue() {
        return writerQueue(LifecycleMachineType.WORKFLOW_WRITER_QUEUE);
    }
    static FiniteStateMachine<TaskQueueState, LifecycleEvent> writerQueue(LifecycleMachineType type) {
        return FiniteStateMachine.builder(type,TaskQueueState.class,LifecycleEvent.class)
                .transition(TaskQueueState.QUEUED,LifecycleEvent.ADMIT,TaskQueueState.ADMITTED)
                .transition(TaskQueueState.QUEUED,LifecycleEvent.CANCEL,TaskQueueState.CANCELLED)
                .transition(TaskQueueState.ADMITTED,LifecycleEvent.FINISH,TaskQueueState.FINISHED).build();
    }
    static FiniteStateMachine<WorkflowModelState, LifecycleEvent> model() {
        var builder = FiniteStateMachine.builder(LifecycleMachineType.WORKFLOW_MODEL, WorkflowModelState.class, LifecycleEvent.class)
                .transition(WorkflowModelState.PREPARING, LifecycleEvent.PREPARE, WorkflowModelState.CREATING)
                .transition(WorkflowModelState.CREATING, LifecycleEvent.PREPARATION_SUCCEEDED, WorkflowModelState.DISPATCHING)
                .transition(WorkflowModelState.DISPATCHING, LifecycleEvent.START, WorkflowModelState.RUNNING);
        for (var state : new WorkflowModelState[]{WorkflowModelState.PREPARING, WorkflowModelState.CREATING,
                WorkflowModelState.DISPATCHING, WorkflowModelState.RUNNING}) {
            builder.transition(state, LifecycleEvent.CANCEL, WorkflowModelState.STOPPING);
            builder.transition(state, LifecycleEvent.FAIL, WorkflowModelState.FAILED);
        }
        builder.transition(WorkflowModelState.RUNNING, LifecycleEvent.COMPLETE, WorkflowModelState.SUCCEEDED);
        builder.transition(WorkflowModelState.PREPARING, LifecycleEvent.REUSE_RESULT, WorkflowModelState.SUCCEEDED);
        builder.transition(WorkflowModelState.STOPPING, LifecycleEvent.ABORT, WorkflowModelState.CANCELLED);
        builder.transition(WorkflowModelState.STOPPING, LifecycleEvent.FAIL, WorkflowModelState.FAILED);
        return builder.build();
    }
}
