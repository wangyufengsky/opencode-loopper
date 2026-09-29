package io.opencode.loopper.service.workflow;

import io.opencode.loopper.persistence.WorkflowCodeMapper;
import io.opencode.loopper.persistence.WorkflowWorkspaceMapper.Workspace;
import io.opencode.loopper.runtime.DirectWorkspaceLeaseCoordinator;
import io.opencode.loopper.workflow.*;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.*;
import org.springframework.stereotype.Component;

/** Plain-directory writer adapter. The shared workspace ledger still owns queue, stop and lifecycle authority. */
@Component
public final class WorkflowDirectoryWorkspaces {
    private final WorkflowDirectoryPreparation preparation;
    private final WorkflowDirectoryStore store;
    private final WorkflowDirectoryFiles files;
    private final GitDirectoryTrees trees;
    private final WorkflowDirectoryStorage storage;
    private final WorkflowDirectoryMutations mutations;
    private final WorkflowCodeSnapshots codes;
    private final WorkflowCodeMapper code;
    public WorkflowDirectoryWorkspaces(WorkflowDirectoryPreparation preparation,WorkflowDirectoryStore store,WorkflowDirectoryFiles files,
            GitDirectoryTrees trees,WorkflowDirectoryStorage storage,WorkflowDirectoryMutations mutations,WorkflowCodeSnapshots codes,WorkflowCodeMapper code) {
        this.preparation=preparation;this.store=store;this.files=files;this.trees=trees;this.storage=storage;this.mutations=mutations;this.codes=codes;this.code=code;
    }
    public WorkflowWorkspacePlan inspect(WorkflowWorkspaceStore.Context context,String seedTree) {
        var prepared=preparation.prepare(context);
        return new WorkflowWorkspacePlan(prepared.canonicalRoot(),prepared.canonicalRoot(),prepared.rootFingerprint(),"source",prepared.sourceCommit(),prepared.baseTree(),
                "loopper/workflow-"+prepared.attemptId(),"refs/loopper/checkpoints/"+prepared.requirementId()+"/"+prepared.attemptId(),seedTree,prepared.objectRepository());
    }
    public boolean supports(WorkflowWorkspaceStore.Context context) {
        GitDirectoryTrees.outsideTransaction();if(store.hasPreparation(context.attempt().id()))return true;
        try{return !Files.exists(io.opencode.loopper.runtime.GitProjectScope.checkoutRoot(Path.of(context.project().rootPath())).resolve(".git"),LinkOption.NOFOLLOW_LINKS);}
        catch(java.io.IOException failure){throw GitDirectoryTrees.invalid();}
    }
    public void enter(Workspace row) {
        identity(row.plan());var baseline=baseline(row);Path repository=Path.of(row.objectRepository());
        trees.reference(repository,"refs/heads/"+row.sourceBranch(),row.sourceCommit());
        trees.reference(repository,"refs/heads/"+row.branch(),row.sourceCommit());trees.select(repository,row.branch());
        if(row.seedSnapshotId()!=null) {
            var source=code.snapshot(row.seedSnapshotId()).orElseThrow(WorkflowCodeStore::invalid);
            var ref=new WorkflowCodeSnapshot.Reference(1,row.seedSnapshotId(),row.seedSha256());
            var accepted=codes.manifest(row.projectId(),row.requirementId(),source.attemptId(),ref);
            if(!accepted.projectPrefix().isEmpty()||!accepted.baseTree().equals(row.baseTree())||!accepted.resultTree().equals(row.seedTree()))throw WorkflowCodeStore.invalid();
            var seed=new WorkflowDirectorySnapshot(1,row.canonicalRoot(),row.rootFingerprint(),accepted.files());
            var intent=store.apply(row,"SEED",baseline,seed);
            storage.importFiles(row.attemptId(),seed,file->codes.readAcceptedFile(accepted,file));
            if(!trees.store(repository,seed.files(),file->storage.read(row.attemptId(),file)).equals(row.seedTree()))throw GitDirectoryTrees.invalid();
            mutations.apply(intent);
        }
        requireReady(row);
    }
    public void requireReady(Workspace row) {
        identity(row.plan());var expected=row.seedTree()==null?baseline(row):store.after(store.apply(row.attemptId(),"SEED").orElseThrow(GitDirectoryTrees::invalid));
        requireExact(row,expected);mutations.requireNoTemporaryFiles(row.attemptId());
        trees.requireHead(Path.of(row.objectRepository()),row.branch(),row.sourceCommit());
    }
    public WorkflowWorkspaceCheckpoint capture(Workspace row) {
        identity(row.plan());var baseline=baseline(row);var seed=store.apply(row.attemptId(),"SEED").map(store::after).orElse(baseline);
        trees.requireHead(Path.of(row.objectRepository()),row.branch(),row.sourceCommit());
        if(!store.executionPlanned(row.attemptId()))mutations.requireCompatible(row.attemptId(),baseline,seed);
        var result=store.result(row.attemptId()).orElse(null);
        if(result==null) {
            var tracked=new ArrayList<>(baseline.files());tracked.addAll(seed.files());
            result=store.freezeResult(row,mutations.inspect(row.attemptId(),tracked));
        }
        storage.copy(row.attemptId(),result);requireExact(row,result);
        Path repository=Path.of(row.objectRepository());String owner=row.attemptId();
        String tree=trees.store(repository,result.files(),file->storage.read(owner,file));
        String commit=trees.commit(repository,tree,owner+"-result",row.createdAt());
        trees.reference(repository,row.checkpointRef(),commit);identity(row.plan());requireExact(row,result);
        return new WorkflowWorkspaceCheckpoint(row.branch(),row.sourceCommit(),row.checkpointRef(),commit,tree,null);
    }
    public void restore(Workspace row) {
        identity(row.plan());var baseline=baseline(row);
        var from=store.result(row.attemptId()).orElseGet(()->store.apply(row.attemptId(),"SEED").map(store::after).orElse(baseline));
        var intent=store.apply(row,"RESTORE",from,baseline);mutations.apply(intent);
        Path repository=Path.of(row.objectRepository());trees.reference(repository,"refs/heads/"+row.sourceBranch(),row.sourceCommit());trees.select(repository,row.sourceBranch());
        requireSource(row);
    }
    public boolean onSource(Workspace row) {
        identity(row.plan());
        if(!mutations.inspect(row.attemptId(),baseline(row).files()).equals(baseline(row)))return false;
        try{mutations.requireNoTemporaryFiles(row.attemptId());return true;}catch(io.opencode.loopper.service.ConflictException pending){return false;}
    }
    public void requireSource(Workspace row) {
        if(!onSource(row))throw GitDirectoryTrees.invalid();trees.requireHead(Path.of(row.objectRepository()),row.sourceBranch(),row.sourceCommit());
    }
    public DirectWorkspaceLeaseCoordinator.WorkspaceIdentity identity(WorkflowWorkspacePlan plan) {
        GitDirectoryTrees.outsideTransaction();files.requireIdentity(new WorkflowDirectorySnapshot(1,plan.canonicalRoot(),plan.rootFingerprint(),List.of()));
        var prepared=store.preparation(attempt(plan));
        if(!prepared.objectRepository().equals(plan.objectRepository())||!prepared.baseTree().equals(plan.baseTree())||!prepared.sourceCommit().equals(plan.sourceCommit())
                ||!prepared.canonicalRoot().equals(plan.canonicalRoot())||!prepared.rootFingerprint().equals(plan.rootFingerprint())
                ||!plan.projectDirectory().equals(plan.canonicalRoot()))throw GitDirectoryTrees.invalid();
        return new DirectWorkspaceLeaseCoordinator.WorkspaceIdentity(plan.canonicalRoot(),plan.rootFingerprint());
    }
    private WorkflowDirectorySnapshot baseline(Workspace row) { return store.snapshot(store.preparation(row.attemptId())); }
    private void requireExact(Workspace row,WorkflowDirectorySnapshot expected) {
        if(!mutations.inspect(row.attemptId(),expected.files()).equals(expected))throw GitDirectoryTrees.invalid();
    }
    private static String attempt(WorkflowWorkspacePlan plan) {
        String prefix="loopper/workflow-";if(!plan.branch().startsWith(prefix))throw GitDirectoryTrees.invalid();return plan.branch().substring(prefix.length());
    }
}
