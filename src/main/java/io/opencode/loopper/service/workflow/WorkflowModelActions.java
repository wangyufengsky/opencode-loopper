package io.opencode.loopper.service.workflow;

import io.opencode.loopper.persistence.WorkflowModelMapper.Launch;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Explicit user recovery keeps the same remote identity; it is not a new model attempt. */
@Service
public class WorkflowModelActions {
    private final WorkflowModelStore store;
    private final WorkflowNodeRuns nodes;
    private final WorkflowCommands commands;
    public WorkflowModelActions(WorkflowModelStore store, WorkflowNodeRuns nodes, WorkflowCommands commands) {
        this.store=store; this.nodes=nodes; this.commands=commands;
    }
    public record Command(String requestKey, long expectedVersion) { }
    public record View(String attemptId, String state, boolean suspended, String errorCode, long version) { }
    public View get(String id,String key,String attemptId) { return view(scoped(id,key,attemptId)); }
    @Transactional
    public View stop(String id,String key,String attemptId,Command command) { return change(id,key,attemptId,command,false); }
    @Transactional
    public View resume(String id,String key,String attemptId,Command command) { return change(id,key,attemptId,command,true); }
    private View change(String id,String key,String attemptId,Command command,boolean resume) {
        var row=scoped(id,key,attemptId);
        String action=resume?"MODEL_RESUME":"MODEL_STOP";
        String digest=store.encoding().digest(action,id+"/"+key+"/"+attemptId,command);
        var replay=commands.replay(command.requestKey(),digest,View.class); if (replay.isPresent()) return replay.get();
        if (row.version()!=command.expectedVersion()) throw WorkflowCommands.conflict();
        if (resume) store.resume(attemptId,command.expectedVersion()); else store.stop(attemptId);
        return commands.record(command.requestKey(),digest,"REQUIREMENT",action,id,view(store.require(attemptId)));
    }
    private Launch scoped(String id,String key,String attemptId) {
        nodes.scopedAttempt(id,key,attemptId);
        var row=store.require(attemptId);
        if (!row.requirementId().equals(id)) throw WorkflowCommands.conflict();
        return row;
    }
    private static View view(Launch row) { return new View(row.attemptId(),row.state(),row.suspended(),row.lastErrorCode(),row.version()); }
}
