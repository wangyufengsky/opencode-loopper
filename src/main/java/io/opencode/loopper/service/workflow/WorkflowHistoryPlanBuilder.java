package io.opencode.loopper.service.workflow;

import static io.opencode.loopper.workflow.WorkflowGraph.*;
import io.opencode.loopper.service.*;
import io.opencode.loopper.template.*;
import io.opencode.loopper.workflow.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Component;

/** Partition the complete fixed history without assigning planning to another model or bypassing confirmation. */
@Component
public final class WorkflowHistoryPlanBuilder {
    private final WorkflowHistoryInputs inputs;
    private final WorkflowHistoryEvidence evidence;
    private final WorkflowEncoding encoding;
    public WorkflowHistoryPlanBuilder(WorkflowHistoryInputs inputs,WorkflowHistoryEvidence evidence,WorkflowEncoding encoding){this.inputs=inputs;this.evidence=evidence;this.encoding=encoding;}
    public record Result(WorkflowPlanCandidates.Proposal proposal,int commitCount,int unitCount,int batchCount,int contributorCount){ }
    public Result build(WorkflowSourcePlanStore.Context context) {
        var fixed=inputs.read(context.inputs());var history=evidence.read(fixed.manifest());
        var units=TemplateAnalysisPartitioner.units(history);var batches=TemplateAnalysisPartitioner.batches(units);
        var before=context.plan().graph();var scope=context.plan().editableNodeKeys();
        var review=one(before,scope,WorkflowHistoryAnalysis.REVIEW,true);var contribution=one(before,scope,WorkflowHistoryAnalysis.CONTRIBUTION,false);
        var report=one(before,scope,WorkflowHistoryReport.MODULE,true);
        try{WorkflowHistoryAnalysis.require(review);WorkflowHistoryReport.require(report);}catch(IllegalArgumentException bad){throw invalid(bad.getMessage());}
        boolean scoring=WorkflowHistoryReport.kind(report)==TemplateTaskDefinition.CONTRIBUTION_REPORT;
        if(scoring!=(contribution!=null))throw invalid("贡献周报需要个人评价配置；历史提交审查仅需审查与汇总配置。");
        var people=scoring?TemplateContributionFacts.people(history).stream().filter(p->!p.author().robot()&&p.effectiveLines()>0).toList():List.<TemplateContributionFacts.Person>of();
        if(batches.size()+people.size()>63)throw limit();
        shape(context.node(),before,review,contribution,report);
        var changing=new HashSet<>(List.of(review.id(),report.id()));if(contribution!=null)changing.add(contribution.id());
        var steps=new ArrayList<>(before.nodes().stream().filter(n->!changing.contains(n.id())).toList());
        var edges=new ArrayList<>(before.edges().stream().filter(e->!changing.contains(e.to())&&!e.from().equals(review.id())&&(contribution==null||!e.from().equals(contribution.id()))).toList());
        var ids=new HashSet<>(before.nodes().stream().map(Node::id).toList());var reviewers=new ArrayList<String>();
        var reportInputs=new ArrayList<>(context.node().inputs());
        for(int i=0;i<batches.size();i++) {
            String id=id(review.id(),i,ids);reviewers.add(id);
            steps.add(copy(review,id,"第 "+(i+1)+" 批",review.inputs(),Map.of("historyBatchOrdinal",String.valueOf(i))));
            edge(edges,context.node().id(),id);edge(edges,id,report.id());reportInputs.add(analysis("review_"+(i+1),id));
        }
        for(int i=0;i<people.size();i++) {
            var person=people.get(i);String id=id(contribution.id(),i,ids);
            var bindings=new ArrayList<>(contribution.inputs().stream().filter(v->v.kind()!=DataKind.JSON).toList());
            for(int j=0;j<batches.size();j++)if(batches.get(j).stream().anyMatch(u->person.evidenceIds().contains(u.evidenceId()))) {
                bindings.add(analysis("review_"+(j+1),reviewers.get(j)));edge(edges,reviewers.get(j),id);
            }
            var node=copy(contribution,id,person.author().name(),bindings,Map.of("historyContributorEmail",person.author().email()));
            try{WorkflowHistoryAnalysis.require(node);}catch(IllegalArgumentException bad){throw invalid(bad.getMessage());}
            steps.add(node);edge(edges,id,report.id());reportInputs.add(analysis("person_"+(i+1),id));
        }
        if(batches.isEmpty())edge(edges,context.node().id(),report.id());
        steps.add(copy(report,report.id(),null,reportInputs,Map.of()));
        var graph=new WorkflowGraph(1,steps,edges,before.inputs());
        if(graph.nodes().size()>256)throw limit();
        var diagnostics=WorkflowGraphValidator.validate(graph,WorkflowGraphValidator.Mode.EXECUTION);
        if(!diagnostics.isEmpty())throw invalid("历史分批候选未通过校验："+diagnostics.getFirst().message());
        encoding.definition(graph);WorkflowPlanChanges.planningScope(before,graph,context.node().id());
        var result=new Result(new WorkflowPlanCandidates.Proposal(1,context.plan().baseRevision(),graph),history.commits().size(),units.size(),batches.size(),people.size());
        if(encoding.encode(delivery(result,null)).getBytes(StandardCharsets.UTF_8).length>128*1024)throw limit();return result;
    }
    public WorkflowDelivery delivery(Result result,String code) {
        var outputs=new LinkedHashMap<String,WorkflowDelivery.Value>();var report=new LinkedHashMap<String,Object>();
        report.put("version",1);report.put("type","HISTORY_PLAN");report.put("complete",result!=null);
        if(result!=null){report.put("commitCount",result.commitCount());report.put("unitCount",result.unitCount());report.put("batchCount",result.batchCount());report.put("contributorCount",result.contributorCount());outputs.put("plan",value(DataKind.PLAN,result.proposal()));}
        if(code!=null)report.put("code",code);
        String summary=result==null?"历史分批候选未生成，请检查后续配置及完整范围。":"已按全部固定历史生成候选，请查看并确认后执行；空范围直接生成无提交报告。";
        outputs.put("report",value(DataKind.JSON,report));outputs.put("summary",value(DataKind.TEXT,summary));return new WorkflowDelivery(summary,null,outputs);
    }
    private void shape(Node planner,WorkflowGraph graph,Node review,Node contribution,Node report) {
        var source=planner.inputs().getFirst();
        for(var node:contribution==null?List.of(review,report):List.of(review,contribution,report))
            if(!node.inputs().contains(source))throw invalid("分批、审查、贡献评价与汇总须绑定同一份固定历史。");
        var expected=new HashMap<String,String>();expected.put(review.id(),planner.id());if(contribution!=null)expected.put(contribution.id(),review.id());expected.put(report.id(),contribution==null?review.id():contribution.id());
        for(var entry:expected.entrySet()) {
            var incoming=graph.edges().stream().filter(e->e.to().equals(entry.getKey())).toList();
            if(incoming.size()!=1||incoming.getFirst().outcome()!=null||!incoming.getFirst().from().equals(entry.getValue()))throw invalid("请按分批→审查→贡献评价（可选）→报告连接，其他工作放在报告之后。");
        }
        if(graph.edges().stream().anyMatch(e->e.from().equals(review.id())&&!e.to().equals(contribution==null?report.id():contribution.id())||contribution!=null&&e.from().equals(contribution.id())&&!e.to().equals(report.id())))throw invalid("待分批分析节点存在其他消费者，请将其他工作移至报告之后。");
        var internal=new HashSet<>(List.of(review.id(),report.id()));if(contribution!=null)internal.add(contribution.id());
        if(graph.nodes().stream().filter(n->!internal.contains(n.id())).flatMap(n->n.inputs().stream()).anyMatch(i->i.source()==InputSource.NODE&&(i.sourceId().equals(review.id())||contribution!=null&&i.sourceId().equals(contribution.id()))))throw invalid("分批外节点不能绑定尚未展开的分析输出。");
        if(contribution!=null&&contribution.inputs().stream().filter(i->i.kind()==DataKind.JSON).anyMatch(i->!i.sourceId().equals(review.id())||!"analysis".equals(i.output())))throw invalid("贡献评价原型请绑定当前审查配置。");
        if(report.inputs().stream().filter(i->i.kind()==DataKind.JSON).anyMatch(i->!"analysis".equals(i.output())||!i.sourceId().equals(review.id())&&(contribution==null||!i.sourceId().equals(contribution.id()))))throw invalid("报告配置包含其他分析，请先检查范围。");
    }
    private Node one(WorkflowGraph graph,Set<String> scope,String module,boolean required) {
        var found=graph.nodes().stream().filter(n->scope.contains(n.id())&&module.equals(n.moduleId())).toList();
        if(found.size()>1||required&&found.isEmpty())throw invalid("分批前请保留一份审查、可选的一份贡献评价和一份报告配置。");return found.isEmpty()?null:found.getFirst();
    }
    private Node copy(Node base,String id,String suffix,List<Input> inputs,Map<String,String> overrides) {
        var parameters=new LinkedHashMap<>(base.parameters());parameters.putAll(overrides);
        String title=suffix==null?base.title():base.title()+" · "+suffix;if(title.length()>120)title=title.substring(0,120);
        return new Node(id,title,base.kind(),base.moduleId(),base.moduleVersion(),base.roleId(),base.task(),inputs,base.outputs(),base.outcomes(),base.completion(),base.maxRetries(),base.pauseAfter(),parameters,base.roleRevisionId());
    }
    private Input analysis(String name,String id){return new Input(name,InputSource.NODE,id,"analysis",DataKind.JSON,true);}
    private String id(String base,int ordinal,Set<String> ids){if(ordinal==0)return base;String id="history_"+WorkflowEncoding.hash(base+":"+ordinal).substring(0,24);if(!ids.add(id))throw invalid("分批节点标识冲突，请调整现有节点。");return id;}
    private void edge(List<Edge> edges,String from,String to){edges.add(new Edge("history_"+WorkflowEncoding.hash(from+":"+to).substring(0,24),from,to,null));}
    private WorkflowDelivery.Value value(DataKind kind,Object value){return new WorkflowDelivery.Value(kind,encoding.decode(encoding.encode(value),tools.jackson.databind.JsonNode.class));}
    private static BadRequestException invalid(String message){return new BadRequestException("WORKFLOW_HISTORY_PLAN_INVALID",message);}
    private static BadRequestException limit(){return new BadRequestException("WORKFLOW_HISTORY_PLAN_LIMIT","完整历史分批超出节点或候选容量，请按明确日期范围拆成独立需求；资料已保留，未截断提交或人员。");}
}
