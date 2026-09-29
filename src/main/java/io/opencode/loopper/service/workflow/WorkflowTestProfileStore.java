package io.opencode.loopper.service.workflow;

import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.persistence.WorkflowExecutionRows;
import io.opencode.loopper.service.BadRequestException;
import io.opencode.loopper.workflow.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Configuration output, stop proof and program completion commit atomically. */
@Service
@Transactional(readOnly=true)
public class WorkflowTestProfileStore {
    private final WorkflowNodeActions actions;
    private final WorkflowNodeRuns nodes;
    private final WorkflowEncoding encoding;
    private final WorkflowCommands commands;
    private final WorkflowPlans plans;
    public WorkflowTestProfileStore(WorkflowNodeActions actions,WorkflowNodeRuns nodes,WorkflowEncoding encoding,WorkflowCommands commands,WorkflowPlans plans){this.actions=actions;this.nodes=nodes;this.encoding=encoding;this.commands=commands;this.plans=plans;}
    public record Context(WorkflowExecutionRows.Attempt attempt,WorkflowGraph.Node node,WorkflowDelivery.Inputs inputs,String project){ }
    @Transactional
    public WorkflowNodeActions.Receipt dispatch(String id,String key,WorkflowNodeActions.Start request,WorkflowDispatch.Permit permit) {
        String digest=encoding.digest("NODE_TEST_PROFILE_START",id+"/"+key,request);var replay=commands.replay(request.requestKey(),digest,WorkflowNodeActions.Receipt.class);if(replay.isPresent())return replay.get();
        var admission=actions.admit(id,key,request,permit);contract(admission.definition());
        var attempt=nodes.begin(admission.node(),admission.owner().headRevision(),admission.inputs(),WorkflowTestProfile.ADAPTER,null);
        nodes.transition(attempt,WorkflowAttemptState.RUNNING,LifecycleEvent.START);
        return actions.acknowledge(request.requestKey(),digest,"TEST_PROFILE_START",id,key,attempt.id());
    }
    public Context context(String id) {
        var attempt=nodes.attempt(id);if(WorkflowAttemptState.valueOf(attempt.state()).terminal())return null;
        if(!attempt.state().equals("RUNNING")||!attempt.adapterKey().equals(WorkflowTestProfile.ADAPTER))throw WorkflowCommands.conflict();
        nodes.active(attempt);var node=nodes.requireNode(attempt.nodeRunId());var definition=nodes.definition(node);contract(definition);
        return new Context(attempt,definition,nodes.inputs(attempt),plans.require(node.requirementId()).projectId());
    }
    @Transactional
    public void finish(Context context,WorkflowDelivery delivery,boolean success) {
        var current=nodes.attempt(context.attempt().id());if(WorkflowAttemptState.valueOf(current.state()).terminal())return;
        nodes.active(context.attempt());nodes.accept(current,delivery);nodes.stopTestProfile(current);
        nodes.finish(current,success?WorkflowAttemptState.SUCCEEDED:WorkflowAttemptState.FAILED);actions.settle(context.inputs().requirementId(),!success,current.id());
    }
    private void contract(WorkflowGraph.Node node){try{WorkflowTestProfile.require(node);}catch(IllegalArgumentException invalid){throw new BadRequestException("WORKFLOW_TEST_PROFILE_INVALID",invalid.getMessage());}}
}
