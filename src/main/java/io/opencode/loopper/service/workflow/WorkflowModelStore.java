package io.opencode.loopper.service.workflow;

import static io.opencode.loopper.service.workflow.WorkflowCommands.conflict;
import io.opencode.loopper.domain.*;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.persistence.WorkflowModelMapper.Launch;
import io.opencode.loopper.runtime.OpenCodeClient;
import io.opencode.loopper.service.ConflictException;
import io.opencode.loopper.workflow.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Durable model dispatch and stop evidence. All methods here are database-only. */
@Service
@Transactional(readOnly=true)
public class WorkflowModelStore {
    private final WorkflowModelMapper mapper;
    private final WorkflowExecutionMapper execution;
    private final WorkflowNodeRuns nodes;
    private final WorkflowPlans plans;
    private final WorkflowNodeActions actions;
    private final WorkflowEncoding encoding;
    private final LifecycleTransitionService lifecycle;
    public WorkflowModelStore(WorkflowModelMapper mapper, WorkflowExecutionMapper execution, WorkflowNodeRuns nodes,
            WorkflowPlans plans, WorkflowNodeActions actions, WorkflowEncoding encoding, LifecycleTransitionService lifecycle) {
        this.mapper=mapper; this.execution=execution; this.nodes=nodes; this.plans=plans; this.actions=actions; this.encoding=encoding; this.lifecycle=lifecycle;
    }
    public WorkflowEncoding encoding() { return encoding; }
    public Launch require(String id) { return mapper.find(id).orElseThrow(WorkflowCommands::conflict); }
    public WorkflowExecutionRows.Attempt attempt(Launch row) { return nodes.attempt(row.attemptId()); }
    public WorkflowGraph.Node definition(Launch row) {
        return nodes.definition(execution.node(attempt(row).nodeRunId()).orElseThrow(WorkflowCommands::conflict));
    }
    public Launch active(Launch expected) {
        var row=require(expected.attemptId());
        if (row.version()!=expected.version() || WorkflowModelState.valueOf(row.state()).terminal()) throw conflict();
        nodes.active(attempt(row));
        return row;
    }
    public Launch activeWork(Launch expected) {
        var row=active(expected);
        // A draining session may still need lifecycle writes, but cannot start more model work.
        if (!Set.of("RUNNING","PAUSED","STALLED").contains(plans.require(row.requirementId()).state()))
            throw new ConflictException("WORKFLOW_MODEL_NOT_ACTIVE","需求任务正在结束，不能继续读取或提交");
        return row;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void create(WorkflowExecutionRows.Attempt attempt, WorkflowRows.Requirement owner, Path directory, OpenCodeClient.OpenCodeModel model) {
        String now=Instant.now().toString();
        var row=new Launch(attempt.id(),owner.id(),"PREPARING",directory.toString(),encoding.encode(model),null,null,null,false,null,0,now,now);
        lifecycle.create(subject(row),row.state(),Map.of("attemptId",attempt.id()),()->mapper.insert(row),WorkflowCommands::conflict);
    }
    @Transactional
    public void prepared(Launch expected, OpenCodeClient.SessionCreationPlan plan, WorkflowModelPrompt.Frozen prompt) {
        var row=active(expected);
        if (!row.state().equals("PREPARING") || row.suspended() || !plan.canonicalDirectory().toString().equals(row.directory())) throw conflict();
        if (mapper.prepare(row.attemptId(),row.version(),encoding.encode(plan),encoding.encode(prompt),
                OpenCodeClient.promptRequestSha256(prompt.request()),Instant.now().toString())!=1) throw conflict();
        transition(require(row.attemptId()),WorkflowModelState.CREATING,LifecycleEvent.PREPARE);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void completeReuse(Launch expected,WorkflowDelivery delivery,Map<String,Object> evidence) {
        var row=activeWork(expected);var attempt=attempt(row);
        if(!row.state().equals("PREPARING")||row.suspended()||row.creationPlanJson()!=null||attempt.externalSessionId()!=null
            ||!WorkflowSnapshotWork.ANALYZE.equals(definition(row).moduleId()))throw conflict();
        var running=nodes.transition(attempt,WorkflowAttemptState.RUNNING,LifecycleEvent.START);
        nodes.accept(running,delivery); // The specialized contract validates the saved provenance instead of fabricating reads.
        proof(running,"NO_SESSION_CREATED",evidence);
        nodes.finish(running,WorkflowAttemptState.SUCCEEDED);
        transition(row,WorkflowModelState.SUCCEEDED,LifecycleEvent.REUSE_RESULT);
        actions.settle(row.requirementId(),false,attempt.id());
    }
    @Transactional
    public void attached(Launch expected, OpenCodeClient.SessionAttestation attestation) {
        var row=active(expected); var attempt=attempt(row);
        if (!row.state().equals("CREATING") || row.suspended() || !plan(row).equals(attestation.plan())
                || attestation.attestationKind()!=OpenCodeClient.SessionAttestationKind.LOCAL_REQUEST_ATTESTED) throw conflict();
        if (execution.attach(attempt.id(),attempt.version(),attestation.remoteId(),Instant.now().toString())!=1) throw conflict();
        nodes.transition(nodes.attempt(attempt.id()),WorkflowAttemptState.RUNNING,LifecycleEvent.START);
        transition(row,WorkflowModelState.DISPATCHING,LifecycleEvent.PREPARATION_SUCCEEDED);
    }
    @Transactional
    public void dispatched(Launch expected) {
        var row=active(expected);
        if (row.suspended()) throw conflict();
        transition(row,WorkflowModelState.RUNNING,LifecycleEvent.START);
    }
    @Transactional
    public void stop(String id) {
        var row=require(id);
        if (WorkflowModelState.valueOf(row.state()).terminal() || row.state().equals("STOPPING")) return;
        active(row);
        nodes.transition(attempt(row),WorkflowAttemptState.STOPPING,LifecycleEvent.CANCEL);
        transition(row,WorkflowModelState.STOPPING,LifecycleEvent.CANCEL);
        actions.holdDispatch(row.requirementId());
    }
    @Transactional
    public void suspend(String id, String code) {
        var row=require(id);
        if (WorkflowModelState.valueOf(row.state()).terminal() || row.suspended()) return;
        active(row);
        if (mapper.suspend(id,row.version(),true,safeCode(code),Instant.now().toString())!=1) throw conflict();
        actions.settle(row.requirementId(),true);
    }
    @Transactional
    public void resume(String id, long version) {
        var row=active(require(id));
        if (row.version()!=version || !row.suspended()) throw conflict();
        if (mapper.suspend(id,version,false,null,Instant.now().toString())!=1) throw conflict();
        actions.resumeRequirement(row.requirementId());
    }
    @Transactional
    public void ended(Launch expected, String proofKind, Map<String,Object> evidence, boolean remoteFailed) {
        var row=active(expected); var attempt=attempt(row);
        if (!row.state().equals("RUNNING") || !Set.of("SESSION_TERMINAL","ABORT_CONFIRMED").contains(proofKind)) throw conflict();
        var accepted=nodes.findDelivery(attempt.id());
        boolean success=!remoteFailed && accepted.isPresent();
        var definition=definition(row);
        if (success && definition.completion().kind()==WorkflowGraph.CompletionKind.OUTCOME)
            success=Objects.equals(definition.completion().expectedOutcome(),accepted.get().outcome());
        proof(attempt,proofKind,evidence);
        nodes.finish(attempt,success?WorkflowAttemptState.SUCCEEDED:WorkflowAttemptState.FAILED);
        transition(row,success?WorkflowModelState.SUCCEEDED:WorkflowModelState.FAILED,success?LifecycleEvent.COMPLETE:LifecycleEvent.FAIL);
        actions.settle(row.requirementId(),!success,attempt.id());
    }
    @Transactional
    public void stopped(Launch expected, String kind, Map<String,Object> evidence) {
        var row=active(expected); var attempt=attempt(row);
        if (!row.state().equals("STOPPING") || !Set.of("NO_SESSION_CREATED","SESSION_ABSENT","CREATION_STOP_CONFIRMED").contains(kind)) throw conflict();
        if (kind.equals("NO_SESSION_CREATED")!=(row.creationPlanJson()==null)) throw conflict();
        proof(attempt,kind,evidence);
        nodes.finish(attempt,WorkflowAttemptState.CANCELLED);
        transition(row,WorkflowModelState.CANCELLED,LifecycleEvent.ABORT);
        actions.settle(row.requirementId(),true,attempt.id());
    }
    public Optional<WorkflowExecutionRows.Stop> stopProof(String id) { return execution.stop(id); }
    @Transactional
    public void writerStopped(Launch expected,String kind,Map<String,Object> evidence,boolean remoteFailed) {
        var row=active(expected);
        if (!io.opencode.loopper.runtime.WorkflowModelProfile.writer(attempt(row).adapterKey())) throw conflict();
        boolean cancelled=row.state().equals("STOPPING");
        if (!(cancelled?Set.of("NO_SESSION_CREATED","SESSION_ABSENT","CREATION_STOP_CONFIRMED"):Set.of("SESSION_TERMINAL","ABORT_CONFIRMED")).contains(kind)
                || !cancelled && !row.state().equals("RUNNING")
                || kind.equals("NO_SESSION_CREATED") && row.creationPlanJson()!=null) throw conflict();
        var details=new LinkedHashMap<String,Object>(evidence);details.put("remoteFailed",remoteFailed);
        proof(attempt(row),kind,details);
    }
    /** Called within the same transaction as workspace/queue release; no external effects belong here. */
    @Transactional(propagation=Propagation.MANDATORY)
    public void completeWriter(Launch expected,WorkflowDelivery delivery) { completeWriter(expected,delivery,true); }
    @Transactional(propagation=Propagation.MANDATORY)
    public void completeWriter(Launch expected,WorkflowDelivery delivery,boolean scopePassed) {
        var row=active(expected);var attempt=attempt(row);
        if (!io.opencode.loopper.runtime.WorkflowModelProfile.writer(attempt.adapterKey())) throw conflict();
        var stopped=stopProof(attempt.id()).orElseThrow(WorkflowCommands::conflict);
        boolean cancelled=row.state().equals("STOPPING");
        boolean success=!cancelled && delivery!=null && !encoding.decode(stopped.evidenceJson(),tools.jackson.databind.JsonNode.class).path("remoteFailed").asBoolean();
        if (success) {
            nodes.accept(attempt,delivery);
            if (definition(row).completion().kind()==WorkflowGraph.CompletionKind.OUTCOME)
                success=Objects.equals(definition(row).completion().expectedOutcome(),delivery.outcome());
        }
        success=success&&scopePassed;
        var next=cancelled?WorkflowModelState.CANCELLED:success?WorkflowModelState.SUCCEEDED:WorkflowModelState.FAILED;
        nodes.finish(attempt,WorkflowAttemptState.valueOf(next.name()));
        transition(row,next,cancelled?LifecycleEvent.ABORT:success?LifecycleEvent.COMPLETE:LifecycleEvent.FAIL);
        actions.settle(row.requirementId(),!success,attempt.id());
    }
    private void proof(WorkflowExecutionRows.Attempt attempt, String kind, Map<String,Object> evidence) {
        if (execution.stop(attempt.id()).isPresent() || execution.insertStop(new WorkflowExecutionRows.Stop(
                attempt.id(),kind,attempt.externalSessionId(),encoding.encode(evidence),Instant.now().toString()))!=1) throw conflict();
    }
    public OpenCodeClient.SessionCreationPlan plan(Launch row) { return encoding.decode(row.creationPlanJson(),OpenCodeClient.SessionCreationPlan.class); }
    public OpenCodeClient.PromptRequest prompt(Launch row) {
        var prompt=encoding.decode(row.promptJson(),WorkflowModelPrompt.Frozen.class).request();
        if (!OpenCodeClient.promptRequestSha256(prompt).equals(row.promptSha256())) throw conflict();
        return prompt;
    }
    public OpenCodeClient.OpenCodeSession remote(Launch row) {
        var plan=plan(row);
        return new OpenCodeClient.OpenCodeSession(attempt(row).externalSessionId(),plan.canonicalDirectory(),plan.runtimeGenerationId(),plan.internalMcpServer());
    }
    private void transition(Launch row, WorkflowModelState next, LifecycleEvent event) {
        lifecycle.transition(subject(row),row.state(),next.name(),event,null,Map.of(),
                ()->mapper.transition(row.attemptId(),row.version(),row.state(),next.name(),Instant.now().toString()),WorkflowCommands::conflict);
    }
    private LifecycleTransitionService.Subject subject(Launch row) {
        return new LifecycleTransitionService.Subject(LifecycleMachineType.WORKFLOW_MODEL,row.attemptId(),
                LifecycleScopeType.PROJECT,plans.require(row.requirementId()).projectId());
    }
    private static String safeCode(String code) { return code!=null && code.matches("[A-Z0-9_]{1,100}")?code:"WORKFLOW_MODEL_RECOVERY_REQUIRED"; }
}
