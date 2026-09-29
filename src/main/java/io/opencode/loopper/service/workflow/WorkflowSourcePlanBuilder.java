package io.opencode.loopper.service.workflow;

import static io.opencode.loopper.workflow.WorkflowGraph.*;
import io.opencode.loopper.service.*;
import io.opencode.loopper.template.*;
import io.opencode.loopper.workflow.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Component;

/** Clones the user's frozen downstream configuration; it never picks a new role or changes an active plan. */
@Component
public final class WorkflowSourcePlanBuilder {
    private final WorkflowSourceRecords sources;
    private final WorkflowEncoding encoding;
    public WorkflowSourcePlanBuilder(WorkflowSourceRecords sources,WorkflowEncoding encoding){this.sources=sources;this.encoding=encoding;}
    public record Result(WorkflowPlanCandidates.Proposal proposal,List<SourceDesign.Batch> batches,int sourceCount) { }
    public Result build(WorkflowSourcePlanStore.Context context) {
        var source=context.inputs().values().getFirst();
        var reference=encoding.decode(encoding.encode(source.content()),WorkflowSourceSnapshot.Reference.class);
        var manifest=sources.manifest(context.project(),context.inputs().requirementId(),source.attemptId(),reference);
        var files=manifest.files().stream().filter(SourceManifest.File::processable).map(file->new SourceDesignBatches.File(file.path(),file.sizeBytes())).toList();
        var batches=SourceDesignBatches.partition(files).batches();
        if(batches.isEmpty())throw new BadRequestException("SOURCE_NO_APPLICABLE_FILES","没有可用于规划的目标源码。");
        var before=context.plan().graph();var area=context.plan().editableNodeKeys();
        var author=one(before,area,WorkflowSourceDesign.AUTHOR,true);var review=one(before,area,WorkflowSourceDesign.REVIEW,false);var document=one(before,area,WorkflowDocument.MODULE,true);
        if(batches.size()>(review==null?63:31))throw limit(); // A document has at most 64 explicit input ports.
        try{WorkflowSourceDesign.require(author);if(review!=null)WorkflowSourceDesign.require(review);WorkflowDocument.require(document);}
        catch(IllegalArgumentException configuration){throw invalid(configuration.getMessage());}
        String sourceKey=context.node().inputs().getFirst().sourceId(),sourceOutput=context.node().inputs().getFirst().output();
        var sourcePort=new Input("source",InputSource.NODE,sourceKey,sourceOutput,DataKind.DOCUMENT,true);
        if(!author.inputs().contains(sourcePort)||review!=null&&!review.inputs().contains(sourcePort)||!document.inputs().contains(sourcePort))throw invalid("编写、复核与汇总节点必须绑定分批节点所用的同一份源码。");
        if(review==null&&WorkflowDocument.require(document)!=WorkflowDocument.ReviewPolicy.NONE)throw invalid("已移除复核节点，请明确把汇总策略改为不要求复核，或恢复复核节点。");
        requireShape(before,context.node(),author,review,document);
        var changing=new HashSet<>(List.of(author.id(),document.id()));if(review!=null)changing.add(review.id());
        var steps=new ArrayList<>(before.nodes().stream().filter(node->!changing.contains(node.id())).toList());
        var edges=new ArrayList<>(before.edges().stream().filter(edge->!changing.contains(edge.to())&&!edge.from().equals(author.id())&&(review==null||!edge.from().equals(review.id()))).toList());
        var ids=new HashSet<>(before.nodes().stream().map(Node::id).toList());var authorIds=new ArrayList<String>();var reviewIds=new ArrayList<String>();
        for(var batch:batches){authorIds.add(cloneId(author.id(),batch.ordinal(),ids));if(review!=null)reviewIds.add(cloneId(review.id(),batch.ordinal(),ids));}
        for(var batch:batches) {
            int i=batch.ordinal();String authorId=authorIds.get(i);
            steps.add(clone(author,authorId,batch,author.inputs()));edge(edges,context.node().id(),authorId);
            if(review!=null) {
                var inputs=new ArrayList<>(review.inputs().stream().filter(input->input.kind()!=DataKind.JSON).toList());
                for(int j=0;j<authorIds.size();j++)inputs.add(new Input(j==i?"draft":"related_"+(j+1),InputSource.NODE,authorIds.get(j),"design",DataKind.JSON,true));
                steps.add(clone(review,reviewIds.get(i),batch,inputs));for(var producer:authorIds)edge(edges,producer,reviewIds.get(i));
            }
        }
        var inputs=new ArrayList<Input>();inputs.add(sourcePort);
        for(int i=0;i<batches.size();i++) {
            inputs.add(new Input("draft_"+(i+1),InputSource.NODE,authorIds.get(i),"design",DataKind.JSON,true));
            if(review!=null)inputs.add(new Input("review_"+(i+1),InputSource.NODE,reviewIds.get(i),"review",DataKind.DECISION,true));
            edge(edges,review==null?authorIds.get(i):reviewIds.get(i),document.id());
        }
        steps.add(new Node(document.id(),document.title(),document.kind(),document.moduleId(),document.moduleVersion(),document.roleId(),document.task(),inputs,document.outputs(),document.outcomes(),document.completion(),document.maxRetries(),document.pauseAfter(),document.parameters(),document.roleRevisionId()));
        var graph=new WorkflowGraph(1,steps,edges,before.inputs());
        var diagnostics=WorkflowGraphValidator.validate(graph,WorkflowGraphValidator.Mode.EXECUTION);
        if(!diagnostics.isEmpty())throw new BadRequestException("WORKFLOW_SOURCE_PLAN_INVALID","分批计划未能通过校验："+diagnostics.getFirst().message());
        encoding.definition(graph);WorkflowPlanChanges.planningScope(before,graph,context.node().id());
        var result=new Result(new WorkflowPlanCandidates.Proposal(1,context.plan().baseRevision(),graph),batches,files.size());
        if(encoding.encode(delivery(result,null)).getBytes(StandardCharsets.UTF_8).length>128*1024)throw limit();
        return result;
    }
    public WorkflowDelivery delivery(Result result,String code) {
        var outputs=new LinkedHashMap<String,WorkflowDelivery.Value>();var report=new LinkedHashMap<String,Object>();
        report.put("version",1);report.put("type","SOURCE_DESIGN_PLAN");report.put("complete",result!=null);
        if(result!=null){report.put("sourceCount",result.sourceCount());report.put("batchCount",result.batches().size());report.put("batches",result.batches());outputs.put("plan",value(DataKind.PLAN,result.proposal()));}
        if(code!=null)report.put("code",code);
        String summary=result==null?"源码分批计划未生成，请检查后续配置与范围。":"已生成源码分批候选计划，用户确认后才会生效。";
        outputs.put("report",value(DataKind.JSON,report));outputs.put("summary",value(DataKind.TEXT,summary));return new WorkflowDelivery(summary,null,outputs);
    }
    private WorkflowDelivery.Value value(DataKind kind,Object value){return new WorkflowDelivery.Value(kind,encoding.decode(encoding.encode(value),tools.jackson.databind.JsonNode.class));}
    private Node one(WorkflowGraph graph,Set<String> scope,String module,boolean required) {
        var found=graph.nodes().stream().filter(node->scope.contains(node.id())&&module.equals(node.moduleId())).toList();
        if(found.size()>1||required&&found.isEmpty())throw invalid("分批前请保留一份编写、可选的一份复核及一份文档汇总节点，作为本次配置。");
        return found.isEmpty()?null:found.getFirst();
    }
    private void requireShape(WorkflowGraph graph,Node planner,Node author,Node review,Node document) {
        var expected=new HashMap<String,Set<String>>();expected.put(author.id(),Set.of(planner.id()));
        if(review!=null)expected.put(review.id(),Set.of(author.id()));expected.put(document.id(),Set.of(review==null?author.id():review.id()));
        for(var entry:expected.entrySet()) {
            var incoming=graph.edges().stream().filter(edge->edge.to().equals(entry.getKey())).toList();
            if(incoming.size()!=entry.getValue().size()||incoming.stream().anyMatch(edge->edge.outcome()!=null||!entry.getValue().contains(edge.from())))throw invalid("分批前请按分批规划→编写→复核（可选）→汇总连接，其他工作可放在汇总之后。");
        }
        if(graph.edges().stream().anyMatch(edge->edge.from().equals(author.id())&&!edge.to().equals(review==null?document.id():review.id())||review!=null&&edge.from().equals(review.id())&&!edge.to().equals(document.id())))
            throw invalid("编写和复核还有其他消费者，请先把它们改为读取汇总成果，避免分批后丢失输入。");
        var internal=new HashSet<>(List.of(author.id(),document.id()));if(review!=null)internal.add(review.id());
        if(graph.nodes().stream().filter(n->!internal.contains(n.id())).flatMap(n->n.inputs().stream())
                .anyMatch(i->i.source()==InputSource.NODE&&(i.sourceId().equals(author.id())||review!=null&&i.sourceId().equals(review.id()))))throw invalid("分批外节点不能直接绑定尚未拆分的设计稿或复核意见。");
        if(review!=null&&review.inputs().stream().filter(i->i.kind()==DataKind.JSON).anyMatch(i->!i.name().equals("draft")||!i.sourceId().equals(author.id())||!"design".equals(i.output())))throw invalid("分批前复核应绑定当前编写节点的主稿，关联稿会按完整批次配置。");
        if(document.inputs().stream().anyMatch(i->!i.name().equals("source")&&!(i.kind()==DataKind.JSON&&i.sourceId().equals(author.id())&&"design".equals(i.output()))&&!(review!=null&&i.kind()==DataKind.DECISION&&i.sourceId().equals(review.id())&&"review".equals(i.output()))))throw invalid("汇总节点存在其他稿件输入，请先检查后续配置。");
    }
    private Node clone(Node base,String id,SourceDesign.Batch batch,List<Input> inputs) {
        var parameters=new LinkedHashMap<>(base.parameters());parameters.put("targetPaths",encoding.encode(batch.paths()));
        String title=base.title();String suffix=" · 第 "+(batch.ordinal()+1)+" 批";if(title.length()+suffix.length()>120)title=title.substring(0,120-suffix.length());
        return new Node(id,title+suffix,base.kind(),base.moduleId(),base.moduleVersion(),base.roleId(),base.task(),inputs,base.outputs(),base.outcomes(),base.completion(),base.maxRetries(),base.pauseAfter(),parameters,base.roleRevisionId());
    }
    private String cloneId(String base,int ordinal,Set<String> ids) {
        if(ordinal==0)return base;String value="batch_"+WorkflowEncoding.hash(base+":"+ordinal).substring(0,24);
        if(!ids.add(value))throw invalid("分批节点标识与现有节点冲突，请先调整现有节点。");return value;
    }
    private void edge(List<Edge> edges,String from,String to){edges.add(new Edge("batch_"+WorkflowEncoding.hash(from+":"+to).substring(0,24),from,to,null));}
    private static BadRequestException invalid(String detail){return new BadRequestException("WORKFLOW_SOURCE_PLAN_INVALID",detail);}
    private static BadRequestException limit(){return new BadRequestException("WORKFLOW_SOURCE_PLAN_LIMIT","完整分批计划超出节点、输入、连接或候选正文容量，请按明确源码子目录建立独立流程；原源码和配置已保留。");}
}
