package io.opencode.loopper.service.workflow;

import io.opencode.loopper.workflow.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Explicit local inspection; no lease, directory mutation or publication consent is created. */
@Service
@Transactional(propagation=Propagation.NOT_SUPPORTED)
public class WorkflowWritebackPreviews {
    private final WorkflowWritebackReads reads;
    private final WorkflowDirectoryFiles files;
    private final WorkflowDirectoryStorage storage;
    private final WorkflowEncoding encoding;
    private final WorkflowDirectoryTransform transform;
    public WorkflowWritebackPreviews(WorkflowWritebackReads reads,WorkflowDirectoryFiles files,WorkflowDirectoryStorage storage,WorkflowEncoding encoding,WorkflowDirectoryTransform transform) {
        this.reads=reads;this.files=files;this.storage=storage;this.encoding=encoding;this.transform=transform;
    }
    public record Context(WorkflowWritebackReads.Source source,WorkflowDirectoryChanges.Plan plan,WorkflowWriteback.Preview preview) { }
    public Context inspect(String requirement,WorkflowWriteback.Selection request) {
        var source=reads.get(requirement,request);var workspace=source.workspace();Path root=Path.of(workspace.canonicalRoot());
        var tracked=new TreeMap<String,WorkflowCodeSnapshot.File>();source.baseline().files().forEach(file->tracked.put(file.path(),file));source.result().files().forEach(file->tracked.put(file.path(),file));
        tracked.values().removeIf(file->!Files.exists(root.resolve(file.path()),LinkOption.NOFOLLOW_LINKS)||Files.isDirectory(root.resolve(file.path()),LinkOption.NOFOLLOW_LINKS));
        var current=files.discover(root,Path.of(workspace.objectRepository()),storage.dataDirectory(),new ArrayList<>(tracked.values()));
        files.requireIdentity(source.baseline());var plan=WorkflowDirectoryChanges.plan(source.baseline(),source.result(),current);
        if(plan.after()!=null) {
            var obstructed=transform.obstructions(plan.before(),plan.after());
            if(!obstructed.isEmpty())plan=new WorkflowDirectoryChanges.Plan(current,null,obstructed,0,0,0,plan.preservedChanges());
        }
        if(!source.equals(reads.get(requirement,request)))throw WorkflowPublications.changed();
        String before=WorkflowEncoding.hash(encoding.encode(current)),after=plan.after()==null?null:WorkflowEncoding.hash(encoding.encode(plan.after()));
        String hash=WorkflowEncoding.hash(encoding.encode(Arrays.asList(requirement,request,before,after,plan.conflicts())));
        var preview=new WorkflowWriteback.Preview(requirement,source.preview().requirementVersion(),request.revision(),source.preview().sha256(),root.toString(),before,after,hash,
                plan.added(),plan.modified(),plan.deleted(),plan.preservedChanges(),plan.conflicts().size(),plan.conflicts().stream().limit(100).toList());
        return new Context(source,plan,preview);
    }
}
