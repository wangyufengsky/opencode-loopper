package io.opencode.loopper.service.workflow;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.workflow.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** One consistent database snapshot of an eligible plain-directory result and its original baseline. */
@Service
@Transactional(readOnly=true)
public class WorkflowWritebackReads {
    private final WorkflowPublicationReads reads;
    private final WorkflowWorkspaceMapper workspaces;
    private final WorkflowFinishMapper finishes;
    private final WorkflowCodeStore codes;
    private final WorkflowDirectoryStore directories;
    public WorkflowWritebackReads(WorkflowPublicationReads reads,WorkflowWorkspaceMapper workspaces,WorkflowFinishMapper finishes,WorkflowCodeStore codes,WorkflowDirectoryStore directories) {
        this.reads=reads;this.workspaces=workspaces;this.finishes=finishes;this.codes=codes;this.directories=directories;
    }
    public record Source(WorkflowPublicationPreview preview,WorkflowWorkspaceMapper.Workspace workspace,
                         WorkflowDirectorySnapshot baseline,WorkflowDirectorySnapshot result) { }
    public Source get(String requirement,WorkflowWriteback.Selection request) {
        var preview=reads.preview(requirement,request.revision(),request.node(),request.attempt(),request.output());
        if(!preview.sha256().equals(request.sourceSha256())||!preview.workspaceKind().equals("DIRECT")||!preview.requirementState().equals("COMPLETED")||!finishes.remaining(requirement).empty())throw WorkflowPublications.changed();
        var workspace=workspaces.find(request.attempt()).orElseThrow(WorkflowPublications::changed);
        var prepared=directories.preparation(request.attempt());var snapshot=codes.snapshot(preview.reference().snapshotId());
        if(!workspace.state().equals("RELEASED")||workspace.objectRepository()==null||!workspace.objectRepository().equals(prepared.objectRepository())
                ||!workspace.objectRepository().equals(snapshot.objectRepository())||!prepared.baseTree().equals(snapshot.baseTree())
                ||!workspace.baseTree().equals(snapshot.baseTree())||!workspace.checkpointTree().equals(snapshot.resultTree())
                ||!workspace.canonicalRoot().equals(snapshot.repository())||!workspace.rootFingerprint().equals(snapshot.rootFingerprint()))throw WorkflowPublications.changed();
        var baseline=directories.snapshot(prepared);var result=directories.result(request.attempt()).orElseThrow(WorkflowPublications::changed);
        var manifest=codes.readOutput(workspace.projectId(),requirement,request.attempt(),preview.reference());
        if(!manifest.files().equals(result.files())||!manifest.projectPrefix().isEmpty())throw WorkflowPublications.changed();
        return new Source(preview,workspace,baseline,result);
    }
}
