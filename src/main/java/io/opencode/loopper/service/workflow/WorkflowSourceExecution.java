package io.opencode.loopper.service.workflow;

import io.opencode.loopper.runtime.ImmutableContentStore;
import io.opencode.loopper.service.*;
import io.opencode.loopper.template.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Recover the original manifest first; changed live files can never replace its bytes. */
@Service
@Transactional(propagation=Propagation.NOT_SUPPORTED)
public class WorkflowSourceExecution {
    private final WorkflowSourceStore store;
    private final SourceTreeCapture capture;
    private final WorkflowSourceContent content;
    private final SourceWorkspaceGuard workspace;
    public WorkflowSourceExecution(WorkflowSourceStore store,SourceTreeCapture capture,WorkflowSourceContent content,SourceWorkspaceGuard workspace) {
        this.store=store;this.capture=capture;this.content=content;this.workspace=workspace;
    }
    public void advance(String id) {
        var context=store.context(id);if(context==null)return;
        boolean success=false;String code=null;
        try {
            var row=context.snapshot();var manifest=store.manifest(row);
            if(manifest==null || !content.ready(row.nodeRunId(),manifest)) {
                var root=SourcePathPolicy.root(row.rootPath());workspace.requireNoWriter(root);
                var captured=capture.capture(new SourceTemplateParameters(root.toString(),row.sourcePath(),null,null,null),row.purpose().equals("UNIT_TEST"));
                workspace.requireNoWriter(root);
                row=store.plan(context,captured.manifest());
                content.write(row.nodeRunId(),captured.contents());manifest=store.manifest(row);
            }
            success=WorkflowSourceStore.complete(manifest);
            if(!success)code=manifest.files().stream().anyMatch(f->f.target()&&SourceTreeCapture.unresolved(f.exclusion()))?"WORKFLOW_SOURCE_INCOMPLETE":"SOURCE_NO_APPLICABLE_FILES";
        }catch(ImmutableContentStore.StorageFailure failure){code="WORKFLOW_SOURCE_STORAGE_INVALID";}
        catch(BadRequestException|ConflictException failure){code=WorkflowFailures.code(failure);}
        // Database/receipt failures remain retryable from the same durable attempt, never disguised as source failure.
        store.finish(context,success,code);
    }
}
