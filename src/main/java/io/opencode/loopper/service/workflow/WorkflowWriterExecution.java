package io.opencode.loopper.service.workflow;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.persistence.WorkflowModelMapper.Launch;
import io.opencode.loopper.runtime.*;
import io.opencode.loopper.service.TaskService;
import io.opencode.loopper.workflow.*;
import java.nio.file.Path;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

/** External writer effects surround a single final transaction: output, terminal state and writer handoff. */
@Service
@Transactional(propagation=Propagation.NOT_SUPPORTED)
public class WorkflowWriterExecution {
    private final WorkflowModelStore models;
    private final WorkflowTestScopes testScopes;
    private final WorkflowWorkspaces workspaces;
    private final WorkflowWorkspaceStore workspaceStore;
    private final WorkflowWriterLeases leases;
    private final LoopperMapper mapper;
    private final WorkflowWriterCandidates candidates;
    private final TransactionTemplate transactions;
    private final ObjectProvider<TaskService> tasks;
    public WorkflowWriterExecution(WorkflowModelStore models,WorkflowWorkspaces workspaces,WorkflowWorkspaceStore workspaceStore,
            WorkflowWriterLeases leases,LoopperMapper mapper,WorkflowWriterCandidates candidates,TransactionTemplate transactions,ObjectProvider<TaskService> tasks,WorkflowTestScopes testScopes) {
        this.models=models;this.workspaces=workspaces;this.testScopes=testScopes;this.workspaceStore=workspaceStore;this.leases=leases;
        this.mapper=mapper;this.candidates=candidates;this.transactions=transactions;this.tasks=tasks;
    }
    public boolean prepare(Launch row) {
        if (queue(row).state().equals("QUEUED")) return false;
        workspaces.prepare(row.attemptId());testScopes.prepare(row.attemptId());return true;
    }
    public void requireReady(Launch row) { workspaces.requireReady(row.attemptId());testScopes.prepare(row.attemptId()); }
    public Optional<String> candidateTime(Launch row) { return candidates.find(row.attemptId()).map(WorkflowWriterCandidateMapper.Candidate::createdAt); }
    public void retainUnknownStop(Launch row) {
        var queue=queue(row);
        if (!queue.state().equals("ADMITTED")) return;
        var identity=identity(row,queue);
        transactions.executeWithoutResult(tx->leases.markUnconfirmed(identity,row.attemptId()));
    }
    public void close(Launch row) {
        if (models.stopProof(row.attemptId()).isEmpty()) throw WorkflowCommands.conflict();
        var queue=queue(row);
        if (queue.state().equals("QUEUED")) {
            transactions.executeWithoutResult(tx->{ leases.cancelQueued(row.attemptId());models.completeWriter(row,null); });
            return;
        }
        var workspace=workspaceStore.find(row.attemptId()).orElse(null);
        if (workspace==null) {
            // Reservation precedes every Git mutation. No reservation and no creation plan proves no workspace effects.
            if (!row.state().equals("STOPPING") || row.creationPlanJson()!=null) throw WorkflowCommands.conflict();
            var identity=identity(row,queue);var lease=mapper.findWorkspaceLease(queue.canonicalRoot()).orElseThrow(WorkflowCommands::conflict);
            var transfer=transactions.execute(tx->{
                models.completeWriter(row,null);
                return leases.releaseAfterStopped(identity,row.attemptId(),lease.version());
            });
            handoff(transfer);return;
        }
        if (Set.of("PREPARING","READY","CAPTURING").contains(workspace.state())) {
            if (workspace.state().equals("PREPARING") && row.creationPlanJson()==null) workspaces.restore(row.attemptId());
            else workspaces.capture(row.attemptId());
        }
        WorkflowDelivery delivery=null;boolean scopePassed=true;
        if (!row.state().equals("STOPPING") && candidates.find(row.attemptId()).isPresent()) {
            boolean needsCode=models.definition(row).outputs().stream().anyMatch(output->output.kind()==WorkflowGraph.DataKind.CODE);
            var code=needsCode?workspaces.codeDelivery(row.attemptId()):null;
            delivery=candidates.delivery(row,code);
            if(WorkflowTestWrite.supports(models.definition(row).moduleId())){var scope=testScopes.verify(row.attemptId(),code);scopePassed=scope.passed();delivery=candidates.scope(row,delivery,scope.reportJson());}
        }
        workspaces.restore(row.attemptId());
        var checked=workspaces.checkRelease(row.attemptId());var result=delivery;boolean verified=scopePassed;
        var transfer=transactions.execute(tx->{
            models.completeWriter(row,result,verified);
            return workspaceStore.release(checked.workspace(),checked.identity(),checked.leaseVersion());
        });
        handoff(transfer);
    }
    private void handoff(WorkspaceWriterQueue.Transfer transfer) {
        if (transfer!=null && transfer.task()!=null) tasks.getObject().continueAdmittedTask(transfer.task().taskId(),transfer.task().position());
    }
    private WorkflowWriterQueueRow queue(Launch row) { return mapper.findWorkflowWriter(row.attemptId()).orElseThrow(WorkflowCommands::conflict); }
    private static DirectWorkspaceLeaseCoordinator.WorkspaceIdentity identity(Launch row,WorkflowWriterQueueRow queue) {
        var actual=DirectWorkspaceLeaseCoordinator.identify(Path.of(row.directory()),queue.canonicalRoot());
        if (!actual.rootFingerprint().equals(queue.rootFingerprint())) throw WorkflowCommands.conflict();
        return actual;
    }
}
