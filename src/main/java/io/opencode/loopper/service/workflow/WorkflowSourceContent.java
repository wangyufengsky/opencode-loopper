package io.opencode.loopper.service.workflow;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.runtime.ImmutableContentStore;
import io.opencode.loopper.service.*;
import io.opencode.loopper.template.SourceManifest;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Private immutable bytes; no user checkout is created or changed. */
@Component
public final class WorkflowSourceContent {
    private final ImmutableContentStore content;
    public WorkflowSourceContent(LoopperProperties properties){content=new ImmutableContentStore(properties.getDataDir().resolve("workflow-source"));}
    public void write(String id,Map<String,byte[]> contents){contents.forEach((sha,bytes)->content.write(id,sha,bytes,SourceTreeCapture.MAX_FILE_BYTES));}
    public byte[] read(String id,SourceManifest.File file) {
        if(file.sha256()==null)throw new BadRequestException("WORKFLOW_SOURCE_FILE_EXCLUDED","该文件未采集正文，请查看排除原因。");
        return content.read(id,file.sha256(),file.sizeBytes(),SourceTreeCapture.MAX_FILE_BYTES);
    }
    public boolean ready(String id,SourceManifest manifest) {
        try {for(var file:manifest.files())if(file.sha256()!=null)read(id,file);return true;}
        catch(ImmutableContentStore.StorageFailure missing){return false;}
    }
}
