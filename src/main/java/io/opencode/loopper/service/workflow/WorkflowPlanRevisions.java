package io.opencode.loopper.service.workflow;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.service.*;
import io.opencode.loopper.workflow.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Applies explicitly reviewed changes while retaining exact execution identities and immutable inputs. */
@Service
public class WorkflowPlanRevisions {
    private final WorkflowPlans plans;
    private final WorkflowPlanMapper mapper;
    private final WorkflowNodeRuns nodes;
    private final WorkflowNodeInputs inputs;
    private final WorkflowEncoding encoding;
    private final WorkflowCommands commands;
    private final WorkflowControlStore controls;
    private final WorkflowSettlement settlement;
    public WorkflowPlanRevisions(WorkflowPlans plans,WorkflowPlanMapper mapper,WorkflowNodeRuns nodes,WorkflowNodeInputs inputs,WorkflowEncoding encoding,
            WorkflowCommands commands,WorkflowControlStore controls,WorkflowSettlement settlement) {
        this.plans=plans;this.mapper=mapper;this.nodes=nodes;this.inputs=inputs;this.encoding=encoding;this.commands=commands;this.controls=controls;this.settlement=settlement;
    }
    @Transactional
    public WorkflowCommands.Receipt revise(String id,WorkflowRequests.RevisePlan request) {
        String digest=encoding.digest("ACTIVE_PLAN_REVISE",id,request);
        var replay=commands.replay(request.requestKey(),digest);if(replay.isPresent())return replay.get();
        var receipt=apply(id,request.expectedVersion(),request.expectedRevision(),request.graph(),"USER");
        return commands.record(request.requestKey(),digest,"REQUIREMENT","ACTIVE_REVISE",receipt);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public WorkflowCommands.Receipt apply(String id,long version,int revision,WorkflowGraph graph,String source) {
        var previous=plans.require(id);
        if(previous.version()!=version || previous.headRevision()!=revision || !Set.of("RUNNING","PAUSED","STALLED").contains(previous.state()))throw WorkflowCommands.conflict();
        var definition=encoding.definition(graph);var issues=encoding.diagnostics(graph);
        if(!issues.isEmpty())throw new BadRequestException("WORKFLOW_NOT_READY",issues.getFirst().message());
        var stored=plans.revision(id,revision);var before=encoding.read(stored.definitionJson(),stored.sha256());
        var states=new HashMap<String,WorkflowNodeState>();nodes.summaries(id,revision).forEach(row->states.put(row.nodeKey(),WorkflowNodeState.valueOf(row.state())));
        final WorkflowPlanChanges.Changes changes;
        try { changes=WorkflowPlanChanges.compare(before,graph,states); }
        catch(IllegalArgumentException invalid) { throw new ConflictException("WORKFLOW_EXECUTED_NODE_LOCKED",invalid.getMessage()); }
        if(controls.pending(id).stream().anyMatch(checkpoint->changes.removed().contains(checkpoint.nodeKey())))
            throw new ConflictException("WORKFLOW_CHECKPOINT_REQUIRED","待检查的节点不能移出计划，请先查看交付物并确认检查点。");
        String now=Instant.now().toString();int next=revision+1;
        if(mapper.reviseActive(id,version,next,now)!=1 || mapper.insertRevision(new WorkflowRows.Revision(id,next,definition.body(),definition.sha256(),now),source,revision)!=1)throw WorkflowCommands.conflict();
        var updated=plans.require(id);nodes.rebind(previous,updated,graph,changes);inputs.carry(previous,updated);
        if(controls.find(id).isPresent())controls.replan(id,next,graph);
        settlement.pauseRequirement(id,false);
        updated=plans.require(id);
        return new WorkflowCommands.Receipt(id,next,updated.version(),updated.layoutVersion(),updated.state());
    }
}
