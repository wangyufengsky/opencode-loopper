package io.opencode.loopper.service.workflow;

import io.opencode.loopper.persistence.WorkflowCommandRunMapper.Run;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** User recovery operates on the original job; it never grants permission for a second process. */
@Service
@Transactional(readOnly=true)
public class WorkflowCommandActions {
    private final WorkflowCommandStore store;
    private final WorkflowNodeRuns nodes;
    private final WorkflowCommands commands;
    private final WorkflowEncoding encoding;
    private final WorkflowNativeTestEvidence nativeTests;
    public WorkflowCommandActions(WorkflowCommandStore store,WorkflowNodeRuns nodes,WorkflowCommands commands,WorkflowEncoding encoding,WorkflowNativeTestEvidence nativeTests) {
        this.store=store;this.nodes=nodes;this.commands=commands;this.encoding=encoding;this.nativeTests=nativeTests;
    }
    public record Command(String requestKey,long expectedVersion) { }
    public record View(String attemptId,String state,boolean suspended,String errorCode,long version) { }
    public record Evidence(String attemptId,String requestSha256,String resultSha256,tools.jackson.databind.JsonNode request,tools.jackson.databind.JsonNode registration,tools.jackson.databind.JsonNode result,tools.jackson.databind.JsonNode nativeReport) { }
    public View get(String id,String key,String attempt){return view(scoped(id,key,attempt));}
    public Evidence evidence(String id,String key,String attempt) {
        var row=scoped(id,key,attempt);
        try {
            if(row.requestJson()!=null && !io.opencode.loopper.runtime.DurableCommandProtocol.hash(
                    io.opencode.loopper.runtime.DurableCommandProtocol.request(store.request(row))).equals(row.requestSha256())
                    || row.resultJson()!=null && !WorkflowEncoding.hash(row.resultJson()).equals(row.resultSha256()))throw new IllegalArgumentException();
        } catch(java.io.IOException | RuntimeException invalid) {
            throw new io.opencode.loopper.service.ConflictException("WORKFLOW_COMMAND_EVIDENCE_INVALID","命令执行记录不一致，请保留现场并检查数据目录。");
        }
        var nativeEvidence=nativeTests.find(attempt);
        if(nativeEvidence.isPresent()&&(!nativeEvidence.get().requestSha256().equals(row.requestSha256())||row.resultJson()!=null&&!nativeEvidence.get().resultJson().equals(row.resultJson())))throw WorkflowCommands.conflict();
        return new Evidence(attempt,row.requestSha256(),row.resultSha256(),json(row.requestJson()),json(row.registrationJson()),json(row.resultJson()),nativeEvidence.map(e->json(e.reportJson())).orElse(null));
    }
    @Transactional public View stop(String id,String key,String attempt,Command request){return change(id,key,attempt,request,false);}
    @Transactional public View resume(String id,String key,String attempt,Command request){return change(id,key,attempt,request,true);}
    private View change(String id,String key,String attempt,Command request,boolean resume) {
        var row=scoped(id,key,attempt);String action=resume?"COMMAND_RESUME":"COMMAND_STOP";
        String digest=encoding.digest(action,id+"/"+key+"/"+attempt,request);
        var replay=commands.replay(request.requestKey(),digest,View.class);if(replay.isPresent())return replay.get();
        if(row.version()!=request.expectedVersion())throw WorkflowCommands.conflict();
        if(resume)store.resume(attempt,request.expectedVersion());else store.stop(attempt,request.expectedVersion());
        return commands.record(request.requestKey(),digest,"REQUIREMENT",action,id,view(store.require(attempt)));
    }
    private Run scoped(String id,String key,String attempt) {
        nodes.scopedAttempt(id,key,attempt);var row=store.require(attempt);if(!row.requirementId().equals(id))throw WorkflowCommands.conflict();return row;
    }
    private tools.jackson.databind.JsonNode json(String body){return body==null?null:encoding.decode(body,tools.jackson.databind.JsonNode.class);}
    private static View view(Run row){return new View(row.attemptId(),row.state(),row.suspended(),row.lastErrorCode(),row.version());}
}
