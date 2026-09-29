package io.opencode.loopper.service.workflow;

import io.opencode.loopper.persistence.WorkflowPublicationMapper;
import io.opencode.loopper.runtime.*;
import io.opencode.loopper.workflow.WorkflowPublication.Intent;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Replays the same commit and create-only branch after any acknowledgement loss. Never touches a checkout. */
@Service
@Transactional(propagation=Propagation.NOT_SUPPORTED)
public class WorkflowPublicationExecution {
    private final WorkflowPublicationMapper mapper;
    private final WorkflowPublications store;
    private final GitEvidenceProcess git;
    private final GitFixedCommits commits;
    private final GitCodeSnapshots snapshots;
    private final WorkflowCodeContent content;
    public WorkflowPublicationExecution(WorkflowPublicationMapper mapper,WorkflowPublications store,GitEvidenceProcess git,
            GitFixedCommits commits,GitCodeSnapshots snapshots,WorkflowCodeContent content) {
        this.mapper=mapper;this.store=store;this.git=git;this.commits=commits;this.snapshots=snapshots;this.content=content;
    }
    public void advance(String requirement) {
        var row=mapper.find(requirement).orElseThrow(WorkflowPublications::changed);
        if(!row.state().equals("CONFIRMED"))return;
        String reason="WORKFLOW_PUBLICATION_SOURCE_UNAVAILABLE";
        try {
            var work=store.work(requirement);if(!work.row().equals(row))return;
            reason="WORKFLOW_PUBLICATION_PROJECT_UNAVAILABLE";
            var intent=work.intent();var repository=identity(intent);
            reason="WORKFLOW_PUBLICATION_SOURCE_UNAVAILABLE";
            var args=List.of("rev-parse",intent.commit().parent()+"^{tree}");
            var parent=git.run(repository,Duration.ofSeconds(30),args);parent.requireSuccess(args);
            if(!parent.output().strip().equals(work.manifest().baseTree()))throw WorkflowPublications.changed();
            var captured=snapshots.capture(repository,work.manifest().baseTree(),work.manifest().resultTree(),intent.projectPrefix(),(hash,bytes)->{});
            if(!captured.files().equals(work.manifest().files())||!captured.changes().equals(work.manifest().changes()))throw WorkflowPublications.changed();
            for(var file:work.manifest().files())content.read(work.manifest(),file);
            reason="WORKFLOW_PUBLICATION_PROJECT_UNAVAILABLE";
            identity(intent);
            reason="WORKFLOW_PUBLICATION_COMMIT_UNCONFIRMED";
            String commit=commits.create(repository,intent.commit());
            reason="WORKFLOW_PUBLICATION_PROJECT_UNAVAILABLE";
            identity(intent);
            reason="WORKFLOW_PUBLICATION_BRANCH_UNCONFIRMED";
            commits.reference(repository,"refs/heads/"+intent.branch(),commit);
            reason="WORKFLOW_PUBLICATION_RECEIPT_UNCONFIRMED";
            store.committed(row,commit);
        } catch(RuntimeException failure) {
            // The ref may already exist. Keep the same frozen identity; retry verifies it before acknowledging success.
            store.blocked(row,reason);
        }
    }
    private Path identity(Intent intent) {
        var project=Path.of(intent.projectDirectory());var identity=DirectWorkspaceLeaseCoordinator.identify(project);
        var scope=GitProjectScope.require(git,project);
        if(!identity.canonicalRoot().equals(intent.repository())||!identity.rootFingerprint().equals(intent.rootFingerprint())
                ||!scope.repository().toString().equals(intent.repository())||!scope.prefix().equals(intent.projectPrefix()))throw WorkflowPublications.changed();
        return scope.repository();
    }
}
