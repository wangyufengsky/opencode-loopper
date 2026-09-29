package io.opencode.loopper.service.workflow;

import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.persistence.WorkflowExecutionRows;
import io.opencode.loopper.service.*;
import io.opencode.loopper.workflow.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Proposal acceptance, dispatch hold and program completion share one short transaction. */
@Service
@Transactional(readOnly=true)
public class WorkflowSourcePlanStore {
    private final WorkflowNodeActions actions;
    private final WorkflowNodeRuns nodes;
    private final WorkflowEncoding encoding;
    private final WorkflowCommands commands;
    private final WorkflowPlans plans;
    private final WorkflowPlanCandidates candidates;
    public WorkflowSourcePlanStore(WorkflowNodeActions actions,WorkflowNodeRuns nodes,WorkflowEncoding encoding,WorkflowCommands commands,WorkflowPlans plans,WorkflowPlanCandidates candidates){this.actions=actions;this.nodes=nodes;this.encoding=encoding;this.commands=commands;this.plans=plans;this.candidates=candidates;}
    public record Context(WorkflowExecutionRows.Attempt attempt,WorkflowGraph.Node node,WorkflowDelivery.Inputs inputs,String project,WorkflowPlanCandidates.Context plan) { }
    @Transactional
    public WorkflowNodeActions.Receipt dispatch(String id,String key,WorkflowNodeActions.Start request,WorkflowDispatch.Permit permit) {
        String digest=encoding.digest("NODE_SOURCE_PLAN_START",id+"/"+key,request);var replay=commands.replay(request.requestKey(),digest,WorkflowNodeActions.Receipt.class);if(replay.isPresent())return replay.get();
        var admission=actions.admit(id,key,request,permit);contract(admission.definition());
        var attempt=nodes.begin(admission.node(),admission.owner().headRevision(),admission.inputs(),WorkflowSourcePlan.adapter(admission.definition()),null);
        nodes.transition(attempt,WorkflowAttemptState.RUNNING,LifecycleEvent.START);
        return actions.acknowledge(request.requestKey(),digest,"SOURCE_PLAN_START",id,key,attempt.id());
    }
    public Context context(String id) {
        var attempt=nodes.attempt(id);if(WorkflowAttemptState.valueOf(attempt.state()).terminal())return null;
        if(!attempt.state().equals("RUNNING")||!java.util.Set.of(WorkflowSourcePlan.ADAPTER,WorkflowSourcePlan.TEST_ADAPTER,WorkflowSourcePlan.DOCUMENT_ADAPTER,WorkflowHistoryReport.PLAN_ADAPTER,WorkflowSnapshotReport.PLAN_ADAPTER).contains(attempt.adapterKey()))throw WorkflowCommands.conflict();
        nodes.active(attempt);var node=nodes.requireNode(attempt.nodeRunId());var definition=nodes.definition(node);contract(definition);
        if(!attempt.adapterKey().equals(WorkflowSourcePlan.adapter(definition)))throw WorkflowCommands.conflict();
        return new Context(attempt,definition,nodes.inputs(attempt),plans.require(node.requirementId()).projectId(),candidates.context(attempt));
    }
    @Transactional
    public void finish(Context context,WorkflowDelivery delivery,boolean success) {
        var current=nodes.attempt(context.attempt().id());if(WorkflowAttemptState.valueOf(current.state()).terminal())return;
        nodes.active(context.attempt());nodes.accept(current,delivery);
        if(success)candidates.capture(current,delivery);
        nodes.stopSourcePlan(current);nodes.finish(current,success?WorkflowAttemptState.SUCCEEDED:WorkflowAttemptState.FAILED);
        actions.settle(context.inputs().requirementId(),!success,current.id());
    }
    private void contract(WorkflowGraph.Node node){try{WorkflowSourcePlan.require(node);}catch(IllegalArgumentException invalid){throw new BadRequestException("WORKFLOW_SOURCE_PLAN_INVALID",invalid.getMessage());}}
}
