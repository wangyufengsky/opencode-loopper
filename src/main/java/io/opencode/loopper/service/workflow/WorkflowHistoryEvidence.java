package io.opencode.loopper.service.workflow;

import io.opencode.loopper.template.TemplateGitEvidence;
import io.opencode.loopper.workflow.WorkflowHistorySnapshot;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.JsonNode;

/** Reconstructs accepted evidence from immutable delivery files, without the original checkout or bare repository. */
@Service
@Transactional(propagation=Propagation.NOT_SUPPORTED)
public class WorkflowHistoryEvidence {
    private final WorkflowHistoryContent content;
    private final WorkflowEncoding encoding;
    public WorkflowHistoryEvidence(WorkflowHistoryContent content,WorkflowEncoding encoding){this.content=content;this.encoding=encoding;}
    public TemplateGitEvidence read(WorkflowHistorySnapshot.Manifest manifest) {
        var commits=new ArrayList<TemplateGitEvidence.Commit>();JsonNode overview=null;long bytes=0;
        for(var file:manifest.files()) {
            bytes+=file.sizeBytes();if(bytes>WorkflowHistorySnapshot.MAX_FILE_BYTES+65536)throw WorkflowCommands.conflict();
            String text=new String(content.read(manifest.nodeRunId(),file),StandardCharsets.UTF_8);
            if(file.path().equals("overview.json")) {if(overview!=null)throw WorkflowCommands.conflict();overview=encoding.decode(text,JsonNode.class);}
            else {
                var commit=encoding.decode(text,TemplateGitEvidence.Commit.class);
                if(!file.path().equals(String.format(Locale.ROOT,"commits/%06d-%s.json",commits.size()+1,commit.sha())))throw WorkflowCommands.conflict();
                commits.add(commit);
            }
        }
        if(overview==null||!TemplateGitEvidence.VERSION.equals(overview.path("version").asString())||commits.size()!=manifest.commitCount()
                ||overview.path("commitCount").asLong()!=commits.size()||!manifest.branchId().equals(overview.path("branchId").asString())
                ||!manifest.commitSha().equals(overview.path("head").asString())||!manifest.startDate().equals(overview.path("startDate").asString())
                ||!manifest.endDate().equals(overview.path("endDate").asString())||!manifest.timezone().equals(overview.path("timezone").asString()))throw WorkflowCommands.conflict();
        return new TemplateGitEvidence(TemplateGitEvidence.VERSION,manifest.branchId(),manifest.commitSha(),manifest.startDate(),manifest.endDate(),
                manifest.timezone(),overview.path("mailmapHash").isNull()?null:overview.path("mailmapHash").asString(),commits);
    }
}
