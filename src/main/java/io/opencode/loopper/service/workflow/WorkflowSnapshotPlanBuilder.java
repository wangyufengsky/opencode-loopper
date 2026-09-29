package io.opencode.loopper.service.workflow;

import static io.opencode.loopper.workflow.WorkflowGraph.*;
import io.opencode.loopper.service.*;
import io.opencode.loopper.template.*;
import io.opencode.loopper.workflow.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Component;

/** Complete V3 capacity batches become user-confirmed nodes with explicit conditional reviews. */
@Component
public final class WorkflowSnapshotPlanBuilder {
    private final WorkflowSnapshotInputs inputs;
    private final WorkflowSnapshotEvidence evidence;
    private final WorkflowEncoding encoding;
    public WorkflowSnapshotPlanBuilder(WorkflowSnapshotInputs inputs,WorkflowSnapshotEvidence evidence,WorkflowEncoding encoding){this.inputs=inputs;this.evidence=evidence;this.encoding=encoding;}
    public record Result(WorkflowPlanCandidates.Proposal proposal,int unitCount,int excludedCount,int batchCount,boolean reviews){ }
    public Result build(WorkflowSourcePlanStore.Context context) {
        var fixed=inputs.source(context.inputs());var snapshot=evidence.read(fixed.manifest());
        var groups=SnapshotReviewLightweightPolicy.plan(snapshot.units()).groups();
        var before=context.plan().graph();var scope=context.plan().editableNodeKeys();
        var analysis=one(before,scope,WorkflowSnapshotWork.ANALYZE,true);var review=one(before,scope,WorkflowSnapshotWork.REVIEW,false);
        var report=one(before,scope,WorkflowSnapshotReport.MODULE,true);
        try{WorkflowSnapshotWork.require(analysis);if(review!=null)WorkflowSnapshotWork.require(review);WorkflowSnapshotReport.require(report);}
        catch(IllegalArgumentException bad){throw invalid(bad.getMessage());}
        if(review==null&&WorkflowSnapshotReport.require(report)!=WorkflowDocument.ReviewPolicy.NONE)throw invalid("移除复核后，请明确将报告策略设为不要求复核。");
        if(groups.size()>(review==null?63:31))throw limit();shape(context.node(),before,analysis,review,report);
        var changing=new HashSet<>(List.of(analysis.id(),report.id()));if(review!=null)changing.add(review.id());
        var steps=new ArrayList<>(before.nodes().stream().filter(n->!changing.contains(n.id())).toList());
        var edges=new ArrayList<>(before.edges().stream().filter(e->!changing.contains(e.to())&&!e.from().equals(analysis.id())&&(review==null||!e.from().equals(review.id()))).toList());
        var ids=new HashSet<>(before.nodes().stream().map(Node::id).toList());var bindings=new ArrayList<>(context.node().inputs());
        for(int i=0;i<groups.size();i++) {
            String author=id(analysis.id(),i,ids);steps.add(copy(analysis,author,i,analysis.inputs()));edge(edges,context.node().id(),author,null);
            bindings.add(result("analysis_"+(i+1),author,true));
            if(review!=null) {
                String checker=id(review.id(),i,ids);var assigned=new ArrayList<>(review.inputs().stream().filter(v->v.kind()!=DataKind.JSON).toList());
                assigned.add(result("analysis",author,true));steps.add(copy(review,checker,i,assigned));
                edge(edges,author,checker,"HAS_FINDINGS");edge(edges,checker,report.id(),null);
                edge(edges,author,report.id(),"NO_FINDINGS");bindings.add(result("review_"+(i+1),checker,false));
            }else edge(edges,author,report.id(),null);
        }
        if(groups.isEmpty())edge(edges,context.node().id(),report.id(),null);
        steps.add(copy(report,report.id(),-1,bindings));var graph=new WorkflowGraph(1,steps,edges,before.inputs());
        var diagnostics=WorkflowGraphValidator.validate(graph,WorkflowGraphValidator.Mode.EXECUTION);
        if(!diagnostics.isEmpty())throw invalid("版本分批候选未通过校验："+diagnostics.getFirst().message());
        encoding.definition(graph);WorkflowPlanChanges.planningScope(before,graph,context.node().id());
        var result=new Result(new WorkflowPlanCandidates.Proposal(1,context.plan().baseRevision(),graph),snapshot.units().size(),fixed.manifest().excludedCount(),groups.size(),review!=null);
        if(encoding.encode(delivery(result,null)).getBytes(StandardCharsets.UTF_8).length>128*1024)throw limit();return result;
    }
    public WorkflowDelivery delivery(Result result,String code) {
        var outputs=new LinkedHashMap<String,WorkflowDelivery.Value>();var report=new LinkedHashMap<String,Object>();
        report.put("version",1);report.put("type","SNAPSHOT_PLAN");report.put("complete",result!=null);
        if(result!=null){report.put("unitCount",result.unitCount());report.put("excludedCount",result.excludedCount());report.put("batchCount",result.batchCount());report.put("conditionalReviews",result.reviews());outputs.put("plan",value(DataKind.PLAN,result.proposal()));}
        if(code!=null)report.put("code",code);
        String summary=result==null?"版本分批候选未生成，请检查后续配置与完整范围。":"完整版本分批候选已生成，请查看并确认；无可审查变化时直接生成范围报告。";
        outputs.put("report",value(DataKind.JSON,report));outputs.put("summary",value(DataKind.TEXT,summary));return new WorkflowDelivery(summary,null,outputs);
    }
    private void shape(Node planner,WorkflowGraph graph,Node analysis,Node review,Node report) {
        var source=planner.inputs().getFirst();
        for(var node:review==null?List.of(analysis,report):List.of(analysis,review,report))if(!node.inputs().contains(source))throw invalid("分批、分析、复核与报告须绑定同一份固定版本资料。");
        var expected=new HashSet<String>();expected.add(key(planner.id(),analysis.id(),null));
        if(review==null)expected.add(key(analysis.id(),report.id(),null));
        else {expected.add(key(analysis.id(),review.id(),"HAS_FINDINGS"));expected.add(key(analysis.id(),report.id(),"NO_FINDINGS"));expected.add(key(review.id(),report.id(),null));}
        var internal=new HashSet<>(List.of(analysis.id(),report.id()));if(review!=null)internal.add(review.id());
        var actual=graph.edges().stream().filter(e->internal.contains(e.to())||e.from().equals(analysis.id())||review!=null&&e.from().equals(review.id())).map(e->key(e.from(),e.to(),e.outcome())).toList();
        if(actual.size()!=expected.size()||!new HashSet<>(actual).equals(expected))throw invalid("请按分批→分析连接；有问题进入复核，无问题进入报告，复核后汇入报告。移除复核时分析直接连接报告。");
        if(graph.nodes().stream().filter(n->!internal.contains(n.id())).flatMap(n->n.inputs().stream()).anyMatch(i->i.source()==InputSource.NODE&&(i.sourceId().equals(analysis.id())||review!=null&&i.sourceId().equals(review.id()))))throw invalid("分批外节点不能绑定尚未展开的分析或复核输出，请放在报告之后。");
        if(review!=null&&review.inputs().stream().filter(i->i.kind()==DataKind.JSON).anyMatch(i->!i.name().equals("analysis")||!i.sourceId().equals(analysis.id())||!"analysis".equals(i.output())))throw invalid("复核原型仅绑定当前分析；批次展开后保留各自独立输入。");
        if(report.inputs().stream().filter(i->i.kind()==DataKind.JSON).anyMatch(i->!"analysis".equals(i.output())||!i.sourceId().equals(analysis.id())&&(review==null||!i.sourceId().equals(review.id()))))throw invalid("报告包含其他分析，请检查固定范围。");
    }
    private Node one(WorkflowGraph graph,Set<String> scope,String module,boolean required){var found=graph.nodes().stream().filter(n->scope.contains(n.id())&&module.equals(n.moduleId())).toList();if(found.size()>1||required&&found.isEmpty())throw invalid("分批前请保留一份分析、可选的一份复核和一份报告配置。");return found.isEmpty()?null:found.getFirst();}
    private Node copy(Node base,String id,int ordinal,List<Input> inputs){
        var parameters=new LinkedHashMap<>(base.parameters());String title=base.title();
        if(ordinal>=0){if(WorkflowSnapshotWork.ANALYZE.equals(base.moduleId()))parameters.put("snapshotBatchOrdinal",String.valueOf(ordinal));String suffix=" · 第 "+(ordinal+1)+" 批";title=title.substring(0,Math.min(title.length(),120-suffix.length()))+suffix;}
        return new Node(id,title,base.kind(),base.moduleId(),base.moduleVersion(),base.roleId(),base.task(),inputs,base.outputs(),base.outcomes(),base.completion(),base.maxRetries(),base.pauseAfter(),parameters,base.roleRevisionId());
    }
    private Input result(String name,String node,boolean required){return new Input(name,InputSource.NODE,node,"analysis",DataKind.JSON,required);}
    private String id(String base,int ordinal,Set<String> ids){if(ordinal==0)return base;String id="snapshot_"+WorkflowEncoding.hash(base+":"+ordinal).substring(0,24);if(!ids.add(id))throw invalid("分批节点标识冲突，请调整已有节点。");return id;}
    private static String key(String from,String to,String outcome){return from+":"+to+":"+outcome;}
    private void edge(List<Edge> edges,String from,String to,String outcome){edges.add(new Edge("snapshot_"+WorkflowEncoding.hash(key(from,to,outcome)).substring(0,24),from,to,outcome));}
    private WorkflowDelivery.Value value(DataKind kind,Object value){return new WorkflowDelivery.Value(kind,encoding.decode(encoding.encode(value),tools.jackson.databind.JsonNode.class));}
    private static BadRequestException invalid(String message){return new BadRequestException("WORKFLOW_SNAPSHOT_PLAN_INVALID",message);}
    private static BadRequestException limit(){return new BadRequestException("WORKFLOW_SNAPSHOT_PLAN_LIMIT","完整版本分批超过画布或候选容量，请明确缩小项目范围或日期范围；已保留资料，未截断代码。");}
}
