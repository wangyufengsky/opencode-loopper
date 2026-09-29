package io.opencode.loopper.service.workflow;

import io.opencode.loopper.service.*;
import io.opencode.loopper.workflow.WorkflowHistorySnapshot;
import java.io.IOException;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Converts a verified, stopped helper result into immutable readable documents outside the acceptance transaction. */
@Service
@Transactional(propagation=Propagation.NOT_SUPPORTED)
public class WorkflowHistoryCapture {
    private final GitHistoryJobs jobs;
    private final WorkflowHistoryContent content;
    public WorkflowHistoryCapture(GitHistoryJobs jobs,WorkflowHistoryContent content){this.jobs=jobs;this.content=content;}
    public GitHistoryJobs.Prepared prepare(String attempt,WorkflowHistoryContract.Context context) {
        var prepared=jobs.prepare(attempt,context.input(),context.timeoutSeconds());
        if(!prepared.inputSha256().equals(context.row().inputSha256()))throw WorkflowCommands.conflict();return prepared;
    }
    public Map<String,String> environment(WorkflowHistoryContract.Context context){return jobs.environment(context.input());}
    public WorkflowHistorySnapshot.Manifest read(WorkflowHistoryContract.Context context) {
        try {
            String sha=GitHistoryEvidenceCodec.hash(jobs.evidence(context.input().nodeId()));
            var frozen=jobs.read(context.input(),context.row().inputSha256());
            if(!frozen.binding().project().equals(context.input().source().projectPath()))throw WorkflowCommands.conflict();
            var manifest=content.capture(context.input(),frozen,sha);
            if(!sha.equals(GitHistoryEvidenceCodec.hash(jobs.evidence(context.input().nodeId()))))throw WorkflowCommands.conflict();
            return manifest;
        }catch(IOException invalid){throw new ConflictException("WORKFLOW_HISTORY_EVIDENCE_INVALID","原历史证据无法核验，请保留现场后恢复采集节点。");}
    }
}
