package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opencode.loopper.domain.TaskPackageRunState;
import io.opencode.loopper.domain.TaskQueueState;
import io.opencode.loopper.domain.TaskState;
import org.junit.jupiter.api.Test;

class RollingPackageCommandPolicyTest {
    private final RollingPackageCommandPolicy policy = new RollingPackageCommandPolicy();

    @Test
    void executionReadyPackageRequiresAnEligibleParentTask() {
        var running = context(TaskState.RUNNING, TaskPackageRunState.EXECUTION_READY);
        var pending = context(TaskState.PENDING_START, TaskPackageRunState.EXECUTION_READY);

        assertThat(policy.capabilities(running).canStartPackage()).isFalse();
        assertThat(policy.capabilities(pending).canStartPackage()).isTrue();
        assertThatThrownBy(() -> policy.require(RollingPackageCommandPolicy.Command.START, running))
                .isInstanceOfSatisfying(ConflictException.class, failure ->
                        assertThat(failure.code()).isEqualTo("PACKAGE_COMMAND_NOT_AVAILABLE"));
    }

    @Test
    void queuedStartIsIdempotentOnlyWhenTaskRunAndQueueAgree() {
        var consistent = new RollingPackageCommandPolicy.Context(TaskState.QUEUED, null,
                TaskPackageRunState.QUEUED, null, TaskQueueState.ADMITTED,
                true, true, true, 1);
        var missingQueue = new RollingPackageCommandPolicy.Context(TaskState.QUEUED, null,
                TaskPackageRunState.QUEUED, null, null,
                true, true, true, 1);
        var mixedParent = new RollingPackageCommandPolicy.Context(TaskState.WAITING_INPUT, null,
                TaskPackageRunState.QUEUED, null, TaskQueueState.QUEUED,
                true, true, true, 1);

        assertThat(policy.startDisposition(consistent)).isEqualTo(RollingPackageCommandPolicy.StartDisposition.IDEMPOTENT);
        assertThat(policy.startDisposition(missingQueue)).isEqualTo(RollingPackageCommandPolicy.StartDisposition.REJECTED);
        assertThat(policy.startDisposition(mixedParent)).isEqualTo(RollingPackageCommandPolicy.StartDisposition.REJECTED);
    }

    @Test
    void correctionRequiresJudgeInputFrozenFactsAndNoActiveOwner() {
        var valid = new RollingPackageCommandPolicy.Context(TaskState.WAITING_INPUT, "JUDGE_CONFLICT",
                null, null, null, true, true, true, 1);
        var ordinaryInput = new RollingPackageCommandPolicy.Context(TaskState.WAITING_INPUT, "PACKAGE_EXECUTION_FAILED",
                null, null, null, true, true, true, 1);
        var activeDesigner = new RollingPackageCommandPolicy.Context(TaskState.WAITING_INPUT, "JUDGE_REVIEW_NOT_APPROVED",
                null, null, null, true, false, true, 1);

        assertThat(policy.capabilities(valid).canAddCorrectionPackage()).isTrue();
        assertThat(policy.capabilities(ordinaryInput).canAddCorrectionPackage()).isFalse();
        assertThat(policy.capabilities(activeDesigner).canAddCorrectionPackage()).isFalse();
    }

    @Test
    void redesignAllowsInitialDesignFailureButRequiresCheckpointAfterExecution() {
        var initialDesignFailure = new RollingPackageCommandPolicy.Context(TaskState.WAITING_INPUT, null,
                TaskPackageRunState.WAITING_INPUT, "PACKAGE_DESIGN_SESSION_FAILED", null,
                true, true, false, 0);
        var executionFailure = new RollingPackageCommandPolicy.Context(TaskState.WAITING_INPUT, null,
                TaskPackageRunState.WAITING_INPUT, "PACKAGE_EXECUTION_FAILED", null,
                true, true, false, 1);
        var executionWithCheckpoint = new RollingPackageCommandPolicy.Context(TaskState.WAITING_INPUT, null,
                TaskPackageRunState.WAITING_INPUT, "PACKAGE_EXECUTION_FAILED", null,
                true, true, true, 1);

        assertThat(policy.capabilities(initialDesignFailure).canRedesignPackage()).isTrue();
        assertThat(policy.capabilities(executionFailure).canRedesignPackage()).isFalse();
        assertThat(policy.capabilities(executionWithCheckpoint).canRedesignPackage()).isTrue();
    }

    @Test
    void activePackageDesignCannotExposeReplanDuringTheDesignerDispatchGap() {
        var dispatching = context(TaskState.PACKAGE_DESIGNING, TaskPackageRunState.DESIGNING);
        var review = context(TaskState.PACKAGE_DESIGNING, TaskPackageRunState.DESIGN_REVIEW);

        assertThat(policy.capabilities(dispatching).canReplanRemaining()).isFalse();
        assertThat(policy.capabilities(review).canReplanRemaining()).isTrue();
    }

    @Test
    void awaitingStoppingAndTerminalTasksExposeNoPackageCommands() {
        for (TaskState state : new TaskState[]{TaskState.AWAITING_DECISION, TaskState.STOPPING,
                TaskState.COMPLETED, TaskState.SUPERSEDED, TaskState.SUCCEEDED,
                TaskState.FAILED, TaskState.CANCELLED}) {
            assertThat(policy.capabilities(context(state, TaskPackageRunState.DESIGN_REVIEW)).anyAvailable())
                    .as(state.name()).isFalse();
        }
    }

    private RollingPackageCommandPolicy.Context context(TaskState task, TaskPackageRunState run) {
        return new RollingPackageCommandPolicy.Context(task, null, run, null, null,
                true, true, true, 1);
    }
}
