package io.opencode.loopper.service.workflow;

import io.opencode.loopper.service.*;
import io.opencode.loopper.template.*;
import io.opencode.loopper.workflow.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** Explicit input provenance and review policy precede deterministic document rendering. */
@Component
public final class WorkflowDocumentBuilder {
    private final WorkflowSourceRecords sources;
    private final WorkflowSourceDesignContract designs;
    private final WorkflowNodeRuns nodes;
    private final WorkflowEncoding encoding;
    public WorkflowDocumentBuilder(WorkflowSourceRecords sources,WorkflowSourceDesignContract designs,WorkflowNodeRuns nodes,WorkflowEncoding encoding){this.sources=sources;this.designs=designs;this.nodes=nodes;this.encoding=encoding;}
    public record Result(Map<String,String> files,int sourceCount,int draftCount,int reviewedCount,int reviseCount,String policy,Map<String,Object> details) {
        public Result(Map<String,String> files,int sourceCount,int draftCount,int reviewedCount,int reviseCount,String policy){this(files,sourceCount,draftCount,reviewedCount,reviseCount,policy,Map.of());}
    }
    public Result build(WorkflowDocumentStore.Context context) {
        var policy=WorkflowDocument.require(context.node());var inputs=context.inputs();
        var source=inputs.values().stream().filter(value->value.name().equals("source")).findFirst().orElseThrow(WorkflowCommands::conflict);
        var reference=encoding.decode(encoding.encode(source.content()),WorkflowSourceSnapshot.Reference.class);
        var manifest=sources.manifest(context.project(),inputs.requirementId(),source.attemptId(),reference);
        var drafts=new LinkedHashMap<String,SourceDesign.Candidate>();var draftInputs=new HashMap<String,WorkflowDelivery.Input>();
        for(var input:inputs.values())if(input.kind()==WorkflowGraph.DataKind.JSON) {
            var candidate=designs.draft(inputs.requirementId(),source,input);
            if(drafts.putIfAbsent(input.attemptId(),candidate)!=null)throw invalid("同一设计稿不能重复绑定到文档汇总。");
            draftInputs.put(input.attemptId(),input);
        }
        var opinions=new HashMap<String,String>();
        for(var input:inputs.values())if(input.kind()==WorkflowGraph.DataKind.DECISION) {
            var author=review(inputs.requirementId(),source,input,draftInputs);
            if(opinions.putIfAbsent(author,input.content().path("verdict").asString())!=null)throw invalid("每份设计稿请选择一份明确的独立复核，不能混合不同意见。");
        }
        if(policy==WorkflowDocument.ReviewPolicy.REQUIRED&&(opinions.size()!=drafts.size()||opinions.values().stream().anyMatch(value->!value.equals("PASS"))))
            throw new BadRequestException("SOURCE_REVIEW_INCOMPLETE","当前完成策略要求每份设计稿独立复核通过，请绑定对应的有效复核或明确修改完成策略。");
        var documents=new ArrayList<SourceDesignDocuments.Draft>();
        drafts.forEach((id,candidate)->documents.add(new SourceDesignDocuments.Draft(documents.size(),candidate,opinions.getOrDefault(id,"NOT_REVIEWED"))));
        var rendered=SourceDesignDocuments.render(manifest.sha256(),manifest.files(),documents);
        return new Result(rendered.files(),Math.toIntExact(manifest.targetCount()),drafts.size(),(int)opinions.values().stream().filter("PASS"::equals).count(),
                (int)opinions.values().stream().filter("REVISE"::equals).count(),policy.name());
    }
    private String review(String requirement,WorkflowDelivery.Input source,WorkflowDelivery.Input input,Map<String,WorkflowDelivery.Input> drafts) {
        if(!"NODE".equals(input.source())||!"review".equals(input.outputName()))throw invalid("请选择专业复核节点的已完成意见。");
        var attempt=nodes.attempt(input.attemptId());var owner=nodes.requireNode(attempt.nodeRunId());var node=nodes.definition(owner);
        if(!owner.requirementId().equals(requirement)||!attempt.state().equals("SUCCEEDED")||!nodes.hasStop(attempt.id())
                ||!attempt.adapterKey().equals(io.opencode.loopper.runtime.WorkflowModelProfile.ADAPTER)||!WorkflowSourceDesign.REVIEW.equals(node.moduleId()))throw invalid("复核必须来自本需求已经成功收尾的专业节点。");
        var delivery=nodes.delivery(attempt.id());
        if(!delivery.sha256().equals(input.sha256())||!WorkflowEncoding.hash(delivery.contentJson()).equals(delivery.sha256()))throw WorkflowCommands.conflict();
        var output=encoding.decode(delivery.contentJson(),WorkflowDelivery.class).outputs().get("review");
        if(output==null||!output.content().equals(input.content()))throw WorkflowCommands.conflict();
        var frozen=nodes.inputs(attempt);designs.context(node,frozen);
        if(frozen.values().stream().noneMatch(value->value.name().equals("source")&&Objects.equals(source.attemptId(),value.attemptId())&&source.content().equals(value.content())))throw invalid("复核和文档汇总必须使用同一版冻结源码。");
        var draft=frozen.values().stream().filter(value->value.name().equals("draft")).findFirst().orElseThrow(WorkflowCommands::conflict);
        var bound=drafts.get(draft.attemptId());
        if(bound==null||!Objects.equals(bound.sha256(),draft.sha256())||!bound.content().equals(draft.content()))throw invalid("复核意见对应的设计稿没有绑定到本次汇总。");
        if(!Set.of("PASS","REVISE").contains(input.content().path("verdict").asString()))throw WorkflowCommands.conflict();
        return draft.attemptId();
    }
    private static BadRequestException invalid(String message){return new BadRequestException("WORKFLOW_DOCUMENT_INPUT_INVALID",message);}
}
