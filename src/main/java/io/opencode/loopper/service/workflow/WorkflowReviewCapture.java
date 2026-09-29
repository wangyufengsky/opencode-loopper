package io.opencode.loopper.service.workflow;

import io.opencode.loopper.service.*;
import io.opencode.loopper.workflow.WorkflowReviewSource;
import java.io.IOException;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Converts a verified, stopped helper result into immutable readable documents outside the acceptance transaction. */
@Service
@Transactional(propagation=Propagation.NOT_SUPPORTED)
public class WorkflowReviewCapture {
    private final GitReviewJobs jobs;
    private final WorkflowReviewContent content;
    public WorkflowReviewCapture(GitReviewJobs jobs,WorkflowReviewContent content){this.jobs=jobs;this.content=content;}
    public GitReviewJobs.Prepared prepare(String attempt,WorkflowReviewSourceContract.Context context) {
        var prepared=jobs.prepare(attempt,context.input(),context.timeoutSeconds());
        if(!prepared.inputSha256().equals(context.row().inputSha256()))throw WorkflowCommands.conflict();return prepared;
    }
    public Map<String,String> environment(WorkflowReviewSourceContract.Context context){return jobs.environment(context.input());}
    public WorkflowReviewSource.Manifest read(WorkflowReviewSourceContract.Context context) {
        try {
            String sha=GitReviewEvidenceCodec.hash(jobs.evidence(context.input().nodeId()));
            var frozen=jobs.read(context.input(),context.row().inputSha256());
            if(!frozen.selection().binding().project().equals(context.input().source().projectPath()))throw WorkflowCommands.conflict();
            var manifest=content.capture(context.input(),frozen,sha);
            if(!sha.equals(GitReviewEvidenceCodec.hash(jobs.evidence(context.input().nodeId()))))throw WorkflowCommands.conflict();
            return manifest;
        }catch(IOException invalid){throw new ConflictException("WORKFLOW_REVIEW_SOURCE_INVALID","原版本审查证据无法核验，请保留现场后恢复采集节点。");}
    }
}
