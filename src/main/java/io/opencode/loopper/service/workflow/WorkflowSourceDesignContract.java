package io.opencode.loopper.service.workflow;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.service.*;
import io.opencode.loopper.template.*;
import io.opencode.loopper.workflow.*;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/** Frozen-source authority and professional candidate semantics, with no filesystem or model I/O. */
@Component
public final class WorkflowSourceDesignContract {
    private final WorkflowSourceRecords sources;
    private final WorkflowExecutionMapper nodes;
    private final WorkflowPlanMapper plans;
    private final WorkflowSourceReadMapper reads;
    private final WorkflowEncoding encoding;
    public WorkflowSourceDesignContract(WorkflowSourceRecords sources,WorkflowExecutionMapper nodes,WorkflowPlanMapper plans,WorkflowSourceReadMapper reads,WorkflowEncoding encoding) {
        this.sources=sources;this.nodes=nodes;this.plans=plans;this.reads=reads;this.encoding=encoding;
    }
    public record Context(String project,WorkflowSourceSnapshot.Reference reference,SourceManifest manifest,SourceDesign.Input input,
                          Map<String,WorkflowDelivery.Input> drafts) { }
    public Context context(WorkflowGraph.Node node,WorkflowDelivery.Inputs inputs) {
        try{WorkflowSourceDesign.require(node);}catch(IllegalArgumentException invalid){throw invalid(invalid.getMessage());}
        var input=inputs.values().stream().filter(value->value.name().equals("source")).findFirst().orElseThrow(WorkflowCommands::conflict);
        if(input.kind()!=WorkflowGraph.DataKind.DOCUMENT||!input.source().equals("NODE")||input.attemptId()==null)throw invalid("请选择已完成的冻结源码资料。");
        var reference=encoding.decode(encoding.encode(input.content()),WorkflowSourceSnapshot.Reference.class);
        String project=plans.find(inputs.requirementId()).orElseThrow(WorkflowCommands::conflict).projectId();
        var manifest=sources.manifest(project,inputs.requirementId(),input.attemptId(),reference);
        var paths=WorkflowSourceTargets.select(node,manifest,encoding);var drafts=new LinkedHashMap<String,WorkflowDelivery.Input>();
        if(WorkflowSourceDesign.REVIEW.equals(node.moduleId()))for(var value:inputs.values())if(value.kind()==WorkflowGraph.DataKind.JSON){
            draft(inputs.requirementId(),input,value);drafts.put(value.name(),value);
        }
        if(WorkflowSourceDesign.REVIEW.equals(node.moduleId())) {
            var draft=drafts.get("draft");if(draft==null)throw invalid("请选择本批的设计稿交付。");
            var candidate=encoding.decode(encoding.encode(draft.content()),SourceDesign.Candidate.class);
            var covered=new HashSet<String>();candidate.sections().forEach(section->covered.addAll(section.paths()));
            if(!covered.equals(new HashSet<>(paths)))throw invalid("本批设计稿与复核目标范围不一致，请检查源码路径和设计稿绑定。");
        }
        return new Context(project,reference,manifest,new SourceDesign.Input(manifest.sha256(),paths,inputs.objective()+"\n"+node.task(),null,null,null),Map.copyOf(drafts));
    }
    public WorkflowDelivery accept(WorkflowExecutionRows.Attempt attempt,WorkflowGraph.Node node,WorkflowDelivery.Inputs inputs,WorkflowDelivery delivery) {
        var context=context(node,inputs);boolean review=WorkflowSourceDesign.REVIEW.equals(node.moduleId());String name=review?"review":"design";
        var value=delivery.outputs().get(name);if(value==null)throw invalid("请提交完整的专业设计交付。");
        String body=encoding.encode(value.content());if(body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>256*1024)throw invalid("单批设计或复核最多 256 KiB，请精简重复内容。");
        Object candidate;
        java.util.function.Function<String,List<SourceDesign.Read>> evidence=path->reads.sources(attempt.id(),"source",path).stream()
                .map(row->new SourceDesign.Read(row.sha256(),row.startLine(),row.endLine(),row.totalLines(),row.content())).toList();
        if(review) {
            for(var entry:context.drafts().entrySet())requireInputRead(attempt.id(),entry.getKey(),entry.getValue());
            var result=SourceDesignValidation.review(context.input(),candidate(body,SourceDesign.Review.class),evidence);
            if(!Objects.equals(delivery.outcome(),result.verdict()))throw invalid("节点业务结果必须与复核的通过或返修结论一致。");
            candidate=result;
        }else candidate=SourceDesignValidation.design(context.input(),candidate(body,SourceDesign.Candidate.class),evidence);
        var outputs=new LinkedHashMap<>(delivery.outputs());outputs.put(name,new WorkflowDelivery.Value(value.kind(),encoding.decode(encoding.encode(candidate),JsonNode.class)));
        return new WorkflowDelivery(delivery.summary(),delivery.outcome(),outputs);
    }
    public Map<String,Object> work(WorkflowGraph.Node node,WorkflowDelivery.Inputs inputs) {
        var context=context(node,inputs);boolean review=WorkflowSourceDesign.REVIEW.equals(node.moduleId());
        var fields=review?Map.of("verdict","PASS 或 REVISE；存在问题必须 REVISE","reason","复核理由","checkedPaths","本批全部源码路径", "references","实际读取的源码引用列表","issues","[{sectionKey,detail,recommendation}]，PASS 必须为空")
                :Map.of("title","设计标题","summary","设计概述","sections","[{key,title,markdown,paths,references}]，覆盖本批全部源码","limitations","局限与未知项字符串列表");
        return Map.of("input",context.input(),"files",context.manifest().files().stream().filter(file->context.input().paths().contains(file.path())).toList(),
                "candidateFields",fields,"referenceFields",List.of("path","sha256","startLine","endLine","quote"),"draftInputs",context.drafts().keySet(),
                "instructions","源码正文必须用 read_workflow_input_file，参数 {name:source,path,sha256,startLine:1,lineCount:200}，按 nextLine 读取到 null。引用逐字对应本角色读到的行，且完整读取全部本批源码。复核前用 read_workflow_node_input 完整读取 draft 及所有绑定的相关设计稿到 nextOffset=null；没有阅读的资料不能作为依据。章节 Markdown 不得包含 HTML、图片或自行构造的链接。");
    }
    public SourceDesign.Candidate draft(String requirement,WorkflowDelivery.Input source,WorkflowDelivery.Input value) {
        if(!"NODE".equals(value.source())||!"design".equals(value.outputName()))throw invalid("相关设计稿必须来自专业源码编写节点的已完成交付。");
        var attempt=nodes.attempt(value.attemptId()).orElseThrow(WorkflowCommands::conflict);var node=nodes.node(attempt.nodeRunId()).orElseThrow(WorkflowCommands::conflict);
        if(value.kind()!=WorkflowGraph.DataKind.JSON||!node.requirementId().equals(requirement)||!attempt.state().equals("SUCCEEDED")||nodes.stop(attempt.id()).isEmpty()
                ||!attempt.adapterKey().equals(io.opencode.loopper.runtime.WorkflowModelProfile.ADAPTER)
                ||!WorkflowEncoding.hash(node.definitionJson()).equals(node.definitionSha256())||!WorkflowSourceDesign.AUTHOR.equals(encoding.decode(node.definitionJson(),WorkflowGraph.Node.class).moduleId()))throw invalid("设计稿来源不属于本需求已完成的专业编写节点。");
        var saved=nodes.delivery(attempt.id()).orElseThrow(WorkflowCommands::conflict);
        if(!saved.sha256().equals(value.sha256())||!WorkflowEncoding.hash(saved.contentJson()).equals(saved.sha256()))throw WorkflowCommands.conflict();
        var output=encoding.decode(saved.contentJson(),WorkflowDelivery.class).outputs().get("design");
        if(output==null||!output.content().equals(value.content())||!WorkflowEncoding.hash(attempt.inputsJson()).equals(attempt.inputsSha256()))throw WorkflowCommands.conflict();
        var authorInputs=encoding.decode(attempt.inputsJson(),WorkflowDelivery.Inputs.class);
        if(authorInputs.values().stream().noneMatch(i->i.name().equals("source")&&i.kind()==WorkflowGraph.DataKind.DOCUMENT&&i.content().equals(source.content())&&Objects.equals(i.attemptId(),source.attemptId())))
            throw invalid("设计稿和复核必须读取同一版冻结源码。");
        return encoding.decode(encoding.encode(output.content()),SourceDesign.Candidate.class);
    }
    private void requireInputRead(String attempt,String name,WorkflowDelivery.Input input) {
        String text=encoding.encode(input.content()),hash=WorkflowEncoding.hash(text);int next=0;
        for(var read:reads.inputs(attempt,name)) {
            if(!read.sha256().equals(hash)||read.totalLength()!=text.length()||read.startOffset()>next)break;
            next=Math.max(next,read.endOffset());
        }
        if(next<text.length())throw new BadRequestException("SOURCE_DRAFT_NOT_READ","请完整读取本次绑定的设计稿“"+name+"”，核对跨模块一致性后提交复核。");
    }
    private static BadRequestException invalid(String detail){return new BadRequestException("WORKFLOW_SOURCE_DESIGN_INVALID",detail);}
    private <T> T candidate(String body,Class<T> type) {
        try{return encoding.decode(body,type);}
        catch(JacksonException malformed){throw new BadRequestException("SOURCE_CANDIDATE_JSON_INVALID","请按本节点工作信息提供完整的设计或复核结构，再提交本次交付物。");}
    }
}
