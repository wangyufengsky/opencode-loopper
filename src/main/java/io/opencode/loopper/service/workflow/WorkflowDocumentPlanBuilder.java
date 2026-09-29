package io.opencode.loopper.service.workflow;

import static io.opencode.loopper.workflow.WorkflowGraph.*;
import io.opencode.loopper.service.*;
import io.opencode.loopper.template.*;
import io.opencode.loopper.workflow.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Component;

/** Complete original scope becomes an explicit proposal, retaining the user's roles and review policy. */
@Component
public final class WorkflowDocumentPlanBuilder {
    private final WorkflowDocumentReviewContext contexts;
    private final WorkflowEncoding encoding;
    public WorkflowDocumentPlanBuilder(WorkflowDocumentReviewContext contexts,WorkflowEncoding encoding){this.contexts=contexts;this.encoding=encoding;}
    public record Result(WorkflowPlanCandidates.Proposal proposal,List<DocumentAssessmentBatches.Batch> batches,int sectionCount){ }
    public Result build(WorkflowSourcePlanStore.Context context) {
        var fixed=contexts.all(context.inputs());
        var batches=DocumentAssessmentBatches.partition(fixed.assigned().stream().map(s->new DocumentAssessmentBatches.Chapter(s.source(),s.characters())).toList());
        var before=context.plan().graph();var scope=context.plan().editableNodeKeys();
        var author=one(before,scope,WorkflowDocumentReview.AUTHOR,true);var review=one(before,scope,WorkflowDocumentReview.REVIEW,false);var report=one(before,scope,WorkflowDocument.ASSESSMENT_MODULE,true);
        if(batches.size()>(review==null?62:31))throw limit();
        try{WorkflowDocumentReview.require(author);if(review!=null)WorkflowDocumentReview.require(review);WorkflowDocument.require(report);}
        catch(IllegalArgumentException invalid){throw invalid(invalid.getMessage());}
        var sources=context.node().inputs();
        for(var node:review==null?List.of(author,report):List.of(author,review,report))
            if(!node.inputs().containsAll(sources))throw invalid("分批、评审、复核与汇总须绑定同一份原文及固定代码。");
        if(review==null&&WorkflowDocument.require(report)!=WorkflowDocument.ReviewPolicy.NONE)throw invalid("移除复核后，请明确将汇总策略设为不要求复核。");
        shape(before,context.node(),author,review,report);
        var changing=new HashSet<>(List.of(author.id(),report.id()));if(review!=null)changing.add(review.id());
        var steps=new ArrayList<>(before.nodes().stream().filter(n->!changing.contains(n.id())).toList());
        var edges=new ArrayList<>(before.edges().stream().filter(e->!changing.contains(e.to())&&!e.from().equals(author.id())&&(review==null||!e.from().equals(review.id()))).toList());
        var ids=new HashSet<>(before.nodes().stream().map(Node::id).toList());var authors=new ArrayList<String>();var reviews=new ArrayList<String>();
        for(var batch:batches){authors.add(id(author.id(),batch.ordinal(),ids));if(review!=null)reviews.add(id(review.id(),batch.ordinal(),ids));}
        for(var batch:batches) {
            int i=batch.ordinal();steps.add(clone(author,authors.get(i),batch,author.inputs()));edge(edges,context.node().id(),authors.get(i));
            if(review!=null) {
                var inputs=new ArrayList<>(review.inputs().stream().filter(p->p.kind()!=DataKind.JSON).toList());
                for(int j=0;j<authors.size();j++){inputs.add(new Input(j==i?"draft":"related_"+(j+1),InputSource.NODE,authors.get(j),"assessment",DataKind.JSON,true));edge(edges,authors.get(j),reviews.get(i));}
                steps.add(clone(review,reviews.get(i),batch,inputs));
            }
        }
        var inputs=new ArrayList<>(sources);
        for(int i=0;i<batches.size();i++) {
            inputs.add(new Input("draft_"+(i+1),InputSource.NODE,authors.get(i),"assessment",DataKind.JSON,true));
            if(review!=null)inputs.add(new Input("review_"+(i+1),InputSource.NODE,reviews.get(i),"review",DataKind.DECISION,true));
            edge(edges,review==null?authors.get(i):reviews.get(i),report.id());
        }
        steps.add(new Node(report.id(),report.title(),report.kind(),report.moduleId(),report.moduleVersion(),report.roleId(),report.task(),inputs,report.outputs(),report.outcomes(),report.completion(),report.maxRetries(),report.pauseAfter(),report.parameters(),report.roleRevisionId()));
        var graph=new WorkflowGraph(1,steps,edges,before.inputs());var diagnostics=WorkflowGraphValidator.validate(graph,WorkflowGraphValidator.Mode.EXECUTION);
        if(!diagnostics.isEmpty())throw invalid("分批候选未通过校验："+diagnostics.getFirst().message());
        encoding.definition(graph);WorkflowPlanChanges.planningScope(before,graph,context.node().id());
        var result=new Result(new WorkflowPlanCandidates.Proposal(1,context.plan().baseRevision(),graph),batches,fixed.assigned().size());
        if(encoding.encode(delivery(result,null)).getBytes(StandardCharsets.UTF_8).length>128*1024)throw limit();return result;
    }
    public WorkflowDelivery delivery(Result result,String code) {
        var outputs=new LinkedHashMap<String,WorkflowDelivery.Value>();var report=new LinkedHashMap<String,Object>();
        report.put("version",1);report.put("type","DOCUMENT_REVIEW_PLAN");report.put("complete",result!=null);
        if(result!=null){report.put("sectionCount",result.sectionCount());report.put("batchCount",result.batches().size());report.put("batches",result.batches());outputs.put("plan",value(DataKind.PLAN,result.proposal()));}
        if(code!=null)report.put("code",code);
        String summary=result==null?"原文分批候选未生成，请检查后续配置及完整范围。":"已按全部原文章节生成分批候选，请查看并确认后执行。";
        outputs.put("report",value(DataKind.JSON,report));outputs.put("summary",value(DataKind.TEXT,summary));return new WorkflowDelivery(summary,null,outputs);
    }
    private WorkflowDelivery.Value value(DataKind kind,Object value){return new WorkflowDelivery.Value(kind,encoding.decode(encoding.encode(value),tools.jackson.databind.JsonNode.class));}
    private Node one(WorkflowGraph graph,Set<String> scope,String module,boolean required) {
        var found=graph.nodes().stream().filter(n->scope.contains(n.id())&&module.equals(n.moduleId())).toList();
        if(found.size()>1||required&&found.isEmpty())throw invalid("分批前请保留一份评审、可选的一份复核和一份报告汇总配置。");return found.isEmpty()?null:found.getFirst();
    }
    private void shape(WorkflowGraph graph,Node planner,Node author,Node review,Node report) {
        var expected=new HashMap<String,String>();expected.put(author.id(),planner.id());if(review!=null)expected.put(review.id(),author.id());expected.put(report.id(),review==null?author.id():review.id());
        for(var entry:expected.entrySet()) {
            var incoming=graph.edges().stream().filter(e->e.to().equals(entry.getKey())).toList();
            if(incoming.size()!=1||incoming.getFirst().outcome()!=null||!incoming.getFirst().from().equals(entry.getValue()))throw invalid("请按分批→评审→复核（可选）→汇总连接，其他工作可放在汇总之后。");
        }
        if(graph.edges().stream().anyMatch(e->e.from().equals(author.id())&&!e.to().equals(review==null?report.id():review.id())||review!=null&&e.from().equals(review.id())&&!e.to().equals(report.id())))throw invalid("评审或复核存在其他消费者，请将其他工作接在汇总后，避免分批遗漏输入。");
        var internal=new HashSet<>(List.of(author.id(),report.id()));if(review!=null)internal.add(review.id());
        if(graph.nodes().stream().filter(n->!internal.contains(n.id())).flatMap(n->n.inputs().stream()).anyMatch(i->i.source()==InputSource.NODE&&(i.sourceId().equals(author.id())||review!=null&&i.sourceId().equals(review.id()))))throw invalid("分批外节点不能直接绑定尚未拆分的评审稿或复核意见。");
        if(review!=null&&review.inputs().stream().filter(i->i.kind()==DataKind.JSON).anyMatch(i->!i.name().equals("draft")||!i.sourceId().equals(author.id())||!"assessment".equals(i.output())))throw invalid("复核先绑定本批评审稿，关联稿将按完整批次配置。");
        if(report.inputs().stream().anyMatch(i->!Set.of("documents","code").contains(i.name())&&!(i.kind()==DataKind.JSON&&i.sourceId().equals(author.id())&&"assessment".equals(i.output()))&&!(review!=null&&i.kind()==DataKind.DECISION&&i.sourceId().equals(review.id())&&"review".equals(i.output()))))throw invalid("汇总节点包含其他稿件，请先检查配置。");
    }
    private Node clone(Node base,String id,DocumentAssessmentBatches.Batch batch,List<Input> inputs) {
        var parameters=new LinkedHashMap<>(base.parameters());parameters.put("documentBatchOrdinal",String.valueOf(batch.ordinal()));parameters.put("documentSections",encoding.encode(batch.sections()));
        String suffix=" · 第 "+(batch.ordinal()+1)+" 批",title=base.title();if(title.length()+suffix.length()>120)title=title.substring(0,120-suffix.length());
        return new Node(id,title+suffix,base.kind(),base.moduleId(),base.moduleVersion(),base.roleId(),base.task(),inputs,base.outputs(),base.outcomes(),base.completion(),base.maxRetries(),base.pauseAfter(),parameters,base.roleRevisionId());
    }
    private String id(String base,int ordinal,Set<String> ids){if(ordinal==0)return base;String id="batch_"+WorkflowEncoding.hash(base+":"+ordinal).substring(0,24);if(!ids.add(id))throw invalid("分批节点标识冲突，请调整现有节点。");return id;}
    private void edge(List<Edge> edges,String from,String to){edges.add(new Edge("batch_"+WorkflowEncoding.hash(from+":"+to).substring(0,24),from,to,null));}
    private static BadRequestException invalid(String message){return new BadRequestException("WORKFLOW_DOCUMENT_PLAN_INVALID",message);}
    private static BadRequestException limit(){return new BadRequestException("WORKFLOW_DOCUMENT_PLAN_LIMIT","完整原文分批超出节点或候选容量，请按明确业务范围拆成独立需求；原文与配置已保留，未截断章节。");}
}
