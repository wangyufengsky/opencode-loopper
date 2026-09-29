package io.opencode.loopper.service.workflow;

import io.opencode.loopper.domain.*;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.workflow.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static io.opencode.loopper.service.workflow.WorkflowCommands.conflict;

/** Reconciles accepted terminal work with dispatch controls; never starts external work. */
@Service
@Transactional(propagation=Propagation.MANDATORY)
public class WorkflowSettlement {
    private final WorkflowPlans plans;
    private final WorkflowPlanMapper mapper;
    private final WorkflowNodeRuns nodes;
    private final WorkflowEncoding encoding;
    private final WorkflowControlStore controls;
    private final LifecycleTransitionService lifecycle;
    private final WorkflowPlanCandidateMapper candidates;
    public WorkflowSettlement(WorkflowPlans plans,WorkflowPlanMapper mapper,WorkflowNodeRuns nodes,WorkflowEncoding encoding,WorkflowControlStore controls,LifecycleTransitionService lifecycle,WorkflowPlanCandidateMapper candidates) {
        this.plans=plans;this.mapper=mapper;this.nodes=nodes;this.encoding=encoding;this.controls=controls;this.lifecycle=lifecycle;this.candidates=candidates;
    }
    public WorkflowRows.Requirement activate(WorkflowRows.Requirement row) {
        if (!row.state().equals("RUNNING")) transition(row,WorkflowState.RUNNING,row.state().equals("PENDING_START")?LifecycleEvent.START:LifecycleEvent.RESUME);
        else if(mapper.touch(row.id(),row.version(),Instant.now().toString())!=1) throw conflict();
        return plans.require(row.id());
    }
    public void humanWaiting(String id) {
        if(ending(id))return;
        var control=controls.find(id);
        if(control.isPresent()) controls.state(id,WorkflowControlState.WAITING,"WORKFLOW_HUMAN_INPUT");
        pauseRequirement(id,false);
    }
    public void hold(String id,String reason) {
        if(ending(id))return;
        if(controls.find(id).isEmpty()) return;
        controls.state(id,WorkflowControlState.PAUSED,reason);pauseRequirement(id,false);
    }
    public void block(String id,String reason) {
        if(ending(id))return;
        if(controls.find(id).isPresent())controls.state(id,WorkflowControlState.STALLED,reason);
        pauseRequirement(id,true);
    }
    public void settle(String id,boolean failed,String completedAttempt) {
        var owner=plans.require(id);if (owner.state().equals("STOPPING") || WorkflowState.valueOf(owner.state()).terminal()) return;
        var control=controls.find(id);
        if(completedAttempt!=null && control.isPresent()) {
            var attempt=nodes.attempt(completedAttempt);var node=nodes.requireNode(attempt.nodeRunId());
            controls.checkpoint(id,node,attempt,nodes.definition(node));
        }
        if(candidates.pending(id)) {
            if(control.isPresent())controls.state(id,WorkflowControlState.PAUSED,"WORKFLOW_PLAN_REVIEW_REQUIRED");
            pauseRequirement(id,false);return;
        }
        if(control.isEmpty()) { legacy(owner,failed);return; }
        var graph=graph(owner);settleSkipped(owner,graph);
        var progress=progress(owner);var current=controls.find(id).orElseThrow(WorkflowCommands::conflict);
        boolean waitingHuman=nodes.summaries(id,owner.headRevision()).stream().anyMatch(row->row.state().equals("ACTIVE")
                && graph.nodes().stream().anyMatch(node->node.id().equals(row.nodeKey()) && node.kind()==WorkflowGraph.NodeKind.HUMAN));
        if(current.state().equals("WAITING") && "WORKFLOW_HUMAN_INPUT".equals(current.reasonCode()) && !waitingHuman && controls.pending(id).isEmpty()) {
            controls.state(id,WorkflowControlState.ACTIVE,null);current=controls.find(id).orElseThrow(WorkflowCommands::conflict);
        }
        boolean completed=progress.values().stream().allMatch(row->Set.of(WorkflowNodeState.SUCCEEDED,WorkflowNodeState.SKIPPED).contains(row.state()));
        if(completed && controls.pending(id).isEmpty()) {
            controls.state(id,WorkflowControlState.DONE,null);complete(owner);return;
        }
        // A reviewed edit may remove the former UNTIL/SINGLE target while an admitted node is finishing.
        // Preserve that node's result without treating the obsolete scope as permission for a broader run.
        String targetKey=current.targetKey();
        if(targetKey!=null && graph.nodes().stream().noneMatch(node->node.id().equals(targetKey))) {
            controls.state(id,WorkflowControlState.PAUSED,"WORKFLOW_PLAN_CHANGED");pauseRequirement(id,false);return;
        }
        // A suspended session is still live. A failed terminal attempt, by contrast, can be retried after proof.
        if(failed && completedAttempt==null) controls.state(id,WorkflowControlState.STALLED,"WORKFLOW_MODEL_RECOVERY_REQUIRED");
        else if(failed && !current.state().equals("ACTIVE")) {
            var reason=WorkflowDispatch.next(graph,WorkflowControlStore.scope(current),progress).reason();
            if(reason==WorkflowDispatch.Reason.RETRY_EXHAUSTED || reason==WorkflowDispatch.Reason.CANCELLED)
                controls.state(id,WorkflowControlState.STALLED,reason==WorkflowDispatch.Reason.CANCELLED?"WORKFLOW_NODE_CANCELLED":"WORKFLOW_RETRY_EXHAUSTED");
        }
        else if(current.state().equals("ACTIVE")) {
            var decision=WorkflowDispatch.next(graph,WorkflowControlStore.scope(current),progress);
            switch(decision.reason()) {
                case SCOPE_COMPLETE -> controls.state(id,WorkflowControlState.PAUSED,"WORKFLOW_SCOPE_COMPLETE");
                case RETRY_EXHAUSTED -> controls.state(id,WorkflowControlState.STALLED,"WORKFLOW_RETRY_EXHAUSTED");
                case CANCELLED -> controls.state(id,WorkflowControlState.STALLED,"WORKFLOW_NODE_CANCELLED");
                case DEPENDENCY_BLOCKED -> controls.state(id,WorkflowControlState.STALLED,"WORKFLOW_DEPENDENCY_BLOCKED");
                default -> { }
            }
        }
        current=controls.find(id).orElseThrow(WorkflowCommands::conflict);
        if(current.state().equals("ACTIVE")) { if(!plans.require(id).state().equals("RUNNING")) activate(plans.require(id)); else touch(id); }
        else pauseRequirement(id,current.state().equals("STALLED"));
    }
    public void pauseRequirement(String id,boolean stalled) {
        if(ending(id))return;
        var row=plans.require(id);var desired=stalled?WorkflowState.STALLED:WorkflowState.PAUSED;
        if(row.state().equals(desired.name())) { touch(id);return; }
        if(!row.state().equals("RUNNING")) row=activate(row);
        transition(row,desired,stalled?LifecycleEvent.REQUIRE_INPUT:LifecycleEvent.PAUSE);
    }
    public Map<String,WorkflowDispatch.Progress> progress(WorkflowRows.Requirement owner) {
        var result=new LinkedHashMap<String,WorkflowDispatch.Progress>();
        nodes.summaries(owner.id(),owner.headRevision()).forEach(row->result.put(row.nodeKey(),new WorkflowDispatch.Progress(row.id(),WorkflowNodeState.valueOf(row.state()),row.attemptCount(),row.outcome())));
        return result;
    }
    public WorkflowGraph graph(WorkflowRows.Requirement row) {
        var revision=plans.revision(row.id(),row.headRevision());return encoding.read(revision.definitionJson(),revision.sha256());
    }
    private void legacy(WorkflowRows.Requirement owner,boolean failed) {
        if(failed) { pauseRequirement(owner.id(),true);return; }
        settleSkipped(owner,graph(owner));
        if(nodes.summaries(owner.id(),owner.headRevision()).stream().allMatch(row->Set.of("SUCCEEDED","SKIPPED").contains(row.state()))) complete(owner);
        else if(owner.state().equals("RUNNING")) transition(owner,WorkflowState.PAUSED,LifecycleEvent.PAUSE);else touch(owner.id());
    }
    private void settleSkipped(WorkflowRows.Requirement owner,WorkflowGraph graph) {
        for(int pass=0;pass<graph.nodes().size();pass++) {
            var current=new LinkedHashMap<String,WorkflowReadiness.Progress>();progress(owner).forEach((key,value)->current.put(key,new WorkflowReadiness.Progress(value.state(),value.outcome())));
            boolean changed=false;
            for(var node:graph.nodes()) if(WorkflowReadiness.decide(graph,node.id(),current)==WorkflowReadiness.Decision.SKIPPED) {
                nodes.skip(nodes.node(owner.id(),owner.headRevision(),node.id()),owner.headRevision());changed=true;
            }
            if(!changed)return;
        }
    }
    private void complete(WorkflowRows.Requirement row) {
        row=plans.require(row.id());if(!row.state().equals("RUNNING"))row=activate(row);
        transition(row,WorkflowState.COMPLETED,LifecycleEvent.COMPLETE);
    }
    private void touch(String id) { var row=plans.require(id);if(mapper.touch(id,row.version(),Instant.now().toString())!=1)throw conflict(); }
    private boolean ending(String id) { var state=WorkflowState.valueOf(plans.require(id).state());return state==WorkflowState.STOPPING || state.terminal(); }
    private void transition(WorkflowRows.Requirement row,WorkflowState state,LifecycleEvent event) {
        var subject=new LifecycleTransitionService.Subject(LifecycleMachineType.WORKFLOW_REQUIREMENT,row.id(),LifecycleScopeType.PROJECT,row.projectId());
        lifecycle.transition(subject,row.state(),state.name(),event,null,Map.of(),
                ()->mapper.transition(row.id(),row.version(),row.state(),state.name(),Instant.now().toString()),WorkflowCommands::conflict);
    }
}
