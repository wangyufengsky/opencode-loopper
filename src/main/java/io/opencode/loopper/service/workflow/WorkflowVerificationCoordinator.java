package io.opencode.loopper.service.workflow;

import io.opencode.loopper.persistence.WorkflowExecutionMapper;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** A lost in-process computation can be safely repeated from the same immutable inputs. */
@Component
@ConditionalOnProperty(name="loopper.workflow-monitor-enabled",havingValue="true",matchIfMissing=true)
public final class WorkflowVerificationCoordinator implements org.springframework.beans.factory.DisposableBean {
    private final WorkflowExecutionMapper mapper;
    private final WorkflowVerificationExecution execution;
    private final Set<String> pending=ConcurrentHashMap.newKeySet();
    private final Semaphore capacity=new Semaphore(4);
    private final ExecutorService workers=Executors.newFixedThreadPool(4,Thread.ofPlatform().daemon().name("workflow-file-check-",0).factory());
    private String cursor="";
    public WorkflowVerificationCoordinator(WorkflowExecutionMapper mapper,WorkflowVerificationExecution execution){this.mapper=mapper;this.execution=execution;}
    @Scheduled(fixedDelayString="${loopper.workflow-monitor-delay:${loopper.monitor-delay:2s}}",initialDelayString="${loopper.workflow-monitor-delay:${loopper.monitor-delay:2s}}")
    public synchronized void tick() {
        if(capacity.availablePermits()==0)return;
        var ids=mapper.activeVerifications(cursor,32);if(ids.isEmpty()){cursor="";return;}
        for(var id:ids) {
            if(!capacity.tryAcquire())break;cursor=id;
            if(!pending.add(id)){capacity.release();continue;}
            try { workers.submit(()->{try{execution.advance(id);}catch(RuntimeException changed){/* Preserve the durable attempt and retry its pure read on the next poll. */}finally{pending.remove(id);capacity.release();}}); }
            catch(RejectedExecutionException stopped){pending.remove(id);capacity.release();}
        }
    }
    @Override public void destroy(){workers.shutdownNow();}
}
