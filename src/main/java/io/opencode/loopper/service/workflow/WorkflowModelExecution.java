package io.opencode.loopper.service.workflow;

import io.opencode.loopper.domain.SessionFailure;
import io.opencode.loopper.persistence.WorkflowModelMapper.Launch;
import io.opencode.loopper.runtime.*;
import io.opencode.loopper.service.CandidateRuntimeBindingService;
import io.opencode.loopper.workflow.WorkflowModelState;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** External I/O uses frozen creation and message identities, followed by short CAS transactions. */
@Service
public class WorkflowModelExecution {
    private final WorkflowModelStore store;
    private final WorkflowModelPrompt prompts;
    private final WorkflowNodeRuns nodes;
    private final OpenCodeClient runtime;
    private final ConfiguredRoleRuntime roles;
    private final WorkflowWriterExecution writers;
    private final WorkflowHistoryAnalysisPreparation history;
    private final WorkflowSnapshotPreparation snapshot;
    private final org.springframework.beans.factory.ObjectProvider<CandidateRuntimeBindingService> bindings;
    public WorkflowModelExecution(WorkflowModelStore store, WorkflowModelPrompt prompts, WorkflowNodeRuns nodes,
            OpenCodeClient runtime, ConfiguredRoleRuntime roles, org.springframework.beans.factory.ObjectProvider<CandidateRuntimeBindingService> bindings, WorkflowWriterExecution writers, WorkflowHistoryAnalysisPreparation history, WorkflowSnapshotPreparation snapshot) {
        this.store=store; this.prompts=prompts; this.nodes=nodes; this.runtime=runtime; this.roles=roles; this.bindings=bindings;this.writers=writers;this.history=history;this.snapshot=snapshot;
    }
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public void advance(String id) {
        var row=store.require(id);
        if (WorkflowModelState.valueOf(row.state()).terminal() || row.suspended() && !row.state().equals("STOPPING")) return;
        if (writer(row) && store.stopProof(row.attemptId()).isPresent()) { writers.close(row);return; }
        switch (WorkflowModelState.valueOf(row.state())) {
            case PREPARING -> prepare(row);
            case CREATING -> create(row);
            case DISPATCHING -> dispatch(row);
            case RUNNING -> poll(row);
            case STOPPING -> stop(row);
            default -> { }
        }
    }
    private void prepare(Launch row) {
        store.active(row);
        history.prepare(row);
        if(snapshot.prepare(row))return;
        if (writer(row) && !writers.prepare(row)) return;
        byte[] nonce=new byte[32]; new SecureRandom().nextBytes(nonce);
        var model=store.encoding().decode(row.modelJson(),OpenCodeClient.OpenCodeModel.class);
        var plan=runtime.prepareSessionCreation(Path.of(row.directory()),"流程节点 "+store.definition(row).title(),model,
                WorkflowModelProfile.profile(store.attempt(row).adapterKey()),Base64.getUrlEncoder().withoutPadding().encodeToString(nonce));
        if (!plan.managed() || plan.internalMcpServer()==null) throw failure("WORKFLOW_MANAGED_RUNTIME_REQUIRED");
        plan=roles.prepare(plan,new OpenCodeClient.RoleContext("WORKFLOW_ATTEMPT",row.attemptId(),WorkflowModelProfile.slot(store.attempt(row).adapterKey())));
        store.prepared(row,plan,prompts.build(row));
    }
    private void create(Launch row) {
        if (writer(row)) writers.requireReady(row);
        var plan=store.plan(row); var lookup=runtime.findSessionsByExactTitle(plan);
        if (!lookup.supported()) throw failure("WORKFLOW_SESSION_LOOKUP_UNKNOWN");
        if (lookup.matches().size()>1) throw failure("WORKFLOW_SESSION_AMBIGUOUS");
        store.active(row);
        var attested=lookup.matches().isEmpty()?runtime.createSession(plan):lookup.matches().getFirst();
        var binding=bindings.getIfAvailable();
        if (binding==null) throw failure("WORKFLOW_RUNTIME_GUARD_REQUIRED");
        binding.bindInternalAttested(attested,plan);
        roles.created(attested.remoteId(),plan);
        store.attached(row,attested);
    }
    private void dispatch(Launch row) {
        var plan=store.plan(row); var remote=store.remote(row); var prompt=store.prompt(row);
        runtime.restoreDesignTurn(remote,plan.profile(),plan.model(),prompt.messageId());
        var lookup=runtime.findPromptMessage(remote,prompt,row.promptSha256());
        if (!lookup.supported()) throw failure("WORKFLOW_MESSAGE_LOOKUP_UNKNOWN");
        if (lookup.exists() && !row.promptSha256().equals(lookup.verifiedRequestSha256())) throw failure("WORKFLOW_MESSAGE_CHANGED");
        if (!lookup.exists()) { store.active(row); if (writer(row)) writers.requireReady(row); runtime.promptAsync(remote,prompt); }
        store.dispatched(row);
    }
    private void poll(Launch row) {
        var plan=store.plan(row); var remote=store.remote(row);
        runtime.restoreDesignTurn(remote,plan.profile(),plan.model(),store.prompt(row).messageId());
        var status=runtime.sessionStatus(remote);
        if (status.completed() || status.failed()) {
            ended(row,"SESSION_TERMINAL",Map.of("state",status.state(),"messageId",store.prompt(row).messageId()),status.failed());
            return;
        }
        var accepted=writer(row)?writers.candidateTime(row):nodes.findDelivery(row.attemptId()).map(value->value.createdAt());
        if (accepted.isPresent() && Instant.now().isAfter(Instant.parse(accepted.get()).plusSeconds(60))) {
            runtime.abortWithConfirmation(remote);
            ended(row,"ABORT_CONFIRMED",Map.of("reason","ACCEPTED_DELIVERY","messageId",store.prompt(row).messageId()),false);
        }
    }
    private boolean writer(Launch row) { return WorkflowModelProfile.writer(store.attempt(row).adapterKey()); }
    private void ended(Launch row,String kind,Map<String,Object> evidence,boolean failed) {
        if (!writer(row)) { store.ended(row,kind,evidence,failed);return; }
        store.writerStopped(row,kind,evidence,failed);writers.close(row);
    }
    private void stopped(Launch row,String kind,Map<String,Object> evidence) {
        if (!writer(row)) { store.stopped(row,kind,evidence);return; }
        store.writerStopped(row,kind,evidence,false);writers.close(row);
    }
    private void stop(Launch row) {
        try { stopRemote(row); }
        catch (RuntimeException unknown) {
            if (writer(row) && store.stopProof(row.attemptId()).isEmpty()) writers.retainUnknownStop(row);
            throw unknown;
        }
    }
    private void stopRemote(Launch row) {
        if (row.creationPlanJson()==null) { stopped(row,"NO_SESSION_CREATED",Map.of("phase","PREPARING")); return; }
        var plan=store.plan(row); var lookup=runtime.findSessionsByExactTitle(plan);
        if (!lookup.supported()) throw failure("WORKFLOW_STOP_UNKNOWN");
        Set<String> stopped=new TreeSet<>();
        for (var match:lookup.matches()) {
            if (!plan.equals(match.plan())) throw failure("WORKFLOW_STOP_IDENTITY_MISMATCH");
            runtime.abortWithConfirmation(match.session()); stopped.add(match.remoteId());
        }
        var known=store.attempt(row).externalSessionId();
        if (known!=null && !stopped.contains(known)) {
            runtime.abortWithConfirmation(store.remote(row)); stopped.add(known);
        }
        stopped(row,stopped.isEmpty()?"SESSION_ABSENT":"CREATION_STOP_CONFIRMED",
                Map.of("creationRequestSha256",plan.createRequestSha256(),"confirmedSessions",List.copyOf(stopped),"exactLookup",true));
    }
    private static SessionFailure failure(String code) { return new SessionFailure(code,"模型会话或请求身份尚未核定，已保留原执行；请恢复原运行，不要重复创建"); }
}
