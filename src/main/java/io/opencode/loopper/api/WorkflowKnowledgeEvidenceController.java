package io.opencode.loopper.api;

import io.opencode.loopper.service.workflow.WorkflowNodeKnowledgeEvidence;
import io.opencode.loopper.workflow.WorkflowKnowledgeEvidence;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workflows/requirements/{id}/nodes/{key}/attempts/{attempt}/knowledge")
public final class WorkflowKnowledgeEvidenceController {
    private final WorkflowNodeKnowledgeEvidence evidence;
    public WorkflowKnowledgeEvidenceController(WorkflowNodeKnowledgeEvidence evidence){this.evidence=evidence;}
    @GetMapping public CursorPage<WorkflowKnowledgeEvidence.Entry> list(@PathVariable String id,@PathVariable String key,@PathVariable String attempt,
            @RequestParam(required=false) String cursor,@RequestParam(defaultValue="50") int limit) {
        return evidence.list(id,key,attempt,cursor,limit);
    }
    @GetMapping("/{entry}") public WorkflowKnowledgeEvidence.Body read(@PathVariable String id,@PathVariable String key,@PathVariable String attempt,@PathVariable String entry) {
        return evidence.read(id,key,attempt,entry);
    }
}
