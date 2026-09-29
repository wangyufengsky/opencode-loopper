package io.opencode.loopper.service.workflow;

import io.opencode.loopper.persistence.WorkflowWorkspaceMapper.Workspace;
import io.opencode.loopper.runtime.*;
import io.opencode.loopper.service.*;
import io.opencode.loopper.workflow.*;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Resumes persisted workspace steps. Caller serializes one attempt, while the database lease excludes other writers. */
@Service
@Transactional(propagation=Propagation.NOT_SUPPORTED)
public class WorkflowWorkspaces {
    private final WorkflowWorkspaceStore store;
    private final WorkflowGitWorkspace git;
    private final WorkflowCodeSnapshots codes;
    private final WorkflowEncoding encoding;
    private final WorkflowDirectoryWorkspaces directories;
    public WorkflowWorkspaces(WorkflowWorkspaceStore store,WorkflowGitWorkspace git,WorkflowCodeSnapshots codes,WorkflowEncoding encoding,WorkflowDirectoryWorkspaces directories) {
        this.store=store;this.git=git;this.codes=codes;this.encoding=encoding;this.directories=directories;
    }
    public Workspace prepare(String id) {
        var row=store.find(id).orElse(null);
        if (row==null) {
            var context=store.context(id);var seed=seed(context);
            String seedTree=seed==null?null:seed.manifest().resultTree();
            var plan=directories.supports(context)?directories.inspect(context,seedTree):git.inspect(context.project().rootPath(),context.owner().id(),id,seedTree);
            if (seed!=null) {
                String prefix=Path.of(plan.canonicalRoot()).relativize(Path.of(plan.projectDirectory())).toString().replace('\\','/');
                if (!prefix.isEmpty()) prefix+="/";
                if (!seed.manifest().baseTree().equals(plan.baseTree()) || !seed.manifest().projectPrefix().equals(prefix))
                    throw new ConflictException("WORKFLOW_CODE_BASE_CHANGED","上游代码交付与当前项目基准不一致，请先明确合并或更新输入，不能覆盖当前代码");
            }
            row=store.reserve(context,plan,seed==null?null:seed.reference());
        }
        var identity=identity(row);var lease=store.holder(row,identity,false);
        if (!store.context(id).attempt().state().equals("PREPARING")) throw WorkflowWorkspaceStore.conflict();
        if (row.state().equals("READY")) { ready(row);return row; }
        if (!row.state().equals("PREPARING")) throw WorkflowWorkspaceStore.conflict();
        if(directory(row))directories.enter(row);else git.enter(row.plan());
        return store.ready(row,identity(row),lease.version());
    }
    public void requireReady(String id) {
        var row=store.require(id);
        if (!row.state().equals("READY")) throw WorkflowWorkspaceStore.conflict();
        store.holder(row,identity(row),false);ready(row);
    }
    public record ReleaseCheck(Workspace workspace,DirectWorkspaceLeaseCoordinator.WorkspaceIdentity identity,long leaseVersion) { }
    public ReleaseCheck checkRelease(String id) {
        var row=store.require(id);var identity=identity(row);
        var lease=store.holder(row,identity,true);
        if (!row.state().equals("RESTORED")) throw WorkflowWorkspaceStore.conflict();
        source(row);
        return new ReleaseCheck(row,identity,lease.version());
    }
    public Workspace capture(String id) {
        var row=store.require(id);var identity=identity(row);
        row=store.beginCapture(id,identity);
        if (row.state().equals("FROZEN")) return row;
        var lease=store.holder(row,identity,true);
        WorkflowWorkspaceCheckpoint checkpoint;
        if(directory(row))checkpoint=directories.capture(row);
        else {
            var captured=git.capture(row.plan(),row.requirementId(),row.attemptId());
            checkpoint=new WorkflowWorkspaceCheckpoint(captured.workspace().branch(),captured.workspace().head(),captured.checkpointRef(),captured.checkpointCommit(),captured.checkpointTree(),captured.stashCommit());
        }
        return store.frozen(row,identity(row),lease.version(),checkpoint);
    }
    public WorkflowCodeSnapshot.Reference codeDelivery(String id) {
        var row=store.require(id);
        if (!List.of("FROZEN","RESTORING","RESTORED").contains(row.state()) || row.checkpointTree()==null) throw WorkflowWorkspaceStore.conflict();
        var attempt=store.context(id).attempt();
        return codes.freeze(id,attempt.version(),row.baseTree(),row.checkpointTree());
    }
    public Workspace restore(String id) {
        var row=store.require(id);
        if (row.state().equals("PREPARING") && !(directory(row)?directories.onSource(row):git.onSource(row.plan()))) row=capture(id);
        var identity=identity(row);
        row=store.beginRestore(id,identity);
        if (row.state().equals("RESTORED")) { source(row);return row; }
        var lease=store.holder(row,identity,true);
        if(directory(row))directories.restore(row);else git.restore(row.plan());
        return store.restored(row,identity(row),lease.version());
    }
    public WorkspaceWriterQueue.Transfer release(String id) {
        var row=store.require(id);
        if (row.state().equals("RELEASED")) return store.released(id);
        var lease=store.holder(row,identity(row),true);source(row);
        return store.release(row,identity(row),lease.version());
    }
    private boolean directory(Workspace row) { return row.objectRepository()!=null; }
    private DirectWorkspaceLeaseCoordinator.WorkspaceIdentity identity(Workspace row) { return directory(row)?directories.identity(row.plan()):git.identity(row.plan()); }
    private void ready(Workspace row) { if(directory(row))directories.requireReady(row);else git.requireReady(row.plan()); }
    private void source(Workspace row) { if(directory(row))directories.requireSource(row);else git.requireSource(row.plan()); }
    private record Seed(WorkflowCodeSnapshot.Reference reference,WorkflowCodeSnapshot manifest) { }
    private Seed seed(WorkflowWorkspaceStore.Context context) {
        var available=context.inputs().values().stream().filter(input->input.kind()==WorkflowGraph.DataKind.CODE).toList();
        String selected=context.definition().parameters().get("workspaceInput");
        if (available.isEmpty() && selected==null) return null;
        if (selected==null && available.size()!=1) throw new BadRequestException("WORKFLOW_WORKSPACE_INPUT_REQUIRED","存在多个代码输入，请明确选择作为工作区基础的输入名称");
        var input=selected==null?available.getFirst():available.stream().filter(value->value.name().equals(selected)).findFirst()
                .orElseThrow(()->new BadRequestException("WORKFLOW_WORKSPACE_INPUT_REQUIRED","工作区输入名称不属于本节点的代码输入"));
        if (!input.source().equals("NODE") || input.attemptId()==null) throw new BadRequestException("WORKFLOW_CODE_INPUT_REQUIRED","工作区代码必须来自本需求已完成节点的固定交付");
        WorkflowCodeSnapshot.Reference reference;
        try { reference=encoding.decode(encoding.encode(input.content()),WorkflowCodeSnapshot.Reference.class); }
        catch (RuntimeException failure) { throw WorkflowWorkspaceStore.conflict(); }
        return new Seed(reference,codes.manifest(context.project().id(),context.owner().id(),input.attemptId(),reference));
    }
}
