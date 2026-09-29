package io.opencode.loopper.service.workflow;

import io.opencode.loopper.persistence.WorkflowDirectoryMapper.Apply;
import io.opencode.loopper.workflow.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** Resumes one frozen before/after transformation. Unknown files/bytes never authorize an overwrite. */
@Component
public final class WorkflowDirectoryMutations {
    private final WorkflowDirectoryStore store;
    private final WorkflowDirectoryFiles files;
    private final WorkflowDirectoryStorage storage;
    private final WorkflowDirectoryTransform transform;
    public WorkflowDirectoryMutations(WorkflowDirectoryStore store,WorkflowDirectoryFiles files,WorkflowDirectoryStorage storage,WorkflowDirectoryTransform transform) {
        this.store=store;this.files=files;this.storage=storage;this.transform=transform;
    }
    public void apply(Apply intent) {
        GitDirectoryTrees.outsideTransaction();var before=store.before(intent);var after=store.after(intent);
        requireCompatible(intent.attemptId(),before,after);cleanup(intent.attemptId(),intent.id());
        var tracked=new TreeMap<String,WorkflowCodeSnapshot.File>();before.files().forEach(file->tracked.put(file.path(),file));after.files().forEach(file->tracked.put(file.path(),file));
        transform.apply(intent.id(),before,after,file->storage.read(intent.attemptId(),file),()->inspect(intent.attemptId(),new ArrayList<>(tracked.values())));
        cleanup(intent.attemptId(),null);files.requireIdentity(after);
    }
    public void requireCompatible(String attempt,WorkflowDirectorySnapshot before,WorkflowDirectorySnapshot after) {
        GitDirectoryTrees.outsideTransaction();var union=index(before.files());union.putAll(index(after.files()));
        transform.requireCompatible(before,after,inspect(attempt,new ArrayList<>(union.values())));
        for(var pending:pending(attempt).entrySet()) {
            Path path=path(before,pending.getKey());if(Files.exists(path,LinkOption.NOFOLLOW_LINKS))WorkflowDirectoryTransform.verifyTemporary(path,storage.read(attempt,pending.getValue()));
        }
    }
    public WorkflowDirectorySnapshot inspect(String attempt,List<WorkflowCodeSnapshot.File> tracked) {
        var prepared=store.preparation(attempt);var root=Path.of(prepared.canonicalRoot());
        var present=new TreeMap<String,WorkflowCodeSnapshot.File>();
        for(var file:tracked) {
            Path path=root.resolve(file.path());
            if(Files.exists(path,LinkOption.NOFOLLOW_LINKS)&&!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))present.put(file.path(),file);
        }
        return files.discover(root,Path.of(prepared.objectRepository()),storage.dataDirectory(),new ArrayList<>(present.values()),pending(attempt).keySet());
    }
    public void requireNoTemporaryFiles(String attempt) {
        var prepared=store.preparation(attempt);Path root=Path.of(prepared.canonicalRoot());
        for(String name:pending(attempt).keySet()) if(Files.exists(root.resolve(name),LinkOption.NOFOLLOW_LINKS))throw GitDirectoryTrees.invalid();
    }
    private Map<String,WorkflowCodeSnapshot.File> pending(String attempt) {
        var result=new TreeMap<String,WorkflowCodeSnapshot.File>();
        for(var intent:store.applies(attempt)) {
            var after=store.after(intent);var occupied=index(store.before(intent).files());occupied.putAll(index(after.files()));
            for(var file:after.files()) {
                String temporary=temporary(intent,file);
                if(occupied.containsKey(temporary)||result.put(temporary,file)!=null)throw GitDirectoryTrees.invalid();
            }
        }
        return result;
    }
    private void cleanup(String attempt,String retainIntent) {
        var prepared=store.preparation(attempt);Path root=Path.of(prepared.canonicalRoot());
        for(var intent:store.applies(attempt)) if(!intent.id().equals(retainIntent)) {
            for(var file:store.after(intent).files()) {
                Path temporary=root.resolve(temporary(intent,file));if(!Files.exists(temporary,LinkOption.NOFOLLOW_LINKS))continue;
                WorkflowDirectoryTransform.verifyTemporary(temporary,storage.read(attempt,file));
                try{Files.deleteIfExists(temporary);}catch(IOException failure){throw GitDirectoryTrees.invalid();}
            }
        }
    }
    private static Path path(WorkflowDirectorySnapshot scope,String relative) {
        Path root=Path.of(scope.canonicalRoot()),path=root.resolve(relative).normalize();
        if(!path.startsWith(root)||path.equals(root))throw GitDirectoryTrees.invalid();GitDirectoryTrees.safe(path);return path;
    }
    static String temporary(Apply intent,WorkflowCodeSnapshot.File file) { return WorkflowDirectoryTransform.temporary(intent.id(),file); }
    private static Map<String,WorkflowCodeSnapshot.File> index(List<WorkflowCodeSnapshot.File> values) {
        var result=new TreeMap<String,WorkflowCodeSnapshot.File>();values.forEach(file->result.put(file.path(),file));return result;
    }
}
