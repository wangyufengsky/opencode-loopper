package io.opencode.loopper.service.workflow;

import io.opencode.loopper.persistence.WorkflowFinishMapper;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded, restartable database-only drain; no process or provider wait occupies the scheduler. */
@Component
@ConditionalOnProperty(name="loopper.workflow-monitor-enabled",havingValue="true",matchIfMissing=true)
public final class WorkflowFinishCoordinator {
    private final WorkflowFinishMapper mapper;
    private final WorkflowFinishDrain drain;
    private final WorkflowFinishes finishes;
    private final Map<String,String> attemptCursors=new HashMap<>();
    private String cursor="";
    public WorkflowFinishCoordinator(WorkflowFinishMapper mapper,WorkflowFinishDrain drain,WorkflowFinishes finishes) {
        this.mapper=mapper;this.drain=drain;this.finishes=finishes;
    }
    @Scheduled(fixedDelayString="${loopper.workflow-monitor-delay:${loopper.monitor-delay:2s}}",initialDelayString="${loopper.workflow-monitor-delay:${loopper.monitor-delay:2s}}")
    public synchronized void tick() {
        var ids=mapper.pending(cursor,16);
        if(ids.isEmpty()){cursor="";attemptCursors.keySet().removeIf(id->mapper.find(id).map(row->row.finalizedAt()!=null).orElse(true));return;}
        for(var id:ids) {
            cursor=id;
            var attempts=mapper.attempts(id,attemptCursors.getOrDefault(id,""),32);
            for(var attempt:attempts) {
                attemptCursors.put(id,attempt);
                try{drain.stop(id,attempt);}catch(RuntimeException changed){/* Keep intent and evidence for recovery; one blocked node cannot starve other stops. */}
            }
            if(attempts.size()<32)attemptCursors.remove(id);
            try{if(finishes.finalizeReady(id))attemptCursors.remove(id);}catch(RuntimeException changed){/* Retry the same durable finish intent. */}
        }
    }
}
