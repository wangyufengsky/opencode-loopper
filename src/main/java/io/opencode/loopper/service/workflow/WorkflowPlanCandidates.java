package io.opencode.loopper.service.workflow;

import io.opencode.loopper.api.CursorPage;
import io.opencode.loopper.persistence.WorkflowExecutionRows;
import io.opencode.loopper.persistence.WorkflowPlanCandidateMapper.*;
import io.opencode.loopper.service.*;
import io.opencode.loopper.workflow.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Work can propose downstream intent; only a local user command may apply it. */
@Service
@Transactional(readOnly=true)
public class WorkflowPlanCandidates {
    private final WorkflowPlanCandidateStore store;
    private final WorkflowPlans plans;
    private final WorkflowNodeRuns nodes;
    private final WorkflowEncoding encoding;
    private final WorkflowCommands commands;
    private final WorkflowControlStore controls;
    private final WorkflowSettlement settlement;
    private final WorkflowPlanRevisions revisions;
    public WorkflowPlanCandidates(WorkflowPlanCandidateStore store,WorkflowPlans plans,WorkflowNodeRuns nodes,WorkflowEncoding encoding,WorkflowCommands commands,
            WorkflowControlStore controls,WorkflowSettlement settlement,WorkflowPlanRevisions revisions) {
        this.store=store;this.plans=plans;this.nodes=nodes;this.encoding=encoding;this.commands=commands;this.controls=controls;this.settlement=settlement;this.revisions=revisions;
    }
    public record Proposal(int version,int baseRevision,WorkflowGraph graph) { }
    public record Context(int baseRevision,String sourceNodeKey,Set<String> editableNodeKeys,WorkflowGraph graph,String instruction) { }
    public record Detail(String id,String attemptId,String nodeKey,String sourceTitle,int baseRevision,String state,long version,Integer appliedRevision,
                         String decisionReason,boolean stale,boolean sourceCompleted,WorkflowGraph graph,WorkflowGraph originalGraph,WorkflowPlanChanges.Changes changes,List<WorkflowGraphValidator.Diagnostic> diagnostics) { }
    public record Apply(String requestKey,long expectedVersion,int expectedRevision,long expectedCandidateVersion,WorkflowGraph graph) { }
    public record Reject(String requestKey,long expectedCandidateVersion,String reason) { }
    public Context context(WorkflowExecutionRows.Attempt attempt) {
        var node=nodes.requireNode(attempt.nodeRunId());var source=plans.revision(node.requirementId(),attempt.planRevision());var graph=encoding.read(source.definitionJson(),source.sha256());
        return new Context(attempt.planRevision(),node.nodeKey(),WorkflowPlanChanges.descendants(graph,node.nodeKey()),graph,
                "PLAN 交付 content 使用 {version:1,baseRevision,graph}；graph 是完整计划，只能改动本节点之后的区域。来源节点和其他区域原样保留。新增节点必须依赖本节点。用户确认之前草案不会执行。命令验证的 purpose=TEST 仅用于受支持的真实测试框架，例如 python3 -m unittest；普通项目检查脚本（如 python3 verify.py）应使用 CHECK。不能跳过测试或把失败改成成功；尚无法确定命令时保留空 argv 供用户配置。");
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void capture(WorkflowExecutionRows.Attempt attempt,WorkflowDelivery delivery) {
        var declared=nodes.definition(nodes.requireNode(attempt.nodeRunId()));
        for(var output:delivery.outputs().entrySet())if(output.getValue().kind()==WorkflowGraph.DataKind.PLAN) {
            if(declared.outputs().stream().filter(value->value.kind()==WorkflowGraph.DataKind.PLAN).count()!=1)throw invalid("每个规划节点只能声明一份候选计划。");
            var content=output.getValue().content();
            if(content==null || !content.isObject() || !content.propertyNames().equals(Set.of("version","baseRevision","graph"))
                    || !content.path("version").isIntegralNumber() || !content.path("baseRevision").isIntegralNumber())throw invalid("候选计划需要整数 version、baseRevision 和完整 graph。");
            final Proposal proposal;
            try{proposal=encoding.decode(encoding.encode(content),Proposal.class);}catch(RuntimeException failure){throw invalid("候选计划格式无效，请按节点工作信息修正。");}
            var base=context(attempt);if(proposal.version()!=1 || proposal.baseRevision()!=base.baseRevision())throw invalid("候选计划必须使用当前尝试冻结的基准版本。");
            var graph=encoding.definition(proposal.graph());encoding.requireConfiguredCommands(graph.graph());scope(base.graph(),graph.graph(),base.sourceNodeKey());
            var previous=store.byOutput(attempt.id(),output.getKey());
            if(previous.isPresent()){if(!previous.get().sha256().equals(graph.sha256()))throw WorkflowCommands.conflict();continue;}
            String id=nodes.requireNode(attempt.nodeRunId()).requirementId(),now=Instant.now().toString();
            store.create(new Candidate(UUID.randomUUID().toString(),id,attempt.id(),output.getKey(),base.baseRevision(),graph.body(),graph.sha256(),"PENDING",0,null,null,now,now));
            if(controls.find(id).isPresent())settlement.hold(id,"WORKFLOW_PLAN_REVIEW_REQUIRED");else settlement.pauseRequirement(id,false);
        }
    }
    public CursorPage<Summary> list(String id,String state,String cursor,Integer limit){return store.page(id,state,cursor,limit);}
    public Detail get(String id,String key) {
        var row=store.require(id,key);var owner=plans.require(id);var attempt=nodes.scopedAttempt(id,nodes.requireNode(nodes.attempt(row.attemptId()).nodeRunId()).nodeKey(),row.attemptId());
        var definition=nodes.definition(nodes.requireNode(attempt.nodeRunId()));var graph=encoding.read(row.graphJson(),row.sha256());
        var base=plans.revision(id,row.baseRevision());var before=encoding.read(base.definitionJson(),base.sha256());
        var changes=WorkflowPlanChanges.compare(before,graph,Map.of());
        return new Detail(row.id(),row.attemptId(),definition.id(),definition.title(),row.baseRevision(),row.state(),row.version(),row.appliedRevision(),row.decisionReason(),
                owner.headRevision()!=row.baseRevision(),attempt.state().equals("SUCCEEDED"),graph,before,changes,encoding.diagnostics(graph));
    }
    @Transactional
    public WorkflowCommands.Receipt apply(String id,String key,Apply request) {
        String digest=encoding.digest("PLAN_CANDIDATE_APPLY",id+"/"+key,request);var replay=commands.replay(request.requestKey(),digest);if(replay.isPresent())return replay.get();
        var row=store.require(id,key);var owner=plans.require(id);
        if(!row.state().equals("PENDING") || row.version()!=request.expectedCandidateVersion() || row.baseRevision()!=request.expectedRevision() || owner.headRevision()!=row.baseRevision())throw new ConflictException("WORKFLOW_CANDIDATE_STALE","候选计划所依据的版本已变化，请保留当前计划并退回过期候选。");
        var attempt=nodes.attempt(row.attemptId());if(!attempt.state().equals("SUCCEEDED"))throw new ConflictException("WORKFLOW_CANDIDATE_SOURCE_RUNNING","来源节点尚未成功收尾，请先等待或处理该节点。");
        var graph=request.graph()==null?encoding.read(row.graphJson(),row.sha256()):encoding.definition(request.graph()).graph();var base=context(attempt);scope(base.graph(),graph,base.sourceNodeKey());
        String origin=nodes.definition(nodes.requireNode(attempt.nodeRunId())).kind()==WorkflowGraph.NodeKind.SYSTEM?"USER":"AI_CONFIRMED";
        var receipt=revisions.apply(id,request.expectedVersion(),request.expectedRevision(),graph,origin);
        store.decide(row,WorkflowPlanCandidateState.APPLIED,receipt.revision(),"用户确认候选计划");
        return commands.record(request.requestKey(),digest,"REQUIREMENT","CANDIDATE_APPLY",receipt);
    }
    @Transactional
    public WorkflowCommands.Receipt reject(String id,String key,Reject request) {
        String digest=encoding.digest("PLAN_CANDIDATE_REJECT",id+"/"+key,request);var replay=commands.replay(request.requestKey(),digest);if(replay.isPresent())return replay.get();
        var row=store.require(id,key);if(!row.state().equals("PENDING") || row.version()!=request.expectedCandidateVersion())throw WorkflowCommands.conflict();
        String reason=encoding.description(request.reason());store.decide(row,WorkflowPlanCandidateState.REJECTED,null,reason);
        var owner=plans.require(id);
        if(!WorkflowState.valueOf(owner.state()).terminal() && !owner.state().equals("STOPPING")) {
            if(controls.find(id).isPresent())settlement.hold(id,"WORKFLOW_USER_PAUSED");settlement.settle(id,false,null);owner=plans.require(id);
        }
        return commands.record(request.requestKey(),digest,"REQUIREMENT","CANDIDATE_REJECT",new WorkflowCommands.Receipt(id,owner.headRevision(),owner.version(),owner.layoutVersion(),owner.state()));
    }
    private static void scope(WorkflowGraph before,WorkflowGraph after,String key){try{WorkflowPlanChanges.planningScope(before,after,key);}catch(IllegalArgumentException failure){throw invalid(failure.getMessage());}}
    private static BadRequestException invalid(String message){return new BadRequestException("WORKFLOW_PLAN_CANDIDATE_INVALID",message);}
}
