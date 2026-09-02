package io.opencode.loopper.lifecycle;

import static io.opencode.loopper.domain.LifecycleEvent.*;
import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.domain.LifecycleMachineType;
import io.opencode.loopper.domain.TaskState;

/** Task topology, registered by the shared lifecycle registry and audited by its transition service. */
final class TaskLifecycleTopology {
    private TaskLifecycleTopology() { }
    static FiniteStateMachine<TaskState, LifecycleEvent> task() {
        var b = FiniteStateMachine.builder(LifecycleMachineType.TASK, TaskState.class, LifecycleEvent.class);
        b.transition(TaskState.PENDING_START, REQUEST_START, TaskState.QUEUED)
                .transition(TaskState.QUEUED, PREPARE, TaskState.PREPARING)
                .transition(TaskState.PREPARING, PREPARATION_SUCCEEDED, TaskState.READY)
                .transition(TaskState.READY, START, TaskState.RUNNING)
                .transition(TaskState.RUNNING, BEGIN_VERIFICATION, TaskState.VERIFYING)
                .transition(TaskState.RUNNING, SCHEDULE_RETRY, TaskState.RETRY_WAIT)
                .transition(TaskState.VERIFYING, SCHEDULE_RETRY, TaskState.RETRY_WAIT)
                .transition(TaskState.RETRY_WAIT, RETRY, TaskState.RUNNING)
                .transition(TaskState.VERIFYING, ADVANCE_STAGE, TaskState.RUNNING)
                .transition(TaskState.VERIFYING, BEGIN_PACKAGE_CHECKPOINT, TaskState.PACKAGE_DESIGNING)
                .transition(TaskState.VERIFYING, BEGIN_FINAL_REVIEW, TaskState.JUDGING)
                .transition(TaskState.PACKAGE_DESIGNING, REQUIRE_INPUT, TaskState.WAITING_INPUT)
                .transition(TaskState.PACKAGE_DESIGNING, BEGIN_FINAL_REVIEW, TaskState.JUDGING)
                .transition(TaskState.WAITING_INPUT, BEGIN_PACKAGE_DESIGN, TaskState.PACKAGE_DESIGNING)
                .transition(TaskState.WAITING_INPUT, REQUEST_START, TaskState.QUEUED)
                .transition(TaskState.JUDGING, BEGIN_PACKAGE_DESIGN, TaskState.PACKAGE_DESIGNING)
                .transition(TaskState.JUDGING, APPROVE, TaskState.AWAITING_DECISION)
                .transition(TaskState.WAITING_INPUT, APPROVE, TaskState.AWAITING_DECISION)
                .transition(TaskState.JUDGING, REQUIRE_INPUT, TaskState.WAITING_INPUT)
                .transition(TaskState.WAITING_INPUT, RETRY_FINAL_REVIEW, TaskState.JUDGING)
                .transition(TaskState.WAITING_INPUT, RECOVER, TaskState.RUNNING)
                .transition(TaskState.WAITING_INPUT, RETRY_PREPARATION, TaskState.PREPARING)
                .transition(TaskState.RUNNING, PAUSE, TaskState.PAUSED)
                .transition(TaskState.VERIFYING, PAUSE, TaskState.PAUSED)
                .transition(TaskState.RETRY_WAIT, PAUSE, TaskState.PAUSED)
                .transition(TaskState.PAUSED, RESUME, TaskState.RUNNING)
                .transition(TaskState.PAUSED, RESUME_RETRY, TaskState.RETRY_WAIT)
                .transition(TaskState.RUNNING, REQUIRE_INPUT, TaskState.WAITING_INPUT)
                .transition(TaskState.VERIFYING, REQUIRE_INPUT, TaskState.WAITING_INPUT)
                .transition(TaskState.QUEUED, REQUIRE_INPUT, TaskState.WAITING_INPUT)
                .transition(TaskState.PREPARING, REQUIRE_INPUT, TaskState.WAITING_INPUT)
                .transition(TaskState.READY, REQUIRE_INPUT, TaskState.WAITING_INPUT)
                .transition(TaskState.RETRY_WAIT, REQUIRE_INPUT, TaskState.WAITING_INPUT)
                .transition(TaskState.PAUSED, REQUIRE_INPUT, TaskState.WAITING_INPUT)
                .transition(TaskState.RUNNING, RECOVER, TaskState.RUNNING)
                .transition(TaskState.VERIFYING, RECOVER, TaskState.RUNNING)
                .transition(TaskState.RETRY_WAIT, RECOVER, TaskState.RUNNING);
        b.transition(TaskState.AWAITING_DECISION, REQUEST_START, TaskState.QUEUED)
                .transition(TaskState.AWAITING_DECISION, ACCEPT_RESULT, TaskState.COMPLETED)
                .transition(TaskState.AWAITING_DECISION, COMPLETE, TaskState.COMPLETED)
                .transition(TaskState.AWAITING_DECISION, SUPERSEDE, TaskState.SUPERSEDED)
                .transition(TaskState.AWAITING_DECISION, CANCEL, TaskState.STOPPING);
        for (TaskState state : TaskState.values()) {
            if (!state.terminal() && state != TaskState.AWAITING_DECISION && state != TaskState.STOPPING) {
                b.transition(state, CANCEL, TaskState.STOPPING).transition(state, FAIL, TaskState.AWAITING_DECISION);
            }
        }
        b.transition(TaskState.STOPPING, COMPLETE, TaskState.CANCELLED);
        return b.build();
    }

}
