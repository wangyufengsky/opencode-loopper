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

@Service
public class PptJobPersistence {
    private final PptMapper mapper;
    private final LifecycleTransitionService lifecycle;
    private final PptPersistence documents;
    public PptJobPersistence(PptMapper mapper,LifecycleTransitionService lifecycle,PptPersistence documents) {
        this.mapper=mapper;this.lifecycle=lifecycle;this.documents=documents;
    }
    @Transactional
    public Job create(Job row,long documentVersion,Runnable guard) {
        return create(row,documentVersion,guard,job->{});
    }
    @Transactional
    public Job create(Job row,long documentVersion,Runnable guard,java.util.function.Consumer<Job> attachment) {
        guard.run();var old=mapper.jobReceipt(row.documentId(),row.requestKey());
        if(old.isPresent()) {if(!old.get().digest().equals(row.digest()))throw PptSupport.conflict("作业标识已用于不同请求");attachment.accept(old.get());return old.get();}
        if(mapper.document(row.documentId()).orElseThrow().version()!=documentVersion)throw PptSupport.conflict("作品已改变，请刷新后重试");
        lifecycle.create(subject(row),row.state(),Map.of("revision",row.revision()),()->mapper.insertJob(row),()->PptSupport.conflict("作业已经创建"));
        attachment.accept(row);return row;
    }
    @Transactional public Job attach(Job row,Runnable guard,java.util.function.Consumer<Job> attachment) {
        guard.run();attachment.accept(row);return row;
    }
    @Transactional
    public Job state(Job row,String next,int completed,String detail) {
        lifecycle.transition(subject(row),row.state(),next,null,Map.of(),
                ()->mapper.jobState(row.id(),row.version(),next,completed,detail,Instant.now().toString()),()->PptSupport.conflict("作业状态已变化"));
        if("COMPLETED".equals(next)&&"EXPORT".equals(row.kind())) {
            var doc=mapper.document(row.documentId()).orElseThrow();
            if(doc.revision()==row.revision()&&doc.phase().equals("REVIEW"))documents.phase(doc,"EXPORTED");
        }
        return mapper.job(row.documentId(),row.id()).orElseThrow();
    }
    @Transactional
    public void artifact(Job row,Artifact artifact,int count) {
        var current=mapper.job(row.documentId(),row.id()).orElseThrow();
        if(!"RUNNING".equals(current.state()))throw PptSupport.conflict("作业已停止");
        if(mapper.artifact(row.documentId(),artifact.id()).isEmpty()&&mapper.insertArtifact(artifact)!=1)throw PptSupport.conflict("制品记录保存失败");
        lifecycle.mutateWithoutTransition(()->mapper.jobProgress(current.id(),current.version(),count,Instant.now().toString()),()->PptSupport.conflict("作业进度已变化"));
    }
    private Subject subject(Job row){return new Subject(LifecycleMachineType.PPT_JOB,row.id(),LifecycleScopeType.PPT_DOCUMENT,row.documentId());}
}
