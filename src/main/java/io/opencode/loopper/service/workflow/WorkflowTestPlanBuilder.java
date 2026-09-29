package io.opencode.loopper.service.workflow;

import static io.opencode.loopper.workflow.WorkflowGraph.*;
import io.opencode.loopper.service.BadRequestException;
import io.opencode.loopper.template.*;
import io.opencode.loopper.workflow.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Component;

/** Deterministic module/batch expansion preserves the user's roles, tasks, policies and checkpoints. */
@Component
public final class WorkflowTestPlanBuilder {
    private final WorkflowTestInputs inputs;
    private final WorkflowEncoding encoding;
    public WorkflowTestPlanBuilder(WorkflowTestInputs inputs,WorkflowEncoding encoding){this.inputs=inputs;this.encoding=encoding;}
    private record Part(SourceDesign.Batch batch,SourceTestProfile.Module module){ }
    private record Configuration(Node design,Node writer,Node test,List<Node> reviews,Node summary,Input source,Input profile){ }
    public WorkflowSourcePlanBuilder.Result build(WorkflowSourcePlanStore.Context context) {
        var fixed=inputs.require(context.inputs());var parts=parts(fixed);var before=context.plan().graph();var config=configuration(context);
        int moduleCount=(int)parts.stream().map(p->p.module().root()).distinct().count();
        if(3+moduleCount+parts.size()*config.reviews().size()>64||parts.size()>61)throw limit();
        var changing=new HashSet<>(List.of(config.design().id(),config.writer().id(),config.test().id(),config.summary().id()));config.reviews().forEach(r->changing.add(r.id()));
        var result=new ArrayList<>(before.nodes().stream().filter(n->!changing.contains(n.id())).toList());
        var edges=new ArrayList<>(before.edges().stream().filter(e->!changing.contains(e.to())&&(!changing.contains(e.from())||e.from().equals(config.summary().id()))).toList());
        var ids=new HashSet<>(before.nodes().stream().map(Node::id).toList());var designs=new ArrayList<String>();var writers=new ArrayList<String>();
        for(var part:parts){designs.add(id(config.design().id(),part.batch().ordinal(),ids));writers.add(id(config.writer().id(),part.batch().ordinal(),ids));}
        for(var part:parts) {
            int i=part.batch().ordinal();var parameters=new LinkedHashMap<>(config.design().parameters());parameters.put("targetPaths",encoding.encode(part.batch().paths()));
            result.add(copy(config.design(),designs.get(i),"第 "+(i+1)+" 批",config.design().inputs(),parameters));edge(edges,context.node().id(),designs.get(i));
            var writerInputs=new ArrayList<>(config.writer().inputs().stream().filter(p->!p.name().equals("design")).toList());writerInputs.add(port("design",designs.get(i),"design",DataKind.JSON));
            var writerParameters=new LinkedHashMap<>(config.writer().parameters());
            if(i>0){writerInputs.add(port("batch_code",writers.get(i-1),"code",DataKind.CODE));writerParameters.put("workspaceInput","batch_code");edge(edges,writers.get(i-1),writers.get(i));}
            result.add(copy(config.writer(),writers.get(i),"第 "+(i+1)+" 批",writerInputs,writerParameters));edge(edges,designs.get(i),writers.get(i));
        }
        String finalWriter=writers.getLast();var moduleTests=new LinkedHashMap<String,String>();var summaryInputs=new ArrayList<>(List.of(config.source(),config.profile(),port("code",finalWriter,"code",DataKind.CODE)));
        for(var part:parts)if(!moduleTests.containsKey(part.module().root())) {
            int ordinal=moduleTests.size();String test=id(config.test().id(),ordinal,ids);moduleTests.put(part.module().root(),test);
            var testInputs=new ArrayList<>(List.of(config.source(),config.profile(),port("code",finalWriter,"code",DataKind.CODE)));int count=0;
            for(var selected:parts)if(selected.module().root().equals(part.module().root()))testInputs.add(port(count++==0?"design":"design_"+count,designs.get(selected.batch().ordinal()),"design",DataKind.JSON));
            var parameters=new LinkedHashMap<>(config.test().parameters());parameters.put("testModuleRoot",part.module().root());
            result.add(copy(config.test(),test,part.module().root().equals(".")?"项目根目录":part.module().root(),testInputs,parameters));edge(edges,finalWriter,test);
            summaryInputs.add(port("test_"+(ordinal+1),test,"report",DataKind.JSON));edge(edges,test,config.summary().id());
        }
        for(var part:parts)for(int index=0;index<config.reviews().size();index++) {
            var base=config.reviews().get(index);int ordinal=part.batch().ordinal();String review=id(base.id(),ordinal,ids),test=moduleTests.get(part.module().root());
            result.add(copy(base,review,"第 "+(ordinal+1)+" 批",List.of(config.source(),config.profile(),port("design",designs.get(ordinal),"design",DataKind.JSON),port("code",finalWriter,"code",DataKind.CODE),port("test",test,"report",DataKind.JSON)),base.parameters()));
            edge(edges,test,review);edge(edges,review,config.summary().id());summaryInputs.add(port("review_"+(ordinal+1)+"_"+(index+1),review,"review",DataKind.DECISION));
        }
        result.add(copy(config.summary(),config.summary().id(),null,summaryInputs,config.summary().parameters()));
        var graph=new WorkflowGraph(1,result,edges,before.inputs());var errors=WorkflowGraphValidator.validate(graph,WorkflowGraphValidator.Mode.EXECUTION);
        if(!errors.isEmpty())throw invalid("完整分批计划不符合图约束："+errors.getFirst().message());
        encoding.definition(graph);WorkflowPlanChanges.planningScope(before,graph,context.node().id());
        var planned=new WorkflowSourcePlanBuilder.Result(new WorkflowPlanCandidates.Proposal(1,context.plan().baseRevision(),graph),parts.stream().map(Part::batch).toList(),Math.toIntExact(fixed.manifest().targetCount()));
        if(encoding.encode(delivery(planned,null)).getBytes(StandardCharsets.UTF_8).length>128*1024)throw limit();return planned;
    }
    private List<Part> parts(WorkflowTestInputs.Context fixed) {
        var files=new LinkedHashMap<String,SourceManifest.File>();fixed.manifest().files().stream().filter(SourceManifest.File::processable).forEach(f->files.put(f.path(),f));
        var assigned=new HashSet<String>();var result=new ArrayList<Part>();
        for(var module:fixed.frozen().profile().modules().stream().sorted(Comparator.comparing(SourceTestProfile.Module::root)).toList()) {
            var selected=new ArrayList<SourceDesignBatches.File>();
            for(String path:module.sourcePaths().stream().sorted().toList()) {
                var file=files.get(path);if(file==null||!assigned.add(path))throw invalid("测试配置的模块范围与固定源码不一致。");selected.add(new SourceDesignBatches.File(path,file.sizeBytes()));
            }
            for(var batch:SourceDesignBatches.partition(selected).batches())result.add(new Part(new SourceDesign.Batch(result.size(),module.root()+" · "+batch.title(),batch.paths()),module));
        }
        if(result.isEmpty()||!assigned.equals(files.keySet()))throw invalid("完整测试配置必须覆盖所有固定目标源码。");return List.copyOf(result);
    }
    private Configuration configuration(WorkflowSourcePlanStore.Context context) {
        var graph=context.plan().graph();var area=context.plan().editableNodeKeys();
        var design=one(graph,area,WorkflowTestDesign.MODULE);
        var writer=one(graph,area,WorkflowTestWrite.MODULE);
        var test=one(graph,area,WorkflowNativeTest.MODULE);
        var summary=one(graph,area,WorkflowTestSummary.MODULE);
        var reviews=graph.nodes().stream().filter(n->area.contains(n.id())&&WorkflowTestReview.MODULE.equals(n.moduleId())).toList();
        try{WorkflowTestDesign.require(design);WorkflowTestWrite.require(writer);WorkflowNativeTest.require(test);reviews.forEach(WorkflowTestReview::require);WorkflowTestSummary.require(summary);}
        catch(IllegalArgumentException failure){throw invalid(failure.getMessage());}
        if(test.moduleVersion()!=2||test.inputs().size()!=4||reviews.stream().anyMatch(r->r.moduleVersion()!=2)||writer.inputs().stream().anyMatch(i->i.name().equals("batch_code")))throw invalid("分批前请选择最终代码测试和复核模块，各保留一批配置；batch_code 输入名由程序保留。");
        var policy=WorkflowTestSummary.require(summary);int required=switch(policy){case NONE->0;case SINGLE->1;case DUAL->2;};
        if(reviews.size()!=required||required==2&&Objects.equals(reviews.getFirst().roleId(),reviews.getLast().roleId()))throw invalid("请让复核节点数量与汇总策略一致；双复核需要两位不同角色，移除复核后请明确调整策略。");
        var source=context.node().inputs().stream().filter(i->i.name().equals("source")).findFirst().orElseThrow();var profile=context.node().inputs().stream().filter(i->i.name().equals("profile")).findFirst().orElseThrow();
        var controlled=new ArrayList<>(List.of(design,writer,test,summary));controlled.addAll(reviews);
        for(var node:controlled)if(!node.inputs().contains(source)||!node.inputs().contains(profile))throw invalid("各节点必须绑定规划节点所用的同一份源码与测试配置。");
        if(!writer.inputs().contains(port("design",design.id(),"design",DataKind.JSON))||!test.inputs().contains(port("design",design.id(),"design",DataKind.JSON))||!test.inputs().contains(port("code",writer.id(),"code",DataKind.CODE)))throw invalid("分批配置的场景和代码来源不匹配。");
        for(var review:reviews)if(!review.inputs().containsAll(List.of(port("design",design.id(),"design",DataKind.JSON),port("code",writer.id(),"code",DataKind.CODE),port("test",test.id(),"report",DataKind.JSON))))throw invalid("复核必须绑定本次配置的设计、代码和原生测试。");
        if(!summary.inputs().contains(port("code",writer.id(),"code",DataKind.CODE))||summary.inputs().size()!=4+reviews.size()
                ||summary.inputs().stream().filter(i->i.name().startsWith("test_")).anyMatch(i->!i.sourceId().equals(test.id())||!"report".equals(i.output()))
                ||summary.inputs().stream().filter(i->i.name().startsWith("review_")).anyMatch(i->reviews.stream().noneMatch(r->r.id().equals(i.sourceId()))||!"review".equals(i.output()))
                ||summary.inputs().stream().filter(i->i.name().startsWith("review_")).map(Input::sourceId).distinct().count()!=reviews.size())throw invalid("汇总配置存在其他测试或复核输入，请先核对来源。");
        var expected=new LinkedHashMap<String,Set<String>>();expected.put(design.id(),Set.of(context.node().id()));expected.put(writer.id(),Set.of(design.id()));expected.put(test.id(),Set.of(writer.id()));
        reviews.forEach(r->expected.put(r.id(),Set.of(test.id())));var summaryParents=new HashSet<String>();summaryParents.add(test.id());reviews.forEach(r->summaryParents.add(r.id()));expected.put(summary.id(),summaryParents);
        shape(graph,expected,summary.id());return new Configuration(design,writer,test,reviews,summary,source,profile);
    }
    private void shape(WorkflowGraph graph,Map<String,Set<String>> expected,String summary) {
        for(var entry:expected.entrySet()) {
            var incoming=graph.edges().stream().filter(e->e.to().equals(entry.getKey())).toList();
            if(incoming.size()!=entry.getValue().size()||incoming.stream().anyMatch(e->e.outcome()!=null||!entry.getValue().contains(e.from())))throw invalid("分批前请按规划→场景→编写→测试→复核连接，汇总同时依赖测试和全部复核。");
        }
        var internal=expected.keySet();
        if(graph.edges().stream().anyMatch(e->internal.contains(e.from())&&!e.from().equals(summary)&&!internal.contains(e.to()))
                ||graph.nodes().stream().filter(n->!internal.contains(n.id())).flatMap(n->n.inputs().stream()).anyMatch(i->i.source()==InputSource.NODE&&internal.contains(i.sourceId())&&!i.sourceId().equals(summary)))throw invalid("分批外节点不能直接消费未拆分的中间结果，请放在汇总之后。");
    }
    private Node one(WorkflowGraph graph,Set<String> area,String module){var found=graph.nodes().stream().filter(n->area.contains(n.id())&&module.equals(n.moduleId())).toList();if(found.size()!=1)throw invalid("分批前需要各一份场景设计、编写、最终测试及汇总配置。");return found.getFirst();}
    private static Input port(String name,String node,String output,DataKind kind){return new Input(name,InputSource.NODE,node,output,kind,true);}
    private Node copy(Node n,String id,String suffix,List<Input> inputs,Map<String,String> parameters) {
        String title=n.title();if(suffix!=null){suffix=" · "+suffix;if(suffix.length()>80)suffix=suffix.substring(0,80);if(title.length()+suffix.length()>120)title=title.substring(0,120-suffix.length());title+=suffix;}
        return new Node(id,title,n.kind(),n.moduleId(),n.moduleVersion(),n.roleId(),n.task(),inputs,n.outputs(),n.outcomes(),n.completion(),n.maxRetries(),n.pauseAfter(),parameters,n.roleRevisionId());
    }
    private String id(String base,int ordinal,Set<String> ids){if(ordinal==0)return base;String id="test_batch_"+WorkflowEncoding.hash(base+":"+ordinal).substring(0,24);if(!ids.add(id))throw invalid("分批节点与现有节点标识冲突。");return id;}
    private void edge(List<Edge> edges,String from,String to){edges.add(new Edge("test_batch_"+WorkflowEncoding.hash(from+":"+to).substring(0,24),from,to,null));}
    public WorkflowDelivery delivery(WorkflowSourcePlanBuilder.Result result,String code) {
        var outputs=new LinkedHashMap<String,WorkflowDelivery.Value>();var report=new LinkedHashMap<String,Object>();report.put("version",1);report.put("type","SOURCE_TEST_PLAN");report.put("complete",result!=null);
        if(result!=null){report.put("sourceCount",result.sourceCount());report.put("batchCount",result.batches().size());report.put("batches",result.batches());outputs.put("plan",value(DataKind.PLAN,result.proposal()));}if(code!=null)report.put("code",code);
        String summary=result==null?"单测分批计划未生成，请检查后续配置与完整范围。":"单测分批候选已生成，确认后按批继承代码，并对最终代码回归和复核。";
        outputs.put("report",value(DataKind.JSON,report));outputs.put("summary",value(DataKind.TEXT,summary));return new WorkflowDelivery(summary,null,outputs);
    }
    private WorkflowDelivery.Value value(DataKind kind,Object body){return new WorkflowDelivery.Value(kind,encoding.decode(encoding.encode(body),tools.jackson.databind.JsonNode.class));}
    private static BadRequestException invalid(String message){return new BadRequestException("WORKFLOW_TEST_PLAN_INVALID",message);}
    private static BadRequestException limit(){return new BadRequestException("WORKFLOW_TEST_PLAN_LIMIT","完整单测计划超过节点、汇总输入或候选正文容量，请按明确源码范围拆分需求，原资料和配置已保留。");}
}
