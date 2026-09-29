package io.opencode.loopper.service.workflow;

import io.opencode.loopper.workflow.WorkflowWriteback.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** File writes end before the short receipt/FIFO transaction; process loss leaves the same intent recoverable. */
@Service
@Transactional(propagation=Propagation.NOT_SUPPORTED)
public class WorkflowWritebackExecution {
    private final WorkflowWritebacks records;
    private final WorkflowWritebackPreviews previews;
    private final WorkflowWritebackFiles files;
    public WorkflowWritebackExecution(WorkflowWritebacks records,WorkflowWritebackPreviews previews,WorkflowWritebackFiles files){this.records=records;this.previews=previews;this.files=files;}
    public View confirm(String requirement,Request request) {
        var replay=records.replay(requirement,request);if(replay!=null)return replay;
        return records.confirm(requirement,request,previews.inspect(requirement,request.selection()));
    }
    public void advance(String requirement) {
        var observed=records.work(requirement);
        if(!observed.queueState().equals("ADMITTED")||!java.util.Set.of("CONFIRMED","APPLYING").contains(observed.row().state()))return;
        WorkflowWritebackFiles.Guard ownership;
        try{ownership=files.lock(observed.row().id());}
        catch(RuntimeException failure){records.blocked(observed,"WORKFLOW_WRITEBACK_LOCK_UNAVAILABLE");return;}
        try(var guard=ownership) {
            if(guard==null)return; // Another live writer is not a failure and cannot grant permission to retry.
            var work=records.work(requirement);
            if(!work.queueState().equals("ADMITTED")||!java.util.Set.of("CONFIRMED","APPLYING").contains(work.row().state()))return;
            long lease=records.requireWriter(work);
            if(work.row().state().equals("CONFIRMED")) {
                try{files.prepare(work.row().id(),work.intent());}
                catch(RuntimeException failure){records.blocked(work,"WORKFLOW_WRITEBACK_PREPARATION_CHANGED");return;}
                work=records.prepared(work);
            }
            try{files.apply(work.row().id(),work.intent());}
            catch(RuntimeException failure){records.blocked(work,"WORKFLOW_WRITEBACK_APPLY_FAILED");return;}
            // A lost metadata receipt must remain APPLYING: recovery verifies the same target without recopying.
            records.applied(work,lease,work.intent().preview().targetSha256());
        }
    }
}
