package io.opencode.loopper.service.workflow;

import io.opencode.loopper.persistence.WorkflowExecutionRows;
import io.opencode.loopper.template.*;
import io.opencode.loopper.workflow.*;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.*;
import static io.opencode.loopper.service.workflow.WorkflowSnapshotWorkStore.invalid;

/** Accepts semantic claims against this attempt's fixed batch and independently acquired evidence. */
@Component
public final class WorkflowSnapshotContract {
    private final WorkflowSnapshotInputs sources;
    private final WorkflowSnapshotWorkStore inputs;
    private final WorkflowEncoding encoding;
    private final ObjectMapper json;
    private final WorkflowSnapshotReuseProof reuse;
    public WorkflowSnapshotContract(WorkflowSnapshotInputs sources,WorkflowSnapshotWorkStore inputs,WorkflowEncoding encoding,ObjectMapper json,WorkflowSnapshotReuseProof reuse){this.sources=sources;this.inputs=inputs;this.encoding=encoding;this.json=json;this.reuse=reuse;}
    public void validate(WorkflowGraph.Node node,WorkflowDelivery.Inputs snapshot){
        try{sources.read(node,snapshot);}catch(IllegalArgumentException failure){throw invalid(failure.getMessage());}
    }
    public WorkflowDelivery accept(WorkflowExecutionRows.Attempt attempt,WorkflowGraph.Node node,WorkflowDelivery.Inputs snapshot,WorkflowDelivery candidate){
        validate(node,snapshot);var input=inputs.require(attempt.id());
        if(!input.module().equals(node.moduleId()))throw WorkflowCommands.conflict();
        var value=candidate.outputs().get("analysis");if(value==null||value.kind()!=WorkflowGraph.DataKind.JSON)throw invalid("请交付 analysis 分析对象。");
        String body=encoding.encode(value.content());var provenance=reuse.require(snapshot.requirementId(),attempt.id(),input,body);if(provenance.isEmpty())inputs.requireRead(attempt.id());
        if(body.length()>200000)throw invalid("候选超出本批 200000 字符容量。");
        SnapshotReviewClaims.Evidence evidence=(refs,target)->SnapshotReviewClaims.evidence(input.targetSha(),refs,target,
                ref->SnapshotReviewClaims.initialContent(input.batch(),ref).or(()->inputs.content(attempt.id(),ref)));
        final Object accepted;
        try{
            if(WorkflowSnapshotWork.ANALYZE.equals(node.moduleId())){
                var claims=SnapshotReviewClaims.analysis(input.batch(),parse(body,SnapshotReview.Analysis.class),evidence,paths->{throw invalid("V3 不追加模型规划。");});
                String outcome=claims.findings().isEmpty()?"NO_FINDINGS":"HAS_FINDINGS";
                if(!outcome.equals(candidate.outcome()))throw invalid("业务结果须与本批候选问题数量一致。");
                accepted=new WorkflowSnapshotWork.Analysis(1,WorkflowSnapshotWork.ANALYSIS_TYPE,input.source(),input.batchOrdinal(),input.batchCount(),input.batch().units().stream().map(u->new WorkflowSnapshotWork.Location(u.id(),u.path())).toList(),claims,provenance.orElse(null));
            }else{
                var claims=SnapshotReviewClaims.review(input.batch(),parse(body,SnapshotReview.Review.class),input.analysis().findings(),evidence,
                        identity->input.reviews().stream().anyMatch(d->d.result().claims().decisions().stream().anyMatch(c->c.verdict()==SnapshotReview.Verdict.SUPPORTED&&identity.equals(d.attempt()+"/"+c.findingKey()))));
                accepted=new WorkflowSnapshotWork.Review(1,WorkflowSnapshotWork.REVIEW_TYPE,input.source(),input.batch().analysisBatchId(),input.analysis().findings().stream().map(f->new WorkflowSnapshotWork.Problem(f.key(),f.title())).toList(),claims);
            }
        }catch(tools.jackson.core.JacksonException failure){throw invalid("候选字段无法解析，请按工作查询中的结构修正完整候选。");}
        catch(IllegalArgumentException failure){throw invalid(failure.getMessage());}
        var outputs=new LinkedHashMap<>(candidate.outputs());outputs.put("analysis",new WorkflowDelivery.Value(WorkflowGraph.DataKind.JSON,json.valueToTree(accepted)));
        return new WorkflowDelivery(candidate.summary(),candidate.outcome(),outputs);
    }
    private <T>T parse(String body,Class<T> type){return json.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(body);}
    public Map<String,Object> work(String attempt){
        var input=inputs.require(attempt);var result=new LinkedHashMap<String,Object>();result.put("module",input.module());result.put("source",input.source());
        result.put("batchOrdinal",input.batchOrdinal());result.put("batchCount",input.batchCount());result.put("unitCount",input.batch().units().size());
        result.put("targetSha",input.targetSha());result.put("baselineSha",Objects.toString(input.baselineSha(),""));
        result.put("readArgs",Map.of("analysis",true,"offset",0,"limit",12000));result.put("submission",SnapshotReviewClaims.shape(input.batch().phase()));
        result.put("instruction","按 readArgs 完整分页读取本批专用输入。analysis.content 只填写候选结构，来源身份由程序绑定。初始完整代码可直接引用，其他引用须来自本角色的按行读取；搜索结果不是读取证据。V3 不追加 supplements；无问题 coverage 简述最多 160 字，可 evidence=[]，缺少初始代码或实际证据须写局限。分析 outcome 按问题数量填写 HAS_FINDINGS 或 NO_FINDINGS。静态审查不能证明测试通过。");
        result.put("relatedReads",Map.of("maximumDistinctRequests",12,"listArgs",Map.of("name","source","version",input.targetSha(),"limit",50),
                "readArgs",Map.of("name","source","version",input.targetSha(),"path","从目录选择","blobSha","从目录选择","startLine",1,"lineCount",200),
                "searchArgs",Map.of("name","source","version",input.targetSha(),"path","从目录选择","blobSha","从目录选择","query","具体文字","afterLine",0),
                "instruction","使用现有文件列表/读取工具；最多 12 个不同目录、单文件搜索或按行请求，同参数重放不重复计数。仅可用固定目标/基线版本；每次最多 200 行、32000 字符。只处理本批与显式依赖，不遍历其他任务或分组。达到边界时保留未确认局限。"));
        return result;
    }
    public WorkflowInputPages.Page page(String attempt,Map<String,Object> args){
        if(!Boolean.TRUE.equals(args.get("analysis"))||!Set.of("analysis","offset","limit").containsAll(args.keySet()))throw invalid("请使用 analysis、offset 与 limit 读取本批输入。");
        return inputs.page(attempt,WorkflowModelTools.integer(args,"offset",0,0,WorkflowSnapshotWork.MAX_INPUT_BYTES),WorkflowModelTools.integer(args,"limit",12000,1,12000));
    }
}
