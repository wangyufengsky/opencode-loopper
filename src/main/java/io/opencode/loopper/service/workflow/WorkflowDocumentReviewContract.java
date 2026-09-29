package io.opencode.loopper.service.workflow;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.service.*;
import io.opencode.loopper.template.*;
import io.opencode.loopper.workflow.*;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import static io.opencode.loopper.service.workflow.WorkflowDocumentReviewContext.invalid;

/** Shared professional semantics consume this attempt's fixed lineage and its own read receipts. */
@Component
public final class WorkflowDocumentReviewContract {
    private final WorkflowDocumentReviewContext contexts;
    private final WorkflowDocumentReadMapper reads;
    private final WorkflowExecutionMapper nodes;
    private final WorkflowEncoding encoding;
    public WorkflowDocumentReviewContract(WorkflowDocumentReviewContext contexts,WorkflowDocumentReadMapper reads,WorkflowExecutionMapper nodes,WorkflowEncoding encoding){this.contexts=contexts;this.reads=reads;this.nodes=nodes;this.encoding=encoding;}
    public WorkflowDocumentReviewContext.Context context(WorkflowGraph.Node node,WorkflowDelivery.Inputs inputs) {
        var context=contexts.resolve(node,inputs);
        if(WorkflowDocumentReview.REVIEW.equals(node.moduleId()))for(var value:inputs.values())if(value.kind()==WorkflowGraph.DataKind.JSON)draft(context,value,"draft".equals(value.name()));
        return context;
    }
    public WorkflowDelivery accept(WorkflowExecutionRows.Attempt attempt,WorkflowGraph.Node node,WorkflowDelivery.Inputs inputs,WorkflowDelivery delivery) {
        var context=context(node,inputs);boolean review=WorkflowDocumentReview.REVIEW.equals(node.moduleId());String name=review?"review":"assessment";
        var output=delivery.outputs().get(name);if(output==null)throw invalid("请提供完整的专业评审交付。");
        String body=encoding.encode(output.content());if(body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>256*1024)throw invalid("单批评审或复核最多 256 KiB，请精简重复正文。");
        var rules=rules(context,attempt.id());var policy=new DirectDocumentAssessmentRules.Context(context.ordinal(),context.codeManifest().commitSha(),1,context.assigned().stream().map(WorkflowDocumentReviewContext.Section::source).toList());
        Object candidate;
        if(review) {
            for(var input:inputs.values())if(input.kind()==WorkflowGraph.DataKind.JSON)requireInputRead(attempt.id(),input);
            var original=inputs.values().stream().filter(value->value.name().equals("draft")).findFirst().orElseThrow(WorkflowCommands::conflict);
            var result=rules.review(policy,candidate(encoding.encode(original.content()),DirectDocumentAssessment.Candidate.class),candidate(body,DirectDocumentAssessment.Review.class));
            if(!Objects.equals(delivery.outcome(),result.approved()?"PASS":"REVISE"))throw invalid("节点结果必须与独立复核的通过或返修意见一致。");
            candidate=result;
        } else candidate=rules.assessment(policy,candidate(body,DirectDocumentAssessment.Candidate.class));
        var outputs=new LinkedHashMap<>(delivery.outputs());outputs.put(name,new WorkflowDelivery.Value(output.kind(),encoding.decode(encoding.encode(candidate),JsonNode.class)));
        return new WorkflowDelivery(delivery.summary(),delivery.outcome(),outputs);
    }
    private DirectDocumentAssessmentRules rules(WorkflowDocumentReviewContext.Context context,String attempt) {
        var files=new HashMap<String,WorkflowRepositorySnapshot.File>();context.codeManifest().files().forEach(file->files.put(file.path(),file));
        var code=new DocumentAssessmentRules(new DocumentAssessmentRules.Evidence() {
            public boolean contains(String path){return files.containsKey(path);}
            public Optional<DocumentAssessmentRules.Read> read(String path,int start,int end) {
                var file=files.get(path);if(file==null||file.limitation()!=null)return Optional.empty();
                return reads.evidence(attempt,path,start,end).filter(row->row.contentSha().equals(file.blobSha()))
                    .map(row->new DocumentAssessmentRules.Read(row.contentSha(),row.startLine(),row.content()));
            }
        });
        var sections=new HashMap<DirectDocumentAssessment.Source,WorkflowDocumentReviewContext.Section>();
        var cached=new HashMap<DirectDocumentAssessment.Source,DirectDocumentAssessmentRules.Section>();
        var verified=new HashSet<DirectDocumentAssessment.Source>();
        return new DirectDocumentAssessmentRules((ref,requireRead)->{
            var section=sections.computeIfAbsent(ref,key->contexts.section(context,key));
            var original=cached.computeIfAbsent(ref,key->new DirectDocumentAssessmentRules.Section(contexts.original(context,section).content()));
            // A prior non-evidence projection never substitutes for this role's full original read.
            if(requireRead&&!verified.contains(ref)){requireDocumentRead(attempt,section,original.content());verified.add(ref);}
            return original;
        },code);
    }
    private void requireDocumentRead(String attempt,WorkflowDocumentReviewContext.Section section,String text) {
        int total=(int)text.lines().count(),next=1;
        for(var row:reads.reads(attempt,"documents",section.path())) {
            if(!row.contentSha().equals(section.sha256())||row.totalLines()!=total||row.startLine()>next)break;
            next=Math.max(next,row.endLine()+1);
        }
        if(next<=total)throw new BadRequestException("WORKFLOW_DOCUMENT_SOURCE_NOT_READ","请用当前节点完整读取原文章节 "+section.source().fileId()+" / "+section.source().section()+"，其他节点的阅读不构成本次证据。");
    }
    private void requireInputRead(String attempt,WorkflowDelivery.Input input) {
        String text=encoding.encode(input.content()),hash=WorkflowEncoding.hash(text);int next=0;
        for(var row:reads.inputs(attempt,input.name())) {
            if(!row.sha256().equals(hash)||row.totalLength()!=text.length()||row.startOffset()>next)break;
            next=Math.max(next,row.endOffset());
        }
        if(next<text.length())throw new BadRequestException("WORKFLOW_DOCUMENT_DRAFT_NOT_READ","请完整读取本次绑定的评审稿“"+input.name()+"”，检查跨批次矛盾后提交复核。");
    }
    public WorkflowDocumentReviewContext.Context draft(WorkflowDocumentReviewContext.Context context,WorkflowDelivery.Input input,boolean primary) {
        if(!"NODE".equals(input.source())||!"assessment".equals(input.outputName())||input.attemptId()==null)throw invalid("复核稿必须来自本需求已完成的专业原文评审节点。");
        var attempt=nodes.attempt(input.attemptId()).orElseThrow(WorkflowCommands::conflict);var owner=nodes.node(attempt.nodeRunId()).orElseThrow(WorkflowCommands::conflict);
        if(!owner.requirementId().equals(context.requirement())||!attempt.state().equals("SUCCEEDED")||nodes.stop(attempt.id()).isEmpty()
            ||!attempt.adapterKey().equals(io.opencode.loopper.runtime.WorkflowModelProfile.ADAPTER)||!WorkflowEncoding.hash(owner.definitionJson()).equals(owner.definitionSha256()))throw invalid("评审稿未完成收尾或不属于本需求。");
        var definition=encoding.decode(owner.definitionJson(),WorkflowGraph.Node.class);
        if(!WorkflowDocumentReview.AUTHOR.equals(definition.moduleId())||!WorkflowEncoding.hash(attempt.inputsJson()).equals(attempt.inputsSha256()))throw invalid("请选择专业原文评审的固定结果。");
        var producer=contexts.resolve(definition,encoding.decode(attempt.inputsJson(),WorkflowDelivery.Inputs.class));
        if(!producer.documents().equals(context.documents())||!producer.code().equals(context.code())||!producer.codeProducer().equals(context.codeProducer()))throw invalid("评审与复核须使用同一份原文和同一生产者的固定代码。");
        if(primary&&(producer.ordinal()!=context.ordinal()||!new HashSet<>(producer.assigned()).equals(new HashSet<>(context.assigned()))))throw invalid("本批评审稿与复核章节或批次序号不一致。");
        var saved=nodes.delivery(attempt.id()).orElseThrow(WorkflowCommands::conflict);
        if(!saved.sha256().equals(input.sha256())||!WorkflowEncoding.hash(saved.contentJson()).equals(saved.sha256()))throw WorkflowCommands.conflict();
        var output=encoding.decode(saved.contentJson(),WorkflowDelivery.class).outputs().get("assessment");
        if(output==null||output.kind()!=WorkflowGraph.DataKind.JSON||!output.content().equals(input.content()))throw WorkflowCommands.conflict();
        return producer;
    }
    public Map<String,Object> work(WorkflowGraph.Node node,WorkflowDelivery.Inputs inputs) {
        var context=context(node,inputs);boolean review=WorkflowDocumentReview.REVIEW.equals(node.moduleId());
        var result=new LinkedHashMap<String,Object>();result.put("snapshotSha",context.codeManifest().commitSha());result.put("sections",context.assigned());
        result.put("requirementKeyRange",List.of("RQ-"+(context.ordinal()*256+1),"RQ-"+((context.ordinal()+1)*256)));result.put("testStatus","NOT_RUN_STATIC_REVIEW");
        result.put("documentLimitations",context.documentManifest().originals().stream().map(o->Map.of("filename",o.filename(),"limitations",o.limitations())).toList());
        result.put("draftInputs",inputs.values().stream().filter(i->i.kind()==WorkflowGraph.DataKind.JSON).map(WorkflowDelivery.Input::name).toList());
        result.put("candidateFields",review?Map.of("snapshotSha","填 null，由程序绑定","approved","布尔值；有 corrections 时必须为 false","reviewedRequirementKeys","本批全部 RQ 编号","reviewedFindingKeys","本批全部问题编号","checkedSections","[{fileId,section}]，包括分配、补充引用与跳过章节","corrections","[{requirementKey,findingKey,source:{fileId,section},detail}]，至少指明一种来源")
            :Map.of("snapshotSha","填 null，由程序绑定","entries","[{title,statement,sources:[{fileId,section}],issues:[],assessment:{requirementKey,conclusion,rationale,evidence:[],checkedPaths:[],missingEntryEvidence,testSourceCoverage,limitations:[]}}]","findings","[{key,kind,severity,title,trigger,impact,recommendation,requirementKeys:[],evidence:[],rootCauseKey}]","skippedSections","[{source:{fileId,section},reason}]，无要求的章节必须说明理由","limitations","局限与未知项字符串列表"));
        result.put("codeReferenceFields",List.of("path","blobSha","startLine","endLine","quote"));
        result.put("conclusions",List.of("SATISFIED","PARTIAL","INCORRECT","NOT_IMPLEMENTED","UNDETERMINED"));
        result.put("findingKinds",List.of("DEFECT","VALIDATION_GAP","SUGGESTION"));result.put("severities",List.of("CRITICAL","HIGH","MEDIUM","LOW","INFO"));
        result.put("instructions","原文和代码均使用 read_workflow_input_file。原文参数 {name:documents,path,sha256,startLine:1,lineCount:200}，代码参数 {name:code,path,blobSha,startLine:1,lineCount:200}；哈希使用清单原值。原文按 nextLine 完整读完。代码引用必须逐字对应当前角色读到的行；仅搜索无结果不能证明未实现。有业务歧义时保留 issues 并用 UNDETERMINED。静态评审不运行测试，不把源码测试覆盖描述成测试已通过。复核用 read_workflow_node_input 完整读取 draft 与全部关联稿，再独立重读原文、引用代码、跳过章节并检查跨批矛盾。所有分配章节必须有结论来源或真实跳过说明。");
        return result;
    }
    private <T>T candidate(String body,Class<T> type) {
        try{return encoding.decode(body,type);}catch(tools.jackson.core.JacksonException invalid){throw invalid("评审结构无法解析，请按本节点工作信息提交完整结果。");}
    }
}
