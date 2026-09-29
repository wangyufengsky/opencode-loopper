package io.opencode.loopper.service.workflow;

import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.workflow.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static io.opencode.loopper.service.workflow.WorkflowCommands.conflict;

/** Requests adapter-owned stops. Existing model/command monitors retain their exact recovery identities. */
@Service
public class WorkflowFinishDrain {
    private final WorkflowFinishMapper mapper;
    private final WorkflowPlanMapper plans;
    private final WorkflowNodeRuns nodes;
    private final WorkflowModelStore models;
    private final WorkflowCommandStore commands;
    public WorkflowFinishDrain(WorkflowFinishMapper mapper,WorkflowPlanMapper plans,WorkflowNodeRuns nodes,
            WorkflowModelStore models,WorkflowCommandStore commands) {
        this.mapper=mapper;this.plans=plans;this.nodes=nodes;this.models=models;this.commands=commands;
    }
    @Transactional
    public void stop(String id,String attemptId) {
        var intent=mapper.find(id).orElseThrow(WorkflowCommands::conflict);
        if(intent.finalizedAt()!=null)return;
        if(!plans.find(id).orElseThrow(WorkflowCommands::conflict).state().equals("STOPPING"))throw conflict();
        var attempt=nodes.attempt(attemptId);
        if(!nodes.requireNode(attempt.nodeRunId()).requirementId().equals(id))throw conflict();
        if(WorkflowAttemptState.valueOf(attempt.state()).terminal())return;
        switch(attempt.adapterKey()) {
            case "model.readonly.v1","model.write.v1" -> models.stop(attemptId);
            case WorkflowCommandVerification.ADAPTER -> commands.stop(attemptId,commands.require(attemptId).version());
            case "human.v1",WorkflowVerification.ADAPTER,WorkflowReviewContract.ADAPTER,WorkflowSourceSnapshot.ADAPTER,WorkflowDocument.ADAPTER,WorkflowDocument.ASSESSMENT_ADAPTER,WorkflowHistoryReport.ADAPTER,WorkflowSnapshotReport.ADAPTER,WorkflowSourcePlan.ADAPTER,WorkflowSourcePlan.TEST_ADAPTER,WorkflowSourcePlan.DOCUMENT_ADAPTER,WorkflowHistoryReport.PLAN_ADAPTER,WorkflowSnapshotReport.PLAN_ADAPTER,WorkflowTestSummary.ADAPTER,WorkflowTestProfile.ADAPTER -> {
                if(!attempt.state().equals("STOPPING"))attempt=nodes.transition(attempt,WorkflowAttemptState.STOPPING,LifecycleEvent.CANCEL);
                switch(attempt.adapterKey()) {
                    case "human.v1" -> nodes.stopHuman(attempt);
                    case WorkflowVerification.ADAPTER -> nodes.stopVerification(attempt);
                    case WorkflowSourceSnapshot.ADAPTER -> nodes.stopSource(attempt);
                    case WorkflowDocument.ADAPTER,WorkflowDocument.ASSESSMENT_ADAPTER,WorkflowHistoryReport.ADAPTER,WorkflowSnapshotReport.ADAPTER -> nodes.stopDocument(attempt);
                    case WorkflowSourcePlan.ADAPTER,WorkflowSourcePlan.TEST_ADAPTER,WorkflowSourcePlan.DOCUMENT_ADAPTER,WorkflowHistoryReport.PLAN_ADAPTER,WorkflowSnapshotReport.PLAN_ADAPTER -> nodes.stopSourcePlan(attempt);
                    case WorkflowTestSummary.ADAPTER -> nodes.stopTestSummary(attempt);
                    case WorkflowTestProfile.ADAPTER -> nodes.stopTestProfile(attempt);
                    default -> nodes.stopReviewGate(attempt);
                }
                nodes.finish(attempt,WorkflowAttemptState.CANCELLED);
            }
            default -> throw conflict(); // An unknown adapter must supply its own stop proof.
        }
    }
}
