package io.opencode.loopper.service.ppt;

import io.opencode.loopper.domain.*;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.lifecycle.LifecycleTransitionService.Subject;
import io.opencode.loopper.persistence.PptMapper;
import io.opencode.loopper.persistence.PptRows.*;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Short transactions, revision CAS and audit. Callers perform layout and I/O before entry. */
@Service
public class PptPersistence {
    private final PptMapper mapper;
    private final LifecycleTransitionService lifecycle;
    private final ObjectMapper json;
    private final io.opencode.loopper.persistence.PptGenerationMapper generations;
    public PptPersistence(PptMapper mapper,LifecycleTransitionService lifecycle,ObjectMapper json,io.opencode.loopper.persistence.PptGenerationMapper generations) {
        this.mapper=mapper;this.lifecycle=lifecycle;this.json=json;this.generations=generations;
    }
    public void assertManualAdmission(String id) {
        if(generations.active(id).isPresent())throw PptSupport.conflict("自动生成期间请先停止生成，再手工修改方案或页面");
    }
    @Transactional
    public void create(Document row,Revision revision) {
        lifecycle.create(subject(row),row.phase(),Map.of(),()->mapper.insert(row),()->PptSupport.conflict("作品已创建，请重新读取"));
        if (mapper.insertRevision(revision)!=1) throw PptSupport.conflict("作品版本保存失败");
    }
    @Transactional
    public JsonNode edit(Document base,Revision next,String title,String key,String digest,JsonNode result,Runnable guard) {
        var replay=mapper.receipt(base.id(),key);
        if(replay.isPresent()) return replay(replay.get(),digest);
        guard.run();
        var current=mapper.document(base.id()).orElseThrow();
        if(current.version()!=base.version()) throw PptSupport.conflict("作品已修改，请读取最新内容后重试");
        lifecycle.mutateWithoutTransition(()->mapper.edit(base.id(),base.version(),next.revision(),title,next.createdAt()),()->PptSupport.conflict("作品已修改，请重新读取"));
        if(mapper.insertRevision(next)!=1) throw PptSupport.conflict("版本保存失败");
        if("EXPORTED".equals(base.phase())) phase(mapper.document(base.id()).orElseThrow(),"REVIEW");
        receipt(base.id(),key,digest,result);
        return result;
    }
    @Transactional
    public JsonNode action(Document base,String phase,Boolean archived,String key,String digest,JsonNode result,Runnable guard) {
        var replay=mapper.receipt(base.id(),key);
        if(replay.isPresent()) return replay(replay.get(),digest);
        guard.run();
        if(mapper.document(base.id()).orElseThrow().version()!=base.version()) throw PptSupport.conflict("作品状态已变化，请重新读取");
        if(archived!=null) lifecycle.mutateWithoutTransition(()->mapper.archive(base.id(),base.version(),archived,Instant.now().toString()),()->PptSupport.conflict("归档状态已变化"));
        else if(phase!=null&&!phase.equals(base.phase())) phase(base,phase);
        receipt(base.id(),key,digest,result);
        return result;
    }
    public void phase(Document row,String phase) {
        if(row.phase().equals(phase)) return;
        lifecycle.transition(subject(row),row.phase(),phase,null,Map.of(),
                ()->mapper.phase(row.id(),row.version(),phase,Instant.now().toString()),()->PptSupport.conflict("作品状态已变化"));
    }
    public JsonNode replay(Receipt receipt,String digest) {
        if(!receipt.digest().equals(digest)) throw PptSupport.conflict("同一请求标识已用于不同操作，请重新发起");
        return json.readTree(receipt.resultJson());
    }
    private void receipt(String id,String key,String digest,JsonNode result) {
        if(mapper.insertReceipt(new Receipt(id,key,digest,json.writeValueAsString(result),Instant.now().toString()))!=1) throw PptSupport.conflict("操作回执保存失败");
    }
    private Subject subject(Document row) { return new Subject(LifecycleMachineType.PPT_DOCUMENT,row.id(),LifecycleScopeType.PPT_DOCUMENT,row.id()); }
}
