package io.opencode.loopper.service.workflow;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.persistence.WorkflowControlMapper.Control;
import io.opencode.loopper.runtime.OpenCodeClient;
import io.opencode.loopper.service.*;
import io.opencode.loopper.workflow.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static io.opencode.loopper.service.workflow.WorkflowCommands.conflict;

/** Explicit run authorization and short-transaction scheduler snapshots. */
@Service
@Transactional(readOnly=true)
public class WorkflowControls {
    private final WorkflowPlans plans;
    private final WorkflowNodeRuns nodes;
    private final WorkflowNodeInputs inputs;
    private final WorkflowEncoding encoding;
    private final WorkflowCommands commands;
    private final WorkflowControlStore store;
    private final WorkflowSettlement settlement;
    private final WorkflowExecutionGuards guards;
    public WorkflowControls(WorkflowPlans plans,WorkflowNodeRuns nodes,WorkflowNodeInputs inputs,WorkflowEncoding encoding,WorkflowCommands commands,
            WorkflowControlStore store,WorkflowSettlement settlement,WorkflowExecutionGuards guards) {
        this.plans=plans;this.nodes=nodes;this.inputs=inputs;this.encoding=encoding;this.commands=commands;this.store=store;this.settlement=settlement;this.guards=guards;
    }
    public record Start(String requestKey,long expectedVersion,long expectedControlVersion,WorkflowDispatch.Mode mode,String targetKey,
                        Map<String,WorkflowDelivery.Value> inputs,OpenCodeClient.OpenCodeModel model,List<String> checkpointAttempts) { }
    public record Pause(String requestKey,long expectedControlVersion) { }
    public record View(String id,int revision,long version,boolean configured,long controlVersion,WorkflowDispatch.Mode mode,String targetKey,
                       WorkflowControlState state,String reasonCode,OpenCodeClient.OpenCodeModel model,List<WorkflowControlMapper.Checkpoint> checkpoints) { }
    public record Prepared(String id,String nodeKey,WorkflowGraph.NodeKind kind,String moduleId,long version,WorkflowDispatch.Permit permit,OpenCodeClient.OpenCodeModel model) { }
    public View get(String id) {
        var owner=plans.require(id);var row=store.find(id);
        if(row.isEmpty()) return new View(id,owner.headRevision(),owner.version(),false,-1,null,null,WorkflowControlState.PAUSED,"WORKFLOW_NOT_STARTED",null,List.of());
        var value=row.get();return new View(id,value.planRevision(),owner.version(),true,value.version(),WorkflowDispatch.Mode.valueOf(value.mode()),
                value.targetKey(),WorkflowControlState.valueOf(value.state()),value.reasonCode(),model(value),store.pending(id));
    }
    @Transactional
    public View start(String id,Start request) {
        String digest=encoding.digest("CONTROL_START",id,request);
        var replay=commands.replay(request.requestKey(),digest,View.class);if(replay.isPresent())return replay.get();
        var owner=plans.require(id);var old=store.find(id);
        if(owner.version()!=request.expectedVersion() || old.map(Control::version).orElse(-1L)!=request.expectedControlVersion()
                || !Set.of("PENDING_START","RUNNING","PAUSED","STALLED").contains(owner.state())) throw conflict();
        store.requireReviewed(id);
        var graph=settlement.graph(owner);final Set<String> scope;
        try { scope=WorkflowDispatch.scope(graph,request.mode(),request.targetKey()); }
        catch(IllegalArgumentException invalid) { throw new BadRequestException("WORKFLOW_SCOPE_INVALID",invalid.getMessage()); }
        if(nodes.summaries(id,owner.headRevision()).isEmpty())nodes.initialize(owner,graph);
        var progress=settlement.progress(owner);
        if(guards.recoveryReason(id)!=null) throw new ConflictException(guards.recoveryReason(id),"仍有节点等待确认停止或恢复，请先处理原尝试");
        if(graph.nodes().stream().anyMatch(node->node.kind()==WorkflowGraph.NodeKind.HUMAN && progress.get(node.id()).state()==WorkflowNodeState.ACTIVE))
            throw new ConflictException("WORKFLOW_HUMAN_INPUT_REQUIRED","请先完成正在等待的人工节点，再继续调度");
        var model=request.model()!=null?request.model():old.map(this::model).orElse(null);
        if(graph.nodes().stream().anyMatch(node->scope.contains(node.id()) && node.kind()==WorkflowGraph.NodeKind.WORK
                && Set.of(WorkflowNodeState.PENDING,WorkflowNodeState.FAILED).contains(progress.get(node.id()).state()))) WorkflowModelAdmission.requireModel(model);
        else if(model!=null)WorkflowModelAdmission.requireModel(model);
        String manualNode=null;int manualOrdinal=0;
        if(request.mode()==WorkflowDispatch.Mode.SINGLE && progress.get(request.targetKey()).state()==WorkflowNodeState.FAILED) {
            var value=progress.get(request.targetKey());manualNode=value.nodeRunId();manualOrdinal=value.attempts()+1;
        }
        if(request.mode()==WorkflowDispatch.Mode.SINGLE && progress.get(request.targetKey()).state()!=WorkflowNodeState.FAILED
                && WorkflowDispatch.next(graph,new WorkflowDispatch.Scope(request.mode(),request.targetKey(),null,0),progress).reason()!=WorkflowDispatch.Reason.READY
                && !continuingSingle(id,owner.headRevision(),request.targetKey(),progress.get(request.targetKey()).state(),old.orElse(null)))
            throw new ConflictException("WORKFLOW_NODE_NOT_READY","该节点已经开始或前置条件尚未满足，请选择就绪节点或运行至节点");
        store.acknowledge(id,request.checkpointAttempts());
        inputs.freezePublic(owner,graph,request.inputs());
        String now=Instant.now().toString();
        store.configure(new Control(id,owner.headRevision(),request.mode().name(),request.targetKey(),model==null?null:encoding.encode(model),manualNode,manualOrdinal,
                "ACTIVE",null,old.map(Control::version).orElse(0L),old.map(Control::createdAt).orElse(now),now));
        settlement.activate(owner);settlement.settle(id,false,null);
        return commands.record(request.requestKey(),digest,"REQUIREMENT","CONTROL_START",id,get(id));
    }
    @Transactional
    public View pause(String id,Pause request) {
        String digest=encoding.digest("CONTROL_PAUSE",id,request);
        var replay=commands.replay(request.requestKey(),digest,View.class);if(replay.isPresent())return replay.get();
        var row=store.find(id).orElseThrow(WorkflowCommands::conflict);
        if(row.version()!=request.expectedControlVersion() || row.state().equals("DONE"))throw conflict();
        settlement.hold(id,"WORKFLOW_USER_PAUSED");
        return commands.record(request.requestKey(),digest,"REQUIREMENT","CONTROL_PAUSE",id,get(id));
    }
    @Transactional
    public Prepared prepare(String id) {
        var saved=store.find(id);if(saved.isEmpty() || !saved.get().state().equals("ACTIVE"))return null;
        var owner=plans.require(id);var row=saved.get();
        if(!Set.of("PENDING_START","RUNNING","PAUSED","STALLED").contains(owner.state()))return null;
        if(row.planRevision()!=owner.headRevision()) { fail(id,new WorkflowDispatch.Permit(row.version(),row.planRevision()),"WORKFLOW_PLAN_CHANGED");return null; }
        if(guards.recoveryReason(id)!=null) { fail(id,new WorkflowDispatch.Permit(row.version(),row.planRevision()),guards.recoveryReason(id));return null; }
        var graph=settlement.graph(owner);var decision=WorkflowDispatch.next(graph,WorkflowControlStore.scope(row),settlement.progress(owner));
        if(decision.nodeKey()==null) { if(decision.reason()!=WorkflowDispatch.Reason.RUNNING)settlement.settle(id,false,null);return null; }
        var node=graph.nodes().stream().filter(value->value.id().equals(decision.nodeKey())).findFirst().orElseThrow(WorkflowCommands::conflict);
        return new Prepared(id,node.id(),node.kind(),node.moduleId(),owner.version(),new WorkflowDispatch.Permit(row.version(),row.planRevision()),model(row));
    }
    @Transactional
    public void fail(String id,WorkflowDispatch.Permit permit,String code) {
        var row=store.find(id);if(row.isEmpty() || !row.get().state().equals("ACTIVE") || row.get().version()!=permit.controlVersion())return;
        store.state(id,WorkflowControlState.STALLED,WorkflowFailures.safe(code));settlement.pauseRequirement(id,true);
    }
    private OpenCodeClient.OpenCodeModel model(Control row) { return row.modelJson()==null?null:encoding.decode(row.modelJson(),OpenCodeClient.OpenCodeModel.class); }
    private boolean continuingSingle(String id,int revision,String target,WorkflowNodeState state,Control old) {
        if(old==null || !old.mode().equals("SINGLE") || !Objects.equals(old.targetKey(),target) || old.planRevision()!=revision)return false;
        return state==WorkflowNodeState.ACTIVE || state==WorkflowNodeState.SUCCEEDED
                && store.pending(id).stream().anyMatch(checkpoint->checkpoint.nodeKey().equals(target));
    }
}
