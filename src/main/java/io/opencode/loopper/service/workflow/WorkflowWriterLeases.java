package io.opencode.loopper.service.workflow;

import io.opencode.loopper.domain.*;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.runtime.*;
import io.opencode.loopper.service.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Node admission shares the legacy FIFO. Identity and checkout/stop preflight must precede the caller's transaction. */
@Service
public class WorkflowWriterLeases {
    public static final String ADAPTER="model.write.v1";
    private final LoopperMapper mapper;
    private final WorkflowExecutionMapper execution;
    private final WorkflowNodeRuns nodes;
    private final WorkflowPlans plans;
    private final WorkspaceWriterQueue fifo;
    public WorkflowWriterLeases(LoopperMapper mapper,WorkflowExecutionMapper execution,WorkflowNodeRuns nodes,
            WorkflowPlans plans,LifecycleTransitionService lifecycle) {
        this.mapper=mapper; this.execution=execution; this.nodes=nodes; this.plans=plans; this.fifo=new WorkspaceWriterQueue(mapper,lifecycle);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public WorkflowWriterQueueRow admit(DirectWorkspaceLeaseCoordinator.WorkspaceIdentity identity,String attemptId) {
        var attempt=nodes.active(nodes.attempt(attemptId));
        if (!ADAPTER.equals(attempt.adapterKey()) || !attempt.state().equals("PREPARING") || execution.stop(attemptId).isPresent()) throw conflict();
        var node=execution.node(attempt.nodeRunId()).orElseThrow(WorkflowWriterLeases::conflict);
        var owner=plans.require(node.requirementId());
        requireNoOverlap(identity);
        var lease=mapper.findWorkspaceLease(identity.canonicalRoot()).orElse(null);
        var previous=mapper.findWorkflowWriter(attemptId).orElse(null);
        if (previous!=null) {
            if (!previous.canonicalRoot().equals(identity.canonicalRoot()) || !previous.rootFingerprint().equals(identity.rootFingerprint())) throw conflict();
            if (!Set.of("QUEUED","ADMITTED").contains(previous.state())) throw conflict();
            if (lease==null) throw conflict();
            WorkspaceWriterQueue.requireIdentity(lease,identity);
            if (previous.state().equals("ADMITTED") && (!attemptId.equals(lease.holderWorkflowAttemptId()) || lease.state().equals("RELEASED"))) throw conflict();
            return previous;
        }
        boolean admitted=lease==null || lease.state().equals("RELEASED");
        if (!admitted) WorkspaceWriterQueue.requireIdentity(lease,identity);
        String now=Instant.now().toString();
        var row=new WorkflowWriterQueueRow(attemptId,owner.id(),owner.projectId(),identity.canonicalRoot(),identity.rootFingerprint(),
                mapper.nextQueuePosition(identity.canonicalRoot()),admitted?"ADMITTED":"QUEUED",now,admitted?now:null,null,0);
        fifo.create(row);
        if (admitted) fifo.save(new WorkspaceLeaseRow(identity.canonicalRoot(),identity.rootFingerprint(),"DIRECT",null,null,
                "HELD",now,now,null,null,lease==null?0:lease.version(),attemptId));
        return row;
    }
    public WorkspaceLeaseRow requireWritable(DirectWorkspaceLeaseCoordinator.WorkspaceIdentity identity,String attemptId) {
        var row=requireHolder(identity,attemptId);
        if (!row.state().equals("HELD")) throw new ConflictException("WORKFLOW_WRITER_STOP_UNCONFIRMED","旧写入者尚未确认停止，不能启动写入");
        var attempt=nodes.active(nodes.attempt(attemptId));
        if (!Set.of("PREPARING","RUNNING").contains(attempt.state()) || execution.stop(attemptId).isPresent()) throw conflict();
        return row;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void markUnconfirmed(DirectWorkspaceLeaseCoordinator.WorkspaceIdentity identity,String attemptId) {
        var row=requireHolder(identity,attemptId);
        if (row.state().equals("RELEASE_PENDING")) return;
        fifo.save(new WorkspaceLeaseRow(row.canonicalRoot(),row.rootFingerprint(),"DIRECT",null,null,"RELEASE_PENDING",
                row.acquiredAt(),Instant.now().toString(),null,"WRITER_TERMINATION_UNCONFIRMED",row.version(),attemptId));
    }
    /** The workspace owner first captures its checkpoint and proves safe checkout restoration outside this transaction. */
    @Transactional(propagation=Propagation.MANDATORY)
    public WorkspaceWriterQueue.Transfer releaseAfterStopped(DirectWorkspaceLeaseCoordinator.WorkspaceIdentity identity,String attemptId,long expectedLeaseVersion) {
        var queue=mapper.findWorkflowWriter(attemptId).orElseThrow(WorkflowWriterLeases::conflict);
        var row=requireHolder(identity,attemptId);
        var attempt=nodes.attempt(attemptId);
        if (row.version()!=expectedLeaseVersion || !io.opencode.loopper.workflow.WorkflowAttemptState.valueOf(attempt.state()).terminal()
                || execution.stop(attemptId).isEmpty()) throw new ConflictException("WORKFLOW_WRITER_STOP_UNCONFIRMED","没有本次执行的停止证明，不能交出工作区");
        fifo.transition(queue,"FINISHED",LifecycleEvent.FINISH);
        return fifo.transfer(row,identity,"WORKFLOW_WRITER_STOPPED");
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public WorkflowWriterQueueRow cancelQueued(String attemptId) {
        var row=mapper.findWorkflowWriter(attemptId).orElseThrow(WorkflowWriterLeases::conflict);
        if (row.state().equals("CANCELLED")) return row;
        if (!row.state().equals("QUEUED")) throw conflict();
        return fifo.transition(row,"CANCELLED",LifecycleEvent.CANCEL);
    }
    private WorkspaceLeaseRow requireHolder(DirectWorkspaceLeaseCoordinator.WorkspaceIdentity identity,String attemptId) {
        var lease=mapper.findWorkspaceLease(identity.canonicalRoot()).orElseThrow(WorkflowWriterLeases::conflict);
        WorkspaceWriterQueue.requireIdentity(lease,identity);
        var queue=mapper.findWorkflowWriter(attemptId).orElseThrow(WorkflowWriterLeases::conflict);
        if (!queue.state().equals("ADMITTED") || !attemptId.equals(lease.holderWorkflowAttemptId()) || lease.state().equals("RELEASED")) throw conflict();
        return lease;
    }
    private void requireNoOverlap(DirectWorkspaceLeaseCoordinator.WorkspaceIdentity identity) {
        Path requested=Path.of(identity.canonicalRoot());
        for (var lease:mapper.blockingSourceWriters()) {
            Path held=Path.of(lease.canonicalRoot());
            if (!held.equals(requested) && (held.startsWith(requested) || requested.startsWith(held)))
                throw new ConflictException("WORKSPACE_OVERLAPPING_LEASE","同一仓库还有目录工作持有写入权，请等待其安全结束");
        }
    }
    private static ConflictException conflict() { return new ConflictException("WORKFLOW_WORKSPACE_CONFLICT","节点工作区身份、排队或持有状态已变化，请刷新后重试"); }
}
