package io.opencode.loopper.service.workflow;

import io.opencode.loopper.domain.*;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.WorkflowCommandRunMapper;
import io.opencode.loopper.persistence.WorkflowCommandRunMapper.Run;
import io.opencode.loopper.persistence.WorkflowExecutionRows.Attempt;
import io.opencode.loopper.runtime.DurableCommandProtocol;
import io.opencode.loopper.runtime.DurableCommandProtocol.*;
import io.opencode.loopper.workflow.*;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static io.opencode.loopper.service.workflow.WorkflowCommands.conflict;

/** Owns short SQLite transactions; no filesystem, process or network operation is allowed here. */
@Service
@Transactional(readOnly=true)
public class WorkflowCommandStore {
    private final WorkflowCommandRunMapper mapper;
    private final WorkflowNodeActions actions;
    private final WorkflowNodeRuns nodes;
    private final WorkflowEncoding encoding;
    private final WorkflowCommands commands;
    private final WorkflowSettlement settlement;
    private final LifecycleTransitionService lifecycle;
    private final WorkflowCommandContract contract;
    public WorkflowCommandStore(WorkflowCommandRunMapper mapper,WorkflowNodeActions actions,WorkflowNodeRuns nodes,WorkflowEncoding encoding,
            WorkflowCommands commands,WorkflowSettlement settlement,LifecycleTransitionService lifecycle,WorkflowCommandContract contract) {
        this.mapper=mapper;this.actions=actions;this.nodes=nodes;this.encoding=encoding;this.commands=commands;this.settlement=settlement;this.lifecycle=lifecycle;this.contract=contract;
    }
    public record Context(Run run,Attempt attempt,WorkflowGraph.Node node,WorkflowCommandVerification spec,WorkflowDelivery.Input input,WorkflowNativeTestContract.Context nativeTest,WorkflowRepositoryContract.Context repository,WorkflowHistoryContract.Context history,WorkflowReviewSourceContract.Context review) { }
    public Run require(String id){return mapper.find(id).orElseThrow(WorkflowCommands::conflict);}
    @Transactional
    public WorkflowNodeActions.Receipt dispatch(String id,String key,WorkflowNodeActions.Start request,WorkflowDispatch.Permit permit) {
        String digest=encoding.digest("NODE_COMMAND_START",id+"/"+key,request);
        var replay=commands.replay(request.requestKey(),digest,WorkflowNodeActions.Receipt.class);if(replay.isPresent())return replay.get();
        var admission=actions.admit(id,key,request,permit);contract.resolve(admission.definition(),admission.inputs());
        var attempt=nodes.begin(admission.node(),admission.owner().headRevision(),admission.inputs(),WorkflowCommandVerification.ADAPTER,null);
        nodes.transition(attempt,WorkflowAttemptState.RUNNING,LifecycleEvent.START);
        contract.admitRepository(attempt,admission);
        String now=Instant.now().toString();var row=new Run(attempt.id(),id,admission.owner().projectId(),"PREPARING",null,null,null,null,null,false,null,0,now,now);
        lifecycle.create(subject(row),row.state(),Map.of("attemptId",row.attemptId()),()->mapper.insert(row),WorkflowCommands::conflict);
        return actions.acknowledge(request.requestKey(),digest,"COMMAND_START",id,key,attempt.id());
    }
    public Context context(String id) {
        var run=require(id);if(WorkflowCommandState.valueOf(run.state()).terminal())return null;
        var attempt=nodes.attempt(id);nodes.active(attempt);
        if(!WorkflowCommandVerification.ADAPTER.equals(attempt.adapterKey()))throw conflict();
        var definition=nodes.definition(nodes.requireNode(attempt.nodeRunId()));var inputs=nodes.inputs(attempt);var nativeTest=contract.nativeContext(definition,inputs);
        var repository=contract.repositoryContext(attempt,definition,inputs);
        var history=contract.historyContext(attempt,definition,inputs);
        var review=contract.reviewContext(attempt,definition,inputs);
        var spec=repository!=null||history!=null||review!=null?null:nativeTest==null?contract.parse(definition):nativeTest.spec();
        var input=inputs.values().stream().filter(value->value.name().equals(repository!=null||history!=null||review!=null?"branch":spec.inputName())).findFirst().orElseThrow(WorkflowCommands::conflict);
        return new Context(run,attempt,definition,spec,input,nativeTest,repository,history,review);
    }
    @Transactional
    public void prepared(Context context,Request request,String sha) {
        var row=current(context.run());if(!row.state().equals("PREPARING") || !request.id().equals(row.attemptId()))throw conflict();
        try{if(!DurableCommandProtocol.hash(DurableCommandProtocol.request(request)).equals(sha))throw conflict();}catch(IOException invalid){throw conflict();}
        if(mapper.prepare(row.attemptId(),row.version(),encoding.encode(request),sha,now())!=1)throw conflict();
        transition(require(row.attemptId()),WorkflowCommandState.READY,LifecycleEvent.PREPARE);
    }
    /** A persisted RUNNING state authorizes only this exact worker and request. */
    @Transactional
    public void registered(Context context,Registration registration) {
        var row=current(context.run());
        if(!Set.of("READY","STOPPING").contains(row.state()) || !registration.requestSha256().equals(row.requestSha256()))throw conflict();
        if(row.registrationJson()==null) {
            if(mapper.register(row.attemptId(),row.version(),encoding.encode(registration),now())!=1)throw conflict();row=require(row.attemptId());
        } else if(!registration.equals(encoding.decode(row.registrationJson(),Registration.class)))throw conflict();
        if(row.state().equals("READY"))transition(row,WorkflowCommandState.RUNNING,LifecycleEvent.START);
    }
    public Request request(Run row){return encoding.decode(row.requestJson(),Request.class);}
    public Registration registration(Run row){return row.registrationJson()==null?null:encoding.decode(row.registrationJson(),Registration.class);}
    @Transactional
    public void finish(Context context,Result result) {
        finish(context,result,null,null,null);
    }
    @Transactional
    public void finish(Context context,Result result,io.opencode.loopper.service.GitSnapshotJobProtocol.Snapshot repositorySnapshot,WorkflowHistorySnapshot.Manifest historySnapshot,WorkflowReviewSource.Manifest reviewSnapshot) {
        var row=current(context.run());nodes.active(context.attempt());
        if(result==null) {
            if(!row.state().equals("STOPPING") || row.requestJson()!=null || row.registrationJson()!=null)throw conflict();
            if(mapper.stopProof(row.attemptId(),"COMMAND_NOT_LAUNCHED",encoding.encode(Map.of("adapter",WorkflowCommandVerification.ADAPTER)),now())!=1)throw conflict();
            complete(context,row,WorkflowAttemptState.CANCELLED);return;
        }
        if(!result.stopConfirmed() || !Objects.equals(result.requestSha256(),row.requestSha256()) || registration(row)==null
                || !result.worker().equals(registration(row).worker()) || !DurableCommandProtocol.matches(request(row),result)
                || result.preparations().stream().anyMatch(step->!step.stopConfirmed()))throw conflict();
        String json=encoding.encode(result),sha=WorkflowEncoding.hash(json);
        if(mapper.result(row.attemptId(),row.version(),json,sha,now())!=1)throw conflict();
        row=require(row.attemptId());
        if(mapper.stopProof(row.attemptId(),"COMMAND_TERMINAL",encoding.encode(Map.of("requestSha256",row.requestSha256(),"resultSha256",sha)),now())!=1)throw conflict();
        if(row.state().equals("STOPPING")){complete(context,row,WorkflowAttemptState.CANCELLED);return;}
        var evaluation=contract.evaluate(context,result,repositorySnapshot,historySnapshot,reviewSnapshot);
        nodes.accept(context.attempt(),evaluation.delivery());
        complete(context,row,evaluation.success()?WorkflowAttemptState.SUCCEEDED:WorkflowAttemptState.FAILED);
    }
    private void complete(Context context,Run row,WorkflowAttemptState state) {
        nodes.finish(context.attempt(),state);
        transition(row,WorkflowCommandState.valueOf(state.name()),state==WorkflowAttemptState.SUCCEEDED?LifecycleEvent.COMPLETE:state==WorkflowAttemptState.FAILED?LifecycleEvent.FAIL:LifecycleEvent.ABORT);
        settlement.settle(row.requirementId(),state!=WorkflowAttemptState.SUCCEEDED,row.attemptId());
    }
    @Transactional
    public void suspend(Run expected,String code) {
        var row=require(expected.attemptId());if(row.version()!=expected.version() || WorkflowCommandState.valueOf(row.state()).terminal())return;
        String safe=WorkflowFailures.safe(code);if(row.suspended() && Objects.equals(row.lastErrorCode(),safe))return;
        if(mapper.suspend(row.attemptId(),row.version(),true,safe,now())!=1)throw conflict();
        settlement.block(row.requirementId(),safe);
    }
    @Transactional
    public void stop(String id,long version) {
        var row=require(id);if(row.version()!=version || WorkflowCommandState.valueOf(row.state()).terminal())throw conflict();
        var attempt=nodes.active(nodes.attempt(id));
        if(!row.state().equals("STOPPING")){
            nodes.transition(attempt,WorkflowAttemptState.STOPPING,LifecycleEvent.CANCEL);
            transition(row,WorkflowCommandState.STOPPING,LifecycleEvent.CANCEL);
        }
        settlement.hold(row.requirementId(),"WORKFLOW_COMMAND_STOPPING");
    }
    @Transactional
    public void resume(String id,long version) {
        var row=require(id);if(row.version()!=version || !row.suspended() || WorkflowCommandState.valueOf(row.state()).terminal())throw conflict();
        nodes.active(nodes.attempt(id));
        if(mapper.suspend(id,version,false,null,now())!=1)throw conflict();
    }
    private Run current(Run expected){var row=require(expected.attemptId());if(row.version()!=expected.version())throw conflict();return row;}
    private void transition(Run row,WorkflowCommandState next,LifecycleEvent event) {
        lifecycle.transition(subject(row),row.state(),next.name(),event,null,Map.of(),
                ()->mapper.transition(row.attemptId(),row.version(),row.state(),next.name(),now()),WorkflowCommands::conflict);
    }
    private LifecycleTransitionService.Subject subject(Run row){return new LifecycleTransitionService.Subject(LifecycleMachineType.WORKFLOW_COMMAND,row.attemptId(),LifecycleScopeType.PROJECT,row.projectId());}
    private static String now(){return Instant.now().toString();}
}
