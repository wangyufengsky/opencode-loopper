package io.opencode.loopper.service.ppt.generation;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.persistence.PptGenerationRows.Generation;
import io.opencode.loopper.service.ppt.*;
import io.opencode.loopper.ppt.PptModel.*;
import io.opencode.loopper.service.ppt.agent.PptFailurePolicy;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Page evidence and consecutive no-progress recovery have a budget independent of agent/tool steps. */
@Service
public class PptGenerationRecovery {
    private final PptRecoveryMapper mapper;
    private final PptGenerationPersistence persistence;
    private final PptDocuments documents;
    private final PptChecks checks;
    private final ObjectMapper json;
    public PptGenerationRecovery(PptRecoveryMapper mapper, PptGenerationPersistence persistence,
            PptDocuments documents, PptChecks checks, ObjectMapper json) {
        this.mapper=mapper;this.persistence=persistence;this.documents=documents;this.checks=checks;this.json=json;
    }
    public boolean pending(Generation row) {
        var saved=mapper.recovery(row.id());
        if(saved.isEmpty()||saved.get().retryAt()==null)return false;
        persistence.continueAutomatically(row);return true;
    }
    public boolean modelFailed(Generation row) {
        var failure=mapper.failure(row.runId());
        if(failure.isEmpty()||!Set.of("TRANSIENT","OUTPUT_LIMIT").contains(failure.get().category()))return false;
        return schedule(row,failure.get().errorCode(),PptFailurePolicy.classify(failure.get().errorCode(),failure.get().detail()).message());
    }
    public boolean repair(Generation row, RuntimeException failure) {
        String code=failure instanceof io.opencode.loopper.service.BadRequestException bad?bad.code():"";
        if(!Set.of("PPT_EMPTY","PPT_EMPTY_PAGE","PPT_PAGE_MISSING","PPT_LAYOUT_INVALID","PPT_PLAN_INVALID",
                "PPT_PLAN_INCOMPLETE","PPT_AUTOMATIC_PLAN_INVALID","PPT_PLAN_REQUIRED").contains(code))return false;
        return schedule(row,code,PptFailurePolicy.safe(failure.getMessage(),1200));
    }
    private boolean schedule(Generation row,String code,String reason) {
        var prior=mapper.recovery(row.id());if(prior.isEmpty())return false;
        long revision=documents.get(row.documentId()).revision();
        var snapshot=documents.snapshot(row.documentId(),revision);
        var deck=documents.deck(row.documentId(),revision);
        var issues=checks.check(row.documentId(),revision).issues();
        var scope=json.readTree(row.scopeJson());
        if(!scope.path("kind").asText().equals("DOCUMENT")&&issues.stream().anyMatch(i->"ERROR".equals(i.severity())&&!allowed(deck,scope,i))) {
            persistence.state(row,PptGenerationState.FAILED,"整份检查发现本次修改范围外的问题，请停止后选择对应范围修复；不会自动扩大修改权限");return true;
        }
        var checkpoint=json.createObjectNode().put("revision",revision).put("step",row.step()).put("reason",reason);
        checkpoint.put("instruction","保留通过检查的页面。先读取当前方案和问题页，分小批保存，只补缺页或修复问题对象；不要重建整份，不执行历史残缺工具文本。最后整份检查后结束本轮。");
        Set<String> bad=new HashSet<>();issues.stream().filter(i->"ERROR".equals(i.severity())).forEach(i->bad.add(i.slideId()));
        var present=deck.slides().stream().filter(s->!s.elements().isEmpty()).map(Slide::id).toList();
        List<String> missing=new ArrayList<>();
        if(row.mode().equals("CREATE"))for(var slide:json.readTree(snapshot.planJson()).path("slides"))if(!present.contains(slide.path("id").asText()))missing.add(slide.path("id").asText());
        checkpoint.set("missingPages",json.valueToTree(missing));
        checkpoint.set("completedPages",json.valueToTree(present.stream().filter(id->!bad.contains(id)).toList()));
        checkpoint.set("emptyPages",json.valueToTree(deck.slides().stream().filter(s->s.elements().isEmpty()).map(Slide::id).toList()));
        checkpoint.set("issues",json.valueToTree(issues.stream().limit(100).toList()));
        checkpoint.put("issueCount",issues.size());
        String fingerprint=PptSupport.hash(row.step()+snapshot.deckJson()+snapshot.planJson());
        int failures=prior.get().fingerprint().equals(fingerprint)?prior.get().failures()+1:1;
        if(failures>3) {
            persistence.state(row,PptGenerationState.FAILED,reason+"；连续三次恢复没有内容进展，请调整要求或检查模型后继续");return true;
        }
        long delay=failures==1?2:failures==2?8:30;
        var recovery=new PptRecoveryMapper.Recovery(row.id(),row.attempt(),revision,fingerprint,failures,
                Instant.now().plusSeconds(delay).toString(),json.writeValueAsString(checkpoint),code);
        persistence.scheduleRecovery(row,recovery,reason+"；"+delay+" 秒后自动继续（连续无进展恢复 "+failures+"/3）");return true;
    }
    private boolean allowed(Deck deck,tools.jackson.databind.JsonNode scope,Issue issue) {
        String kind=scope.path("kind").asText();
        if(kind.equals("SECTION"))return deck.slides().stream().anyMatch(s->s.id().equals(issue.slideId())&&s.section().equals(scope.path("section").asText()));
        return scope.path("slideId").asText().equals(issue.slideId())&&(!kind.equals("ELEMENT")||scope.path("elementId").asText().equals(issue.elementId()));
    }
}
