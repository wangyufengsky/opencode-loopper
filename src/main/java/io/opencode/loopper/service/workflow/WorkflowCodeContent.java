package io.opencode.loopper.service.workflow;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.runtime.ImmutableContentStore;
import io.opencode.loopper.workflow.WorkflowCodeSnapshot;
import org.springframework.stereotype.Component;

/** Immutable managed CODE bytes, independent of writer admission and node lifecycle. */
@Component
public final class WorkflowCodeContent {
    private final ImmutableContentStore content;
    public WorkflowCodeContent(LoopperProperties properties){content=new ImmutableContentStore(properties.getDataDir().resolve("workflow-code"));}
    public void write(String snapshot,String hash,byte[] bytes){content.write(snapshot,hash,bytes,GitCodeSnapshots.MAX_FILE_BYTES);}
    public byte[] read(WorkflowCodeSnapshot manifest,WorkflowCodeSnapshot.File file) {
        if(!manifest.files().contains(file))throw WorkflowCodeStore.invalid();
        return content.read(manifest.snapshotId(),file.sha256(),file.sizeBytes(),GitCodeSnapshots.MAX_FILE_BYTES);
    }
}
