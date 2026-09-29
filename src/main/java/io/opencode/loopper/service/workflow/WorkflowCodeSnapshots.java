package io.opencode.loopper.service.workflow;

import io.opencode.loopper.runtime.*;
import io.opencode.loopper.service.NotFoundException;
import io.opencode.loopper.workflow.WorkflowCodeSnapshot;
import java.nio.file.Path;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Recoverable capture: commit intent, copy exact objects, then publish the complete immutable manifest. */
@Service
@Transactional(propagation=Propagation.NOT_SUPPORTED)
public class WorkflowCodeSnapshots {
    private final WorkflowCodeStore store;
    private final GitCodeSnapshots git;
    private final SafeProcessRunner runner;
    private final WorkflowCodeContent content;
    public WorkflowCodeSnapshots(WorkflowCodeStore store,GitCodeSnapshots git,SafeProcessRunner runner,WorkflowCodeContent content) {
        this.store=store; this.git=git; this.runner=runner;
        this.content=content;
    }
    /** Trees come from the server's stopped-writer checkpoint, never model-supplied paths or object IDs. */
    public WorkflowCodeSnapshot.Reference freeze(String attemptId,long attemptVersion,String baseline,String result) {
        var context=store.context(attemptId);
        if (context.attempt().version()!=attemptVersion) throw WorkflowCodeStore.invalid();
        var workspace=store.workspace(attemptId).orElse(null);
        Path project=Path.of(context.project().rootPath());
        var identity=DirectWorkspaceLeaseCoordinator.identify(project);
        var row=workspace!=null&&workspace.objectRepository()!=null
                ?store.prepareDirectory(context,identity,workspace,baseline,result)
                :store.prepare(context,identity,GitProjectScope.require(runner,project),baseline,result);
        var captured=git.capture(Path.of(row.objectRepository()==null?row.repository():row.objectRepository()),row.baseTree(),row.resultTree(),row.projectPrefix(),
                (hash,bytes)->content.write(row.id(),hash,bytes));
        var manifest=new WorkflowCodeSnapshot(1,row.id(),row.projectId(),row.requirementId(),row.attemptId(),row.inputsSha256(),
                row.baseTree(),row.resultTree(),row.projectPrefix(),captured.files(),captured.changes());
        return store.publish(row,attemptVersion,DirectWorkspaceLeaseCoordinator.identify(project),manifest);
    }
    WorkflowCodeSnapshot stoppedManifest(String id,WorkflowCodeSnapshot.Reference reference){return store.stoppedManifest(id,reference);}
    WorkflowCodeSnapshot outputManifest(String project,String requirement,String attempt,WorkflowCodeSnapshot.Reference reference) {
        return store.readOutput(project,requirement,attempt,reference);
    }
    public WorkflowCodeSnapshot manifest(String project,String requirement,String attempt,WorkflowCodeSnapshot.Reference reference) {
        return store.read(project,requirement,attempt,reference);
    }
    public byte[] read(String project,String requirement,String attempt,WorkflowCodeSnapshot.Reference reference,String path) {
        var manifest=store.read(project,requirement,attempt,reference);
        var file=manifest.files().stream().filter(entry->entry.path().equals(path)).findFirst()
                .orElseThrow(()->new NotFoundException("冻结代码清单中不存在此文件"));
        // No fallback to Git HEAD or the current checkout, even if the managed bytes are missing.
        return content.read(manifest,file);
    }
    /** Internal bulk consumer already authorized this immutable manifest through manifest(...). */
    byte[] readAcceptedFile(WorkflowCodeSnapshot manifest,WorkflowCodeSnapshot.File file) {
        return content.read(manifest,file);
    }
}
