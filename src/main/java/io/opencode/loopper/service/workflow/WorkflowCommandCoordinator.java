package io.opencode.loopper.service.workflow;

import io.opencode.loopper.persistence.WorkflowCommandRunMapper;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls persisted command intents with bounded concurrency; shutdown leaves detached supervision intact. */
@Component
@ConditionalOnProperty(name="loopper.workflow-monitor-enabled",havingValue="true",matchIfMissing=true)
public final class WorkflowCommandCoordinator implements org.springframework.beans.factory.DisposableBean {
    private final WorkflowCommandRunMapper mapper;
    private final WorkflowCommandExecution execution;
    private final Set<String> pending=ConcurrentHashMap.newKeySet();
    private final Semaphore capacity=new Semaphore(4);
    private final ExecutorService workers=Executors.newFixedThreadPool(4,Thread.ofPlatform().daemon().name("workflow-command-",0).factory());
    private String cursor="";
    public WorkflowCommandCoordinator(WorkflowCommandRunMapper mapper,WorkflowCommandExecution execution){this.mapper=mapper;this.execution=execution;}
    @Scheduled(fixedDelayString="${loopper.workflow-monitor-delay:${loopper.monitor-delay:2s}}",initialDelayString="${loopper.workflow-monitor-delay:${loopper.monitor-delay:2s}}")
    public synchronized void tick() {
        if(capacity.availablePermits()==0)return;
        var ids=mapper.active(cursor,32);if(ids.isEmpty()){cursor="";return;}
        for(var id:ids) {
            if(!capacity.tryAcquire())break;cursor=id;
            if(!pending.add(id)){capacity.release();continue;}
            try { workers.submit(()->{try{execution.advance(id);}catch(RuntimeException changed){/* Preserve the original command identity; never create a replacement here. */}finally{pending.remove(id);capacity.release();}}); }
            catch(RejectedExecutionException stopped){pending.remove(id);capacity.release();}
        }
    }
    @Override public void destroy(){workers.shutdownNow();}
}
