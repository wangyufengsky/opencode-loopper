package io.opencode.loopper.service.workflow;

import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.runtime.*;
import io.opencode.loopper.service.ConflictException;
import io.opencode.loopper.workflow.WorkflowWriteback.Intent;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** The third shared FIFO participant. This owner never borrows a finished node's authority. */
@Service
public class WorkflowWritebackLeases {
    private final LoopperMapper mapper;
    private final WorkspaceWriterQueue fifo;
    public WorkflowWritebackLeases(LoopperMapper mapper,LifecycleTransitionService lifecycle){this.mapper=mapper;fifo=new WorkspaceWriterQueue(mapper,lifecycle);}
    @Transactional(propagation=Propagation.MANDATORY)
    public void admit(WorkflowWritebackMapper.Row row,Intent intent) {
        var identity=identity(intent);Path requested=Path.of(identity.canonicalRoot());
        for(var lease:mapper.blockingSourceWriters()) {
            Path held=Path.of(lease.canonicalRoot());
            if(!held.equals(requested)&&(held.startsWith(requested)||requested.startsWith(held)))
                throw new ConflictException("WORKSPACE_OVERLAPPING_LEASE","原目录或其子目录仍有其他工作持有写入权，请等待其安全结束。");
        }
        if(mapper.findWritebackQueue(row.id()).isPresent())throw WorkflowWritebacks.changed();
        var lease=mapper.findWorkspaceLease(identity.canonicalRoot()).orElse(null);
        boolean admitted=lease==null||lease.state().equals("RELEASED");
        if(!admitted)WorkspaceWriterQueue.requireIdentity(lease,identity);
        String now=Instant.now().toString();
        fifo.create(new WorkflowWritebackQueueMapper.Row(row.id(),row.projectId(),identity.canonicalRoot(),identity.rootFingerprint(),
                mapper.nextQueuePosition(identity.canonicalRoot()),admitted?"ADMITTED":"QUEUED",now,admitted?now:null,null,0));
        if(admitted)fifo.save(new WorkspaceLeaseRow(identity.canonicalRoot(),identity.rootFingerprint(),"DIRECT",null,null,"HELD",now,now,null,null,
                lease==null?0:lease.version(),null,row.id()));
    }
    public WorkspaceLeaseRow requireHolder(String id,Intent intent) {
        var lease=mapper.findWorkspaceLease(intent.before().canonicalRoot()).orElseThrow(WorkflowWritebacks::changed);
        WorkspaceWriterQueue.requireIdentity(lease,identity(intent));
        var queue=queue(id);
        if(!id.equals(lease.holderWritebackId())||!lease.state().equals("HELD")||!queue.state().equals("ADMITTED"))throw WorkflowWritebacks.changed();
        return lease;
    }
    public WorkflowWritebackQueueMapper.Row queue(String id){return mapper.findWritebackQueue(id).orElseThrow(WorkflowWritebacks::changed);}
    public long position(WorkflowWritebackQueueMapper.Row row){return row.state().equals("QUEUED")?mapper.writerQueuePosition(row.canonicalRoot(),row.position()):0;}
    /** Called only after the file owner proves the final directory and has stopped all writes. */
    @Transactional(propagation=Propagation.MANDATORY)
    public void release(String id,Intent intent,long leaseVersion) {
        var lease=requireHolder(id,intent);if(lease.version()!=leaseVersion)throw WorkflowWritebacks.changed();
        fifo.transition(queue(id),"FINISHED",LifecycleEvent.FINISH);
        fifo.transfer(lease,identity(intent),"WORKFLOW_WRITEBACK_APPLIED");
    }
    private static DirectWorkspaceLeaseCoordinator.WorkspaceIdentity identity(Intent intent) {
        return new DirectWorkspaceLeaseCoordinator.WorkspaceIdentity(intent.before().canonicalRoot(),intent.before().rootFingerprint());
    }
}
