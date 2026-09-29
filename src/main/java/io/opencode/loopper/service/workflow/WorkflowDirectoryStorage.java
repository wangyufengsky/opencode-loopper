package io.opencode.loopper.service.workflow;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.runtime.ImmutableContentStore;
import io.opencode.loopper.workflow.*;
import java.nio.file.Path;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/** Private immutable bytes for one directory attempt. Callers persist inventory/authorization before copying. */
@Component
public final class WorkflowDirectoryStorage {
    private final ImmutableContentStore content;
    private final WorkflowDirectoryFiles files;
    private final Path dataDirectory;
    public WorkflowDirectoryStorage(LoopperProperties properties,WorkflowDirectoryFiles files) {
        this.files=files;content=new ImmutableContentStore(properties.getDataDir().resolve("workflow-directory"));
        dataDirectory=new ImmutableContentStore(properties.getDataDir()).location("workflow-directory").getParent();
    }
    public Path repository(String owner) { return content.location(owner).resolve("repository"); }
    public Path dataDirectory() { return dataDirectory; }
    public void copy(String owner,WorkflowDirectorySnapshot snapshot) {
        files.requireIdentity(snapshot);importFiles(owner,snapshot,file->files.read(snapshot,file));files.requireIdentity(snapshot);
    }
    public void importFiles(String owner,WorkflowDirectorySnapshot snapshot,Function<WorkflowCodeSnapshot.File,byte[]> read) {
        GitDirectoryTrees.outsideTransaction();GitDirectoryTrees.validate(snapshot.files());
        for(var file:snapshot.files()) if(content.find(owner,file.sha256(),file.sizeBytes(),GitCodeSnapshots.MAX_FILE_BYTES).isEmpty()) {
            byte[] bytes=read.apply(file);GitDirectoryTrees.requireBytes(file,bytes);
            content.write(owner,file.sha256(),bytes,GitCodeSnapshots.MAX_FILE_BYTES);
        }
    }
    public byte[] read(String owner,WorkflowCodeSnapshot.File file) {
        return content.read(owner,file.sha256(),file.sizeBytes(),GitCodeSnapshots.MAX_FILE_BYTES);
    }
}
