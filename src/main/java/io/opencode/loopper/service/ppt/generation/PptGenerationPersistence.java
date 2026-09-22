package io.opencode.loopper.service.ppt.generation;

import io.opencode.loopper.domain.*;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.persistence.PptAgentRows.Run;
import io.opencode.loopper.persistence.PptGenerationRows.*;
import io.opencode.loopper.service.NotFoundException;
import io.opencode.loopper.service.ppt.PptSupport;
import io.opencode.loopper.service.ppt.agent.PptAgentWorkflowGate;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Short transactions hold the automatic continuation authority; no model or file I/O. */
@Service
public class PptGenerationPersistence implements PptAgentWorkflowGate {
    private final PptGenerationMapper mapper;
    private final PptAgentMapper agents;
    private final PptMapper documents;
    private final LifecycleTransitionService lifecycle;
    private final ObjectMapper json;
    private final io.opencode.loopper.service.ppt.PptJobPersistence jobs;
    public PptGenerationPersistence(PptGenerationMapper mapper,PptAgentMapper agents,PptMapper documents,
            LifecycleTransitionService lifecycle,ObjectMapper json,io.opencode.loopper.service.ppt.PptJobPersistence jobs) {
        this.mapper=mapper;this.agents=agents;this.documents=documents;this.lifecycle=lifecycle;this.json=json;this.jobs=jobs;
    }
    public Generation require(String id) { return mapper.get(id).orElseThrow(()->new NotFoundException("自动生成请求不存在")); }
    public Generation replay(String document,String key,String sha,String kind) {
        var old=mapper.request(document,key);if(old.isEmpty())return null;
        if(!old.get().inputSha().equals(sha)||!old.get().kind().equals(kind))throw PptSupport.conflict("同一请求标识不能用于不同生成请求");
        return require(old.get().generationId());
    }
    @Transactional public Generation begin(Generation desired) {
        String kind=desired.mode().equals("REVISE")?"REVISE":"GENERATE";
        var old=replay(desired.documentId(),desired.idempotencyKey(),desired.inputSha(),kind);if(old!=null)return old;
        assertManualAdmission(desired.documentId());noWriter(desired.documentId());
        var doc=document(desired.documentId(),desired.sourceRevision());
        if(desired.mode().equals("CREATE")?!Set.of("BRIEFING","DIRECTION","DESIGN").contains(doc.phase()):!Set.of("REVIEW","EXPORTED").contains(doc.phase()))
            throw PptSupport.bad("PPT_GENERATION_PHASE","当前阶段不适用于此生成请求，请刷新状态");
        lifecycle.create(subject(desired),desired.state(),Map.of("authorizedBy","GENERATE","revision",desired.sourceRevision()),
                ()->mapper.insert(desired),()->PptSupport.conflict("作品已有自动生成请求"));
        receipt(desired,desired.idempotencyKey(),desired.inputSha(),kind);return require(desired.id());
    }
    @Transactional public Generation resume(Generation observed,String key,String sha,long revision) {
        var old=replay(observed.documentId(),key,sha,"RESUME");if(old!=null)return old;
        var row=require(observed.id());
        if(!Set.of("STOPPED","FAILED").contains(row.state()))throw PptSupport.conflict("当前生成尚未停止，或已经完成");
        if(mapper.latest(row.documentId()).filter(v->v.id().equals(row.id())).isEmpty())throw PptSupport.conflict("该生成已由新请求替代");
        noWriter(row.documentId());assertManualAdmission(row.documentId());var doc=document(row.documentId(),revision);
        if(row.mode().equals("REVISE")&&!Set.of("REVIEW","EXPORTED").contains(doc.phase()))throw PptSupport.conflict("作品阶段已变化，请发送新请求");
        if(Set.of("PREVIEW","EXPORT").contains(row.step())&&Objects.equals(row.outputRevision(),revision)&&row.jobId()!=null) {
            var job=documents.job(row.documentId(),row.jobId()).orElseThrow();
            if(!job.state().equals("CANCELLED")) {
                if(job.state().equals("FAILED"))jobs.state(job,"PREPARED",job.completed(),"继续原版本，保留成功页面");
                mutate(()->mapper.resumeOutput(row.id(),row.version(),now()));
                var result=state(require(row.id()),PptGenerationState.valueOf(row.step()),"继续原版本输出");
                receipt(result,key,sha,"RESUME");return result;
            }
        }
        String step=Set.of("BRIEFING","DIRECTION","DESIGN").contains(doc.phase())?"PLANNING":
                Set.of("REVIEW","EXPORTED").contains(doc.phase())&&Set.of("PREVIEW","EXPORT").contains(row.step())?"PREVIEW":"PRODUCING";
        int attempt=row.attempt()+1;
        mutate(()->mapper.step(row.id(),row.version(),step,attempt,revision,agentKey(row.id(),attempt,step),null,
                step.equals("PREVIEW")?revision:null,now()));
        var result=state(require(row.id()),PptGenerationState.valueOf(step),"已恢复，将继续已保存内容");
        receipt(result,key,sha,"RESUME");return result;
    }
    @Transactional public Generation state(Generation row,PptGenerationState next,String detail) {
        if(row.state().equals(next.name())) {
            if(!row.detail().equals(detail))mutate(()->mapper.detail(row.id(),row.version(),detail,now()));
        } else lifecycle.transition(subject(row),row.state(),next.name(),null,Map.of("step",row.step()),
                ()->mapper.state(row.id(),row.version(),next.name(),detail,now()),()->PptSupport.conflict("生成状态已变化"));
        return require(row.id());
    }
    @Transactional public Generation bindRun(Generation row,Run run) {
        current(row);if(!run.documentId().equals(row.documentId())||!run.idempotencyKey().equals(row.agentKey()))throw PptSupport.conflict("生成运行身份不匹配");
        if(row.runId()==null)mutate(()->mapper.bindRun(row.id(),row.version(),run.id(),now()));
        return require(row.id());
    }
    @Transactional public Generation bindJob(Generation row,PptRows.Job job) {
        current(row);if(!job.documentId().equals(row.documentId())||!Objects.equals(row.outputRevision(),job.revision()))throw PptSupport.conflict("生成作业版本不匹配");
        if(row.jobId()==null)mutate(()->mapper.bindJob(row.id(),row.version(),job.id(),now()));return require(row.id());
    }
    @Transactional public Generation freeze(Generation row,Run run) {
        completed(row,run);long revision=run.sourceRevision();Long saved=mapper.savedRevision(run.id());
        if(saved!=null)revision=Math.max(revision,saved);
        document(row.documentId(),revision);
        if(row.outputRevision()==null) {
            long frozen=revision;mutate(()->mapper.freeze(row.id(),row.version(),frozen,now()));
        }else if(row.outputRevision()!=revision)throw PptSupport.conflict("生成结果版本已变化");
        return require(row.id());
    }
    @Transactional public Generation advance(Generation row,PptGenerationState next) {
        current(row);noWriter(row.documentId());document(row.documentId(),Objects.requireNonNull(row.outputRevision()));
        String step=next.name();Long output=next==PptGenerationState.PRODUCING?null:row.outputRevision();
        mutate(()->mapper.step(row.id(),row.version(),step,row.attempt(),row.outputRevision(),
                agentKey(row.id(),row.attempt(),step),next==PptGenerationState.EXPORT?row.jobId():null,output,now()));
        return state(require(row.id()),next,"");
    }
    @Transactional public Generation completeOutput(Generation row,String exportId) {
        guard(row);noWriter(row.documentId());
        if(!row.step().equals("EXPORT")||!Objects.equals(row.jobId(),exportId)||row.previewJobId()==null)throw PptSupport.conflict("输出身份不完整");
        outputProof(row,row.previewJobId(),"PREVIEW");outputProof(row,exportId,"EXPORT");
        return state(row,PptGenerationState.COMPLETED,"预览与 PPTX 已生成，可以继续提出修改意见");
    }
    private void outputProof(Generation row,String jobId,String kind) {
        var job=documents.job(row.documentId(),jobId).orElseThrow();
        if(!job.state().equals("COMPLETED")||!job.kind().equals(kind)||!Objects.equals(row.outputRevision(),job.revision())
                ||job.completed()!=job.total()||documents.artifacts(row.documentId(),jobId).size()!=job.total())throw PptSupport.conflict("预览和导出尚未全部完成");
    }
    public void guard(Generation row) {
        current(row);if(row.outputRevision()!=null)document(row.documentId(),row.outputRevision());
    }
    public void completed(Generation row,Run run) {
        current(row);var latest=agents.run(run.id()).orElseThrow();
        if(!latest.documentId().equals(row.documentId())||!latest.idempotencyKey().equals(row.agentKey())
                ||!latest.state().equals("COMPLETED")||latest.stopProof()==null||latest.stopProof().isBlank())throw PptSupport.conflict("模型尚未证明完成");
        noWriter(row.documentId());
    }
    public void noWriter(String document) {
        if(agents.activeCount(document)!=0)throw PptSupport.conflict("PPT 助手尚未安全停止");
    }
    @Override public void assertManualAdmission(String document) {
        if(mapper.active(document).isPresent())throw PptSupport.conflict("自动生成仍在进行，请先回答问题或停止生成后发送新请求");
    }
    @Override public void validateAutomatic(String document,String key,Authorization auth) {
        var row=require(auth.generationId());
        if(!row.documentId().equals(document)||row.attempt()!=auth.attempt()||!row.agentKey().equals(key)||!row.step().equals(auth.step())||!row.mode().equals(auth.mode()))throw PptSupport.conflict("自动生成授权已经变化");
        current(row);
    }
    @Override public void validateRun(Run run) {
        var auth=json.readTree(run.contextJson()).get("generationAuthorization");if(auth==null)return;
        validateAutomatic(run.documentId(),run.idempotencyKey(),json.treeToValue(auth,Authorization.class));
    }
    @Override public List<Answer> answers(String document,Authorization authorization) {
        var row=require(authorization.generationId());
        if(!row.documentId().equals(document))throw PptSupport.conflict("生成授权不属于此作品");
        return mapper.answers(document,"generate_"+row.id().replace("-","")+"_*").stream().map(q->new Answer(q.prompt(),q.answer())).toList();
    }
    @Override @Transactional public void cancel(String document) {
        mapper.active(document).ifPresent(row->{if(!row.state().equals("STOPPING"))state(row,PptGenerationState.STOPPING,"正在停止生成，自动续接已取消");});
    }
    private Generation current(Generation expected) {
        var row=require(expected.id());
        if(PptGenerationState.valueOf(row.state()).terminal()||row.state().equals("STOPPING")||row.attempt()!=expected.attempt()
                ||!row.step().equals(expected.step())||!row.agentKey().equals(expected.agentKey()))throw PptSupport.conflict("自动生成已停止或进入新阶段");
        return row;
    }
    private PptRows.Document document(String id,long revision) {
        var row=documents.document(id).orElseThrow(()->new NotFoundException("PPT 作品不存在"));
        if(row.archived()||row.revision()!=revision)throw PptSupport.conflict("作品已修改或归档，自动生成不会覆盖新内容，请刷新后继续");return row;
    }
    private void receipt(Generation row,String key,String sha,String kind) {
        if(mapper.insertRequest(new Request(row.documentId(),key,sha,row.id(),kind,now()))!=1)throw PptSupport.conflict("生成请求回执冲突");
    }
    private void mutate(java.util.function.IntSupplier action) { lifecycle.mutateWithoutTransition(action,()->PptSupport.conflict("生成状态已变化")); }
    private LifecycleTransitionService.Subject subject(Generation row) {
        return new LifecycleTransitionService.Subject(LifecycleMachineType.PPT_GENERATION,row.id(),LifecycleScopeType.PPT_DOCUMENT,row.documentId());
    }
    public static String agentKey(String id,int attempt,String step) { return "generate_"+id.replace("-","")+"_"+attempt+"_"+step.toLowerCase(Locale.ROOT); }
    private static String now() { return Instant.now().toString(); }
}
