package io.opencode.loopper.service.workflow;

import io.opencode.loopper.persistence.WorkflowWritebackMapper;
import java.util.Set;
import java.util.concurrent.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Only admitted, already-confirmed operations resume. A blocked operation needs the user's retry. */
@Component
@ConditionalOnProperty(name="loopper.workflow-monitor-enabled",havingValue="true",matchIfMissing=true)
public final class WorkflowWritebackCoordinator implements org.springframework.beans.factory.DisposableBean {
    private final WorkflowWritebackMapper mapper;private final WorkflowWritebackExecution execution;
    private final Set<String> pending=ConcurrentHashMap.newKeySet();private final Semaphore capacity=new Semaphore(2);
    private final ExecutorService workers=Executors.newFixedThreadPool(2,Thread.ofPlatform().daemon().name("workflow-writeback-",0).factory());
    private String cursor="";
    public WorkflowWritebackCoordinator(WorkflowWritebackMapper mapper,WorkflowWritebackExecution execution){this.mapper=mapper;this.execution=execution;}
    @Scheduled(fixedDelayString="${loopper.workflow-monitor-delay:${loopper.monitor-delay:2s}}",initialDelayString="${loopper.workflow-monitor-delay:${loopper.monitor-delay:2s}}")
    public synchronized void tick() {
        if(capacity.availablePermits()==0)return;var ids=mapper.pending(cursor,16);if(ids.isEmpty()){cursor="";return;}
        for(var id:ids) {
            if(!capacity.tryAcquire())break;cursor=id;if(!pending.add(id)){capacity.release();continue;}
            try{workers.submit(()->{try{execution.advance(id);}catch(RuntimeException failure){/* Original intent and lease remain for recovery. */}finally{pending.remove(id);capacity.release();}});}
            catch(RejectedExecutionException stopped){pending.remove(id);capacity.release();}
        }
    }
    @Override public void destroy(){workers.shutdownNow();}
}
