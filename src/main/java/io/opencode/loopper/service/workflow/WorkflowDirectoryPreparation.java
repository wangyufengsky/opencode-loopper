package io.opencode.loopper.service.workflow;

import io.opencode.loopper.persistence.WorkflowDirectoryMapper.Preparation;
import io.opencode.loopper.runtime.*;
import io.opencode.loopper.workflow.WorkflowDirectorySnapshot;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Reserve identity, freeze inventory, copy immutable bodies, then record the exact private baseline. */
@Service
@Transactional(propagation=Propagation.NOT_SUPPORTED)
public class WorkflowDirectoryPreparation {
    private final WorkflowDirectoryStore store;
    private final WorkflowDirectoryFiles files;
    private final GitDirectoryTrees trees;
    private final WorkflowDirectoryStorage storage;
    public WorkflowDirectoryPreparation(WorkflowDirectoryStore store,WorkflowDirectoryFiles files,GitDirectoryTrees trees,WorkflowDirectoryStorage storage) {
        this.store=store;this.files=files;this.trees=trees;this.storage=storage;
    }
    public Preparation prepare(WorkflowWorkspaceStore.Context context) {
        Path project;
        try { project=Path.of(context.project().rootPath()).toRealPath();
            if (!GitProjectScope.checkoutRoot(project).equals(project) || Files.exists(project.resolve(".git"),LinkOption.NOFOLLOW_LINKS)) throw GitDirectoryTrees.invalid();
        } catch(IOException failure) { throw GitDirectoryTrees.invalid(); }
        var identity=DirectWorkspaceLeaseCoordinator.identifyDirectory(project);
        Path repository=storage.repository(context.attempt().id());
        var row=store.reserve(context,identity,repository.toString());
        trees.initialize(repository);
        if (row.manifestJson()==null) row=store.manifest(context,row,files.discover(project,repository,storage.dataDirectory(),List.of()));
        var inventory=store.snapshot(row);files.requireIdentity(inventory);
        storage.copy(row.attemptId(),inventory);
        String owner=row.attemptId();
        String tree=trees.store(repository,inventory.files(),file->storage.read(owner,file));
        String commit=trees.commit(repository,tree,owner,row.createdAt());
        files.requireIdentity(inventory);
        return store.ready(context,row,tree,commit);
    }
    public byte[] read(Preparation row,io.opencode.loopper.workflow.WorkflowCodeSnapshot.File file) {
        WorkflowDirectorySnapshot inventory=store.snapshot(row);
        if (!inventory.files().contains(file)) throw GitDirectoryTrees.invalid();
        return storage.read(row.attemptId(),file);
    }
}
