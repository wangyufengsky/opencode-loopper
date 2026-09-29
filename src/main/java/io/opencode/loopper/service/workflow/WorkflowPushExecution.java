package io.opencode.loopper.service.workflow;

import io.opencode.loopper.runtime.*;
import io.opencode.loopper.workflow.WorkflowPush;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Resume exact durable supervisors; an absent receipt never authorizes another writer. */
@Service
@Transactional(propagation=Propagation.NOT_SUPPORTED)
public class WorkflowPushExecution {
    private final WorkflowPushes store;
    private final WorkflowPushPreviews previews;
    private final WorkflowPushJobs jobs;
    private final DurableCommands commands;
    public WorkflowPushExecution(WorkflowPushes store,WorkflowPushPreviews previews,WorkflowPushJobs jobs,DurableCommands commands){this.store=store;this.previews=previews;this.jobs=jobs;this.commands=commands;}
    public WorkflowPush.View confirm(String id,WorkflowPush.Request request){var replay=store.replay(id,request);return replay!=null?replay:store.confirm(id,request,previews.inspect(id,request.remote()));}
    public WorkflowPush.View retry(String id,long version) {
        var work=store.work(id);boolean stopped=false;
        if(work.attempt().resultJson()!=null){var observed=commands.observe(job(work));stopped=!observed.stopUnknown()&&observed.result()!=null&&observed.result().stopConfirmed()&&store.sameResult(work,observed.result());}
        return store.retry(work,version,stopped);
    }
    public void advance(String id) {
        var work=store.work(id);if(!java.util.Set.of("PREPARING","RUNNING").contains(work.push().state()))return;
        try {
            if(work.push().state().equals("PREPARING")){var request=jobs.prepare(work.input());var job=commands.prepare(request);store.prepared(work,request,job.requestSha256());return;}
            var job=job(work);var observed=commands.observe(job);
            if(observed.stopUnknown()){store.suspend(work,"WORKFLOW_PUSH_STOP_UNCONFIRMED");return;}
            if(observed.registration()==null){commands.ensureSupervisor(job,jobs.environment(work.input()));return;}
            if(work.attempt().registrationJson()==null){store.registered(work,observed.registration());return;}
            if(!store.registration(work).equals(observed.registration()))throw WorkflowPublications.changed();
            if(observed.result()!=null){store.finish(work,observed.result());return;}
            commands.grant(job,store.registration(work));
        }catch(RuntimeException failure){String code=WorkflowFailures.code(failure);store.suspend(work,code.equals("WORKFLOW_MODEL_RECOVERY_REQUIRED")?"WORKFLOW_PUSH_RECOVERY_REQUIRED":code);}
    }
    private static DurableCommands.Job job(WorkflowPushes.Work work){return new DurableCommands.Job(work.attempt().id(),work.attempt().requestSha256());}
}
