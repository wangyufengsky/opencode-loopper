package io.opencode.loopper.runtime;

import io.opencode.loopper.domain.*;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.*;
import java.time.Instant;
import java.util.*;

/** Shared lease mutations and FIFO transfer. Callers supply stop/workspace proof and own a short transaction. */
public final class WorkspaceWriterQueue {
    private final WorkspaceLeaseMapper mapper;
    private final LifecycleTransitionService lifecycle;
    public WorkspaceWriterQueue(WorkspaceLeaseMapper mapper,LifecycleTransitionService lifecycle) { this.mapper=mapper; this.lifecycle=lifecycle; }
    public record Transfer(WorkspaceLeaseRow lease,TaskQueueRow task,WorkflowWriterQueueRow workflow,WorkflowWritebackQueueMapper.Row writeback) { }

    public Transfer transfer(WorkspaceLeaseRow previous,DirectWorkspaceLeaseCoordinator.WorkspaceIdentity identity,String reason) {
        requireTransaction(); requireIdentity(previous,identity);
        var task=mapper.nextQueuedTask(identity.canonicalRoot()).orElse(null);
        var workflow=mapper.nextWorkflowWriter(identity.canonicalRoot()).orElse(null);
        var writeback=mapper.nextWriteback(identity.canonicalRoot()).orElse(null);
        var positions=new ArrayList<Long>();
        if(task!=null)positions.add(task.position());if(workflow!=null)positions.add(workflow.position());if(writeback!=null)positions.add(writeback.position());
        if(new HashSet<>(positions).size()!=positions.size())throw failure("WORKSPACE_QUEUE_INCONSISTENT","写入排队顺序重复，已保留原租约");
        long first=positions.stream().mapToLong(Long::longValue).min().orElse(0);
        if(task!=null&&task.position()!=first)task=null;if(workflow!=null&&workflow.position()!=first)workflow=null;if(writeback!=null&&writeback.position()!=first)writeback=null;
        String now=Instant.now().toString();
        if (task!=null) {
            requireFingerprint(task.rootFingerprint(),identity.rootFingerprint());
            var admitted=new TaskQueueRow(task.taskId(),task.canonicalRoot(),task.rootFingerprint(),task.position(),
                    task.source(),"ADMITTED",task.enqueuedAt(),now,null,task.version());
            lifecycle.transition(new LifecycleTransitionService.Subject(LifecycleMachineType.TASK_QUEUE,task.taskId(),LifecycleScopeType.TASK,task.taskId()),
                    "QUEUED","ADMITTED",LifecycleEvent.ADMIT,null,Map.of(),()->mapper.updateTaskQueue(admitted),WorkspaceWriterQueue::conflict);
            task=mapper.findTaskQueue(task.taskId()).orElseThrow();
        } else if (workflow!=null) {
            requireFingerprint(workflow.rootFingerprint(),identity.rootFingerprint());
            if (!mapper.workflowWriterReady(workflow.attemptId()))
                throw failure("WORKFLOW_WRITER_QUEUE_STALE","等待节点已停止或不再属于当前计划，请先处理该节点的排队记录");
            workflow=transition(workflow,"ADMITTED",LifecycleEvent.ADMIT);
        } else if(writeback!=null) {
            requireFingerprint(writeback.rootFingerprint(),identity.rootFingerprint());
            if(!mapper.writebackReady(writeback.writebackId()))throw failure("WORKFLOW_WRITEBACK_QUEUE_STALE","等待的成果回填已变化，已保留原租约");
            writeback=transition(writeback,"ADMITTED",LifecycleEvent.ADMIT);
        }
        var next=new WorkspaceLeaseRow(previous.canonicalRoot(),previous.rootFingerprint(),"DIRECT",task==null?null:task.taskId(),null,
                task==null && workflow==null && writeback==null?"RELEASED":"HELD",task==null && workflow==null && writeback==null?previous.acquiredAt():now,
                now,task==null && workflow==null && writeback==null?now:null,task==null && workflow==null && writeback==null?reason:null,previous.version(),
                workflow==null?null:workflow.attemptId(),writeback==null?null:writeback.writebackId());
        save(next);
        return new Transfer(mapper.findWorkspaceLease(next.canonicalRoot()).orElseThrow(),task,workflow,writeback);
    }
    public WorkflowWriterQueueRow transition(WorkflowWriterQueueRow row,String next,LifecycleEvent event) {
        requireTransaction();
        String now=Instant.now().toString(), admitted=next.equals("ADMITTED")?now:row.admittedAt();
        String finished=Set.of("FINISHED","CANCELLED").contains(next)?now:null;
        lifecycle.transition(subject(row),row.state(),next,event,null,Map.of(),
                ()->mapper.transitionWorkflowWriter(row.attemptId(),row.version(),row.state(),next,admitted,finished),WorkspaceWriterQueue::conflict);
        return mapper.findWorkflowWriter(row.attemptId()).orElseThrow();
    }
    public void create(WorkflowWriterQueueRow row) {
        requireTransaction();
        lifecycle.create(subject(row),row.state(),Map.of("requirementId",row.requirementId()),()->mapper.insertWorkflowWriter(row),WorkspaceWriterQueue::conflict);
    }
    public WorkflowWritebackQueueMapper.Row transition(WorkflowWritebackQueueMapper.Row row,String next,LifecycleEvent event) {
        requireTransaction();String now=Instant.now().toString();
        lifecycle.transition(writebackSubject(row),row.state(),next,event,null,Map.of(),
                ()->mapper.transitionWritebackQueue(row.writebackId(),row.version(),row.state(),next,next.equals("ADMITTED")?now:row.admittedAt(),next.equals("FINISHED")?now:null),WorkspaceWriterQueue::conflict);
        return mapper.findWritebackQueue(row.writebackId()).orElseThrow();
    }
    public void create(WorkflowWritebackQueueMapper.Row row) {
        requireTransaction();lifecycle.create(writebackSubject(row),row.state(),Map.of(),()->mapper.insertWritebackQueue(row),WorkspaceWriterQueue::conflict);
    }
    private static LifecycleTransitionService.Subject writebackSubject(WorkflowWritebackQueueMapper.Row row) {
        return new LifecycleTransitionService.Subject(LifecycleMachineType.WORKFLOW_WRITEBACK_QUEUE,row.writebackId(),LifecycleScopeType.PROJECT,row.projectId());
    }
    public void save(WorkspaceLeaseRow row) {
        requireTransaction();
        var subject=new LifecycleTransitionService.Subject(LifecycleMachineType.WORKSPACE_LEASE,row.rootFingerprint(),LifecycleScopeType.WORKSPACE,row.rootFingerprint());
        var old=mapper.findWorkspaceLease(row.canonicalRoot()).orElse(null);
        if (old==null) { lifecycle.create(subject,row.state(),Map.of(),()->mapper.insertWorkspaceLease(row),WorkspaceWriterQueue::conflict); return; }
        if (old.version()!=row.version()) throw conflict();
        boolean same=old.state().equals(row.state());
        boolean transferred=!Objects.equals(old.holderTaskId(),row.holderTaskId())
                || !Objects.equals(old.holderWorkflowAttemptId(),row.holderWorkflowAttemptId())
                || !Objects.equals(old.holderWritebackId(),row.holderWritebackId());
        if (same && !transferred) lifecycle.mutateWithoutTransition(()->mapper.updateWorkspaceLeaseDetails(row),WorkspaceWriterQueue::conflict);
        else lifecycle.transition(subject,old.state(),row.state(),transferred && row.state().equals("HELD") && !old.state().equals("RELEASED")
                        ?LifecycleEvent.TRANSFER:null,row.releaseReason(),Map.of(),
                ()->same?mapper.updateWorkspaceLeaseDetails(row):mapper.updateWorkspaceLease(row),WorkspaceWriterQueue::conflict);
    }
    public static void requireIdentity(WorkspaceLeaseRow lease,DirectWorkspaceLeaseCoordinator.WorkspaceIdentity identity) {
        if (!lease.canonicalRoot().equals(identity.canonicalRoot()) || !lease.mode().equals("DIRECT")) throw conflict();
        requireFingerprint(lease.rootFingerprint(),identity.rootFingerprint());
    }
    private static void requireFingerprint(String stored,String observed) {
        if (!stored.equals(observed)) throw failure("DIRECT_WORKSPACE_FINGERPRINT_MISMATCH","工作区身份发生变化，拒绝释放或转移写入权");
    }
    private static LifecycleTransitionService.Subject subject(WorkflowWriterQueueRow row) {
        return new LifecycleTransitionService.Subject(LifecycleMachineType.WORKFLOW_WRITER_QUEUE,row.attemptId(),LifecycleScopeType.PROJECT,row.projectId());
    }
    private static void requireTransaction() {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Workspace FIFO mutations require a short transaction");
    }
    private static TaskFailure conflict() { return failure("DIRECT_LEASE_CONCURRENT_CONFLICT","工作区租约已变化，本次变更未接受"); }
    private static TaskFailure failure(String code,String text) { return new TaskFailure(code,text); }
}
