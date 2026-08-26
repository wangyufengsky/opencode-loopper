package io.opencode.loopper.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.domain.LifecycleMachineType;
import io.opencode.loopper.domain.TaskPackageRunState;
import io.opencode.loopper.domain.TaskState;
import io.opencode.loopper.domain.PackagePlanRevisionState;
import org.junit.jupiter.api.Test;

class RollingPackageLifecycleTest {
    private final LifecycleRegistry lifecycle = new LifecycleRegistry();

    @Test
    void packageRunUsesItsOwnClosedLoopStateAxis() {
        String state = TaskPackageRunState.PLANNED.name();
        state = next(state, TaskPackageRunState.DESIGNING, LifecycleEvent.BEGIN_PACKAGE_DESIGN);
        state = next(state, TaskPackageRunState.DESIGN_REVIEW, LifecycleEvent.REQUEST_PACKAGE_REVIEW);
        state = next(state, TaskPackageRunState.EXECUTION_READY, LifecycleEvent.APPROVE_PACKAGE_DESIGN);
        state = next(state, TaskPackageRunState.QUEUED, LifecycleEvent.REQUEST_PACKAGE_EXECUTION);
        state = next(state, TaskPackageRunState.RUNNING, LifecycleEvent.START);
        state = next(state, TaskPackageRunState.VERIFYING, LifecycleEvent.BEGIN_VERIFICATION);
        state = next(state, TaskPackageRunState.CHECKPOINTING, LifecycleEvent.BEGIN_PACKAGE_CHECKPOINT);
        state = next(state, TaskPackageRunState.FACT_FROZEN, LifecycleEvent.FREEZE_PACKAGE_FACT);

        assertThat(state).isEqualTo(TaskPackageRunState.FACT_FROZEN.name());
        assertThatThrownBy(() -> lifecycle.resolve(LifecycleMachineType.TASK_PACKAGE_RUN, "run-1",
                TaskPackageRunState.FACT_FROZEN.name(), TaskPackageRunState.DESIGNING.name(), null))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void taskSeparatesPackageDesignFromExecutionAndFinalReview() {
        assertThat(lifecycle.resolve(LifecycleMachineType.TASK, "task-1", TaskState.VERIFYING.name(),
                TaskState.PACKAGE_DESIGNING.name(), LifecycleEvent.BEGIN_PACKAGE_CHECKPOINT).event())
                .isEqualTo(LifecycleEvent.BEGIN_PACKAGE_CHECKPOINT);
        assertThat(lifecycle.resolve(LifecycleMachineType.TASK, "task-1", TaskState.PACKAGE_DESIGNING.name(),
                TaskState.WAITING_INPUT.name(), LifecycleEvent.REQUIRE_INPUT).event())
                .isEqualTo(LifecycleEvent.REQUIRE_INPUT);
        assertThat(lifecycle.resolve(LifecycleMachineType.TASK, "task-1", TaskState.PACKAGE_DESIGNING.name(),
                TaskState.JUDGING.name(), LifecycleEvent.BEGIN_FINAL_REVIEW).event())
                .isEqualTo(LifecycleEvent.BEGIN_FINAL_REVIEW);
    }

    @Test
    void aiPlanSuggestionHasAnIndependentAuditedLifecycle() {
        assertThat(lifecycle.resolve(LifecycleMachineType.PACKAGE_PLAN_REVISION, "plan-2",
                PackagePlanRevisionState.GENERATING.name(), PackagePlanRevisionState.PROPOSED.name(),
                LifecycleEvent.COMPLETE_PACKAGE_REPLAN).event()).isEqualTo(LifecycleEvent.COMPLETE_PACKAGE_REPLAN);
        assertThat(lifecycle.resolve(LifecycleMachineType.PACKAGE_PLAN_REVISION, "plan-2",
                PackagePlanRevisionState.PROPOSED.name(), PackagePlanRevisionState.ACTIVE.name(),
                LifecycleEvent.APPROVE_PACKAGE_REPLAN).event()).isEqualTo(LifecycleEvent.APPROVE_PACKAGE_REPLAN);
        assertThatThrownBy(() -> lifecycle.resolve(LifecycleMachineType.PACKAGE_PLAN_REVISION, "plan-2",
                PackagePlanRevisionState.FAILED.name(), PackagePlanRevisionState.ACTIVE.name(), null))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    private String next(String from, TaskPackageRunState expected, LifecycleEvent event) {
        var transition = lifecycle.resolve(LifecycleMachineType.TASK_PACKAGE_RUN, "run-1",
                from, expected.name(), event);
        assertThat(transition.event()).isEqualTo(event);
        return transition.toState();
    }
}
