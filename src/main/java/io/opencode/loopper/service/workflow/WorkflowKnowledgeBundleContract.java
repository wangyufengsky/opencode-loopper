package io.opencode.loopper.service.workflow;

import io.opencode.loopper.persistence.WorkflowExecutionRows.Attempt;
import io.opencode.loopper.persistence.WorkflowKnowledgeMapper;
import io.opencode.loopper.service.BadRequestException;
import io.opencode.loopper.service.assist.AssistRedaction;
import io.opencode.loopper.workflow.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** DB-only compilation copies explicitly selected receipts into an immutable public node delivery. */
@Component
public final class WorkflowKnowledgeBundleContract {
    private final WorkflowKnowledgeMapper mapper;
    private final ObjectMapper json;
    public WorkflowKnowledgeBundleContract(WorkflowKnowledgeMapper mapper,ObjectMapper json){this.mapper=mapper;this.json=json;}
    public WorkflowDelivery accept(Attempt attempt,WorkflowGraph.Node node,WorkflowDelivery delivery) {
        WorkflowKnowledgeBundle.require(node);
        var value=delivery.outputs().get("evidence").content();
        if(!value.isObject()||!value.propertyNames().equals(Set.of("version","references","limitations"))
                ||!value.path("version").isIntegralNumber()||!value.path("version").canConvertToInt()||value.path("version").asInt()!=1
                ||!value.path("references").isArray()||value.path("references").size()>12
                ||!value.path("limitations").isArray()||value.path("limitations").size()>32)throw invalid("来源选择格式不完整，请按工作信息提交真实引用和局限。");
        var limitations=new ArrayList<String>();
        for(var item:value.get("limitations")) {
            if(!item.isString()||item.asString().isBlank()||item.asString().length()>2000)throw invalid("每项资料局限须为 1–2000 字的说明。");
            limitations.add(item.asString());
        }
        var entries=new ArrayList<WorkflowKnowledgeBundle.Entry>();var seen=new HashSet<String>();int bytes=0;
        for(var reference:value.get("references")) {
            if(!reference.isString()||!reference.asString().matches("call:[A-Za-z0-9_-]{1,100}")||!seen.add(reference.asString()))
                throw invalid("请选择本次执行实际返回且不重复的 call: 引用。");
            var entry=entry(attempt,reference.asString());bytes+=json.writeValueAsBytes(entry).length;
            if(bytes>128*1024)throw invalid("所选证据超过交付上限，请缩小原文读取范围后重新选择。");
            entries.add(entry);
        }
        if(entries.isEmpty()&&limitations.isEmpty())throw invalid("没有保存证据时，请说明未查到或未能读取的资料范围。");
        var outputs=new LinkedHashMap<>(delivery.outputs());
        outputs.put("evidence",new WorkflowDelivery.Value(WorkflowGraph.DataKind.JSON,json.valueToTree(
                new WorkflowKnowledgeBundle.Frozen(1,WorkflowKnowledgeBundle.TYPE,List.copyOf(entries),List.copyOf(limitations)))));
        return new WorkflowDelivery(delivery.summary(),delivery.outcome(),outputs);
    }
    private WorkflowKnowledgeBundle.Entry entry(Attempt attempt,String reference) {
        var row=mapper.nodeEvidence(attempt.externalSessionId(),"WORKFLOW_ATTEMPT:"+attempt.id(),reference.substring(5));
        if(row==null||row.resultJson()==null||row.resultJson().getBytes(StandardCharsets.UTF_8).length>128*1024)
            throw invalid("引用不属于本次成功读取记录，或正文过大；请重新读取所需范围并选择。");
        try {
            var content=json.readTree(AssistRedaction.text(row.resultJson()));
            if(content==null||!content.isObject())throw invalid("保存的证据格式不完整，请检查原读取记录。");
            return new WorkflowKnowledgeBundle.Entry(reference,row.toolName(),row.createdAt(),WorkflowEncoding.hash(json.writeValueAsString(content)),content);
        }catch(tools.jackson.core.JacksonException malformed){throw invalid("保存的证据格式不完整，请检查原读取记录。");}
    }
    private static BadRequestException invalid(String message){return new BadRequestException("WORKFLOW_KNOWLEDGE_BUNDLE_INVALID",message);}
}
