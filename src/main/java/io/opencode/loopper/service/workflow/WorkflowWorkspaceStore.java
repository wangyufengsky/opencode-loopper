package io.opencode.loopper.service.workflow;

import io.opencode.loopper.domain.*;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.persistence.WorkflowWorkspaceMapper.Workspace;
import io.opencode.loopper.runtime.*;
import io.opencode.loopper.runtime.DirectWorkspaceLeaseCoordinator.WorkspaceIdentity;
import io.opencode.loopper.service.ConflictException;
import io.opencode.loopper.workflow.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Short persistence boundaries for workspace effects. A stopped writer retains ownership until proven restored. */
@Service
@Transactional(readOnly=true)
public class WorkflowWorkspaceStore {
    private final WorkflowWorkspaceMapper mapper;
    private final WorkflowExecutionMapper execution;
    private final WorkflowNodeRuns nodes;
    private final WorkflowPlanMapper plans;
    private final LoopperMapper projects;
    private final WorkflowWriterLeases leases;
    private final LifecycleTransitionService lifecycle;
    private final WorkflowEncoding encoding;
    public WorkflowWorkspaceStore(WorkflowWorkspaceMapper mapper,WorkflowExecutionMapper execution,WorkflowNodeRuns nodes,
            WorkflowPlanMapper plans,LoopperMapper projects,WorkflowWriterLeases leases,LifecycleTransitionService lifecycle,WorkflowEncoding encoding) {
        this.mapper=mapper;this.execution=execution;this.nodes=nodes;this.plans=plans;this.projects=projects;this.leases=leases;this.lifecycle=lifecycle;
        this.encoding=encoding;
    }
    public record Context(WorkflowExecutionRows.Attempt attempt,WorkflowRows.Requirement owner,ProjectRow project,
            WorkflowGraph.Node definition,WorkflowDelivery.Inputs inputs) { }
    public Context context(String id) {
        var attempt=nodes.attempt(id);var node=execution.node(attempt.nodeRunId()).orElseThrow(WorkflowWorkspaceStore::conflict);
        var owner=plans.find(node.requirementId()).orElseThrow(WorkflowWorkspaceStore::conflict);
        return new Context(attempt,owner,projects.findProject(owner.projectId()).orElseThrow(WorkflowWorkspaceStore::conflict),
                nodes.definition(node),nodes.inputs(attempt));
    }
    public Optional<Workspace> find(String id) { return mapper.find(id); }
    public Workspace require(String id) { return find(id).orElseThrow(WorkflowWorkspaceStore::conflict); }
    @Transactional
    public Workspace reserve(Context expected,WorkflowWorkspacePlan plan,WorkflowCodeSnapshot.Reference seed) {
        var current=context(expected.attempt().id());
        if (current.project().version()!=expected.project().version() || !current.project().rootPath().equals(expected.project().rootPath())
                || current.attempt().version()!=expected.attempt().version() || !current.attempt().state().equals("PREPARING")) throw conflict();
        leases.requireWritable(identity(plan),current.attempt().id());
        var existing=find(current.attempt().id()).orElse(null);
        if (existing!=null) {
            if (!existing.plan().equals(plan) || !Objects.equals(existing.seedSnapshotId(),seed==null?null:seed.snapshotId())
                    || !Objects.equals(existing.seedSha256(),seed==null?null:seed.sha256())) throw conflict();
            return existing;
        }
        String now=Instant.now().toString();
        var row=new Workspace(current.attempt().id(),current.owner().id(),current.project().id(),"PREPARING",plan.projectDirectory(),
                plan.canonicalRoot(),plan.rootFingerprint(),plan.sourceBranch(),plan.sourceCommit(),plan.baseTree(),plan.branch(),
                plan.checkpointRef(),seed==null?null:seed.snapshotId(),seed==null?null:seed.sha256(),plan.seedTree(),
                null,null,null,null,0,now,now,plan.objectRepository());
        lifecycle.create(subject(row),row.state(),Map.of(),()->mapper.insert(row),WorkflowWorkspaceStore::conflict);
        return row;
    }
    public WorkspaceLeaseRow holder(Workspace row,WorkspaceIdentity identity,boolean stopped) {
        if (!identity.equals(identity(row.plan()))) throw conflict();
        var lease=projects.findWorkspaceLease(identity.canonicalRoot()).orElseThrow(WorkflowWorkspaceStore::conflict);
        WorkspaceWriterQueue.requireIdentity(lease,identity);
        var queue=projects.findWorkflowWriter(row.attemptId()).orElseThrow(WorkflowWorkspaceStore::conflict);
        if (!row.attemptId().equals(lease.holderWorkflowAttemptId()) || lease.state().equals("RELEASED") || !queue.state().equals("ADMITTED")) throw conflict();
        var attempt=nodes.attempt(row.attemptId());
        if (!WorkflowWriterLeases.ADAPTER.equals(attempt.adapterKey()) || stopped && execution.stop(attempt.id()).isEmpty()) throw conflict();
        if (!stopped) leases.requireWritable(identity,row.attemptId());
        return lease;
    }
    @Transactional
    public Workspace ready(Workspace expected,WorkspaceIdentity identity,long leaseVersion) {
        var row=current(expected);requireLease(row,identity,leaseVersion,false);
        if (!nodes.attempt(row.attemptId()).state().equals("PREPARING")) throw conflict();
        return transition(row,WorkflowWorkspaceState.READY,LifecycleEvent.PREPARATION_SUCCEEDED);
    }
    @Transactional
    public Workspace beginCapture(String id,WorkspaceIdentity identity) {
        var row=require(id);holder(row,identity,true);nodes.active(nodes.attempt(id));
        if (row.state().equals("CAPTURING") || row.state().equals("FROZEN")) return row;
        return transition(row,WorkflowWorkspaceState.CAPTURING,LifecycleEvent.CAPTURE_WORKSPACE);
    }
    @Transactional
    public Workspace frozen(Workspace expected,WorkspaceIdentity identity,long leaseVersion,WorkflowWorkspaceCheckpoint checkpoint) {
        var row=current(expected);requireLease(row,identity,leaseVersion,true);nodes.active(nodes.attempt(row.attemptId()));
        if (!checkpoint.reference().equals(row.checkpointRef()) || !checkpoint.branch().equals(row.branch())
                || !checkpoint.sourceCommit().equals(row.sourceCommit())) throw conflict();
        if (mapper.checkpoint(row.attemptId(),row.version(),checkpoint.commit(),checkpoint.tree(),checkpoint.stash(),Instant.now().toString())!=1) throw conflict();
        return transition(require(row.attemptId()),WorkflowWorkspaceState.FROZEN,LifecycleEvent.COMPLETE);
    }
    @Transactional
    public Workspace beginRestore(String id,WorkspaceIdentity identity) {
        var row=require(id);holder(row,identity,true);
        if (row.state().equals("RESTORING") || row.state().equals("RESTORED")) return row;
        boolean unused=row.state().equals("PREPARING");
        if (unused && nodes.attempt(id).externalSessionId()!=null) throw conflict();
        return transition(row,WorkflowWorkspaceState.RESTORING,unused?LifecycleEvent.CANCEL:LifecycleEvent.RESTORE);
    }
    @Transactional
    public Workspace restored(Workspace expected,WorkspaceIdentity identity,long leaseVersion) {
        var row=current(expected);requireLease(row,identity,leaseVersion,true);
        return transition(row,WorkflowWorkspaceState.RESTORED,LifecycleEvent.COMPLETE);
    }
    @Transactional
    public WorkspaceWriterQueue.Transfer release(Workspace expected,WorkspaceIdentity identity,long leaseVersion) {
        var row=current(expected);requireLease(row,identity,leaseVersion,true);
        if (!row.state().equals("RESTORED")) throw conflict();
        var transfer=leases.releaseAfterStopped(identity,row.attemptId(),leaseVersion);
        if (mapper.releaseReceipt(row.attemptId(),row.version(),encoding.encode(transfer),Instant.now().toString())!=1) throw conflict();
        transition(require(row.attemptId()),WorkflowWorkspaceState.RELEASED,LifecycleEvent.RELEASE);
        return transfer;
    }
    public WorkspaceWriterQueue.Transfer released(String id) {
        var row=require(id);
        if (!row.state().equals("RELEASED") || row.releaseReceiptJson()==null) throw conflict();
        return encoding.decode(row.releaseReceiptJson(),WorkspaceWriterQueue.Transfer.class);
    }
    private Workspace current(Workspace expected) {
        var row=require(expected.attemptId());if (!row.equals(expected)) throw conflict();return row;
    }
    private void requireLease(Workspace row,WorkspaceIdentity identity,long version,boolean stopped) { if(holder(row,identity,stopped).version()!=version) throw conflict(); }
    private Workspace transition(Workspace row,WorkflowWorkspaceState next,LifecycleEvent event) {
        lifecycle.transition(subject(row),row.state(),next.name(),event,null,Map.of(),
                ()->mapper.transition(row.attemptId(),row.version(),row.state(),next.name(),Instant.now().toString()),WorkflowWorkspaceStore::conflict);
        return require(row.attemptId());
    }
    private LifecycleTransitionService.Subject subject(Workspace row) {
        return new LifecycleTransitionService.Subject(LifecycleMachineType.WORKFLOW_WORKSPACE,row.attemptId(),LifecycleScopeType.PROJECT,row.projectId());
    }
    private static WorkspaceIdentity identity(WorkflowWorkspacePlan plan) { return new WorkspaceIdentity(plan.canonicalRoot(),plan.rootFingerprint()); }
    static ConflictException conflict() { return new ConflictException("WORKFLOW_WORKSPACE_CONFLICT","节点工作区记录、尝试或租约已变化，请按原记录恢复"); }
}
