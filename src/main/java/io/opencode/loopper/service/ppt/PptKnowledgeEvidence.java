package io.opencode.loopper.service.ppt;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.persistence.PptKnowledgeMapper.Evidence;
import io.opencode.loopper.service.NotFoundException;
import io.opencode.loopper.service.assist.AssistRedaction;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Captured reads are committed only while their exact PPT run/message still owns the request. */
@Service
public class PptKnowledgeEvidence {
    private final PptKnowledgeMapper mapper;
    private final PptAgentMapper runs;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    public PptKnowledgeEvidence(PptKnowledgeMapper mapper,PptAgentMapper runs,ObjectMapper json,PlatformTransactionManager manager) {
        this.mapper=mapper;this.runs=runs;this.json=json;this.tx=new TransactionTemplate(manager);
    }
    public Object capture(String document,String runId,Map<String,Object> body,Runnable guard) {
        var run=runs.run(runId).filter(r->r.documentId().equals(document)).orElseThrow(()->PptSupport.bad("PPT_KNOWLEDGE_SCOPE","资料读取不属于当前作品"));
        String encoded=AssistRedaction.text(json.writeValueAsString(body)),digest=PptSupport.hash(encoded);
        if(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>1_048_576)throw PptSupport.bad("PPT_KNOWLEDGE_SIZE","资料片段过大，请缩小读取范围");
        return tx.execute(status->{
            guard.run();var current=runs.run(runId).orElseThrow();
            if(!Objects.equals(current.messageId(),run.messageId())||!Set.of("RUNNING","SENDING","UNKNOWN").contains(current.state()))
                throw PptSupport.conflict("资料读取期间助手轮次已结束，请重新读取");
            var old=mapper.replay(runId,run.messageId(),digest);if(old.isPresent())return response(old.get());
            if(mapper.evidenceCount(runId)>=100) {
                var result=(tools.jackson.databind.node.ObjectNode)json.readTree(encoded);
                result.put("evidenceStatus","LIMIT_REACHED");result.put("notice","本轮已保存 100 条来源证据；可继续读取，但本次不能作为已保存来源引用");return result;
            }
            var evidence=new Evidence("knowledge_"+UUID.randomUUID().toString().replace("-",""),document,runId,run.messageId(),
                    Objects.toString(body.get("sourceId"),""),encoded,digest,Instant.now().toString());
            if(mapper.insertEvidence(evidence)!=1)return response(mapper.replay(runId,run.messageId(),digest).orElseThrow());
            return response(evidence);
        });
    }
    public JsonNode read(String document,String id) {
        return response(mapper.evidence(document,id).orElseThrow(()->new NotFoundException("来源证据不属于此作品")));
    }
    private JsonNode response(Evidence evidence) {
        if(!PptSupport.hash(evidence.bodyJson()).equals(evidence.digest()))throw PptSupport.bad("PPT_EVIDENCE_INTEGRITY","已保存来源证据校验失败，请重新读取来源，保留原记录供核查");
        var result=(tools.jackson.databind.node.ObjectNode)json.readTree(evidence.bodyJson());
        result.put("evidenceId",evidence.id());result.put("collectedAt",evidence.createdAt());
        result.put("evidenceUrl","/api/ppt/documents/"+evidence.documentId()+"/knowledge/evidence/"+evidence.id());
        result.put("citationHint","方案 sourceIds 可使用此 evidenceId；讲稿注明来源名称、位置和采集时间");return result;
    }
}
