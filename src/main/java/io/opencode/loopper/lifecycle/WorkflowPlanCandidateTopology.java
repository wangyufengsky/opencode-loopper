package io.opencode.loopper.lifecycle;
import io.opencode.loopper.domain.*;
import io.opencode.loopper.workflow.WorkflowPlanCandidateState;
import static io.opencode.loopper.workflow.WorkflowPlanCandidateState.*;
final class WorkflowPlanCandidateTopology {
    private WorkflowPlanCandidateTopology() { }
    static FiniteStateMachine<WorkflowPlanCandidateState,LifecycleEvent> machine() {
        return FiniteStateMachine.builder(LifecycleMachineType.WORKFLOW_PLAN_CANDIDATE,WorkflowPlanCandidateState.class,LifecycleEvent.class)
                .transition(PENDING,LifecycleEvent.APPLY,APPLIED).transition(PENDING,LifecycleEvent.REJECT,REJECTED).build();
    }
}
