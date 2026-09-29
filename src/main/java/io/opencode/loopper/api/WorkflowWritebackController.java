package io.opencode.loopper.api;

import io.opencode.loopper.service.workflow.WorkflowWritebackPreviews;
import io.opencode.loopper.service.workflow.WorkflowWritebacks;
import io.opencode.loopper.service.workflow.WorkflowWritebackExecution;
import io.opencode.loopper.workflow.WorkflowWriteback;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workflows/requirements/{id}/publication/writeback")
public final class WorkflowWritebackController {
    private final WorkflowWritebackPreviews previews;
    private final WorkflowWritebacks records;
    private final WorkflowWritebackExecution execution;
    public WorkflowWritebackController(WorkflowWritebackPreviews previews,WorkflowWritebacks records,WorkflowWritebackExecution execution){this.previews=previews;this.records=records;this.execution=execution;}
    @PostMapping("/preview") public WorkflowWriteback.Preview preview(@PathVariable String id,
            @RequestHeader(value="X-Loopper-Local-UI",required=false) String authority,@RequestBody WorkflowWriteback.Selection request) {
        WorkflowTemplateController.requireLocalUi(authority);return previews.inspect(id,request).preview();
    }
    @GetMapping public WorkflowWriteback.View get(@PathVariable String id){return records.get(id);}
    @PostMapping("/confirm") public WorkflowWriteback.View confirm(@PathVariable String id,
            @RequestHeader(value="X-Loopper-Local-UI",required=false) String authority,@RequestBody WorkflowWriteback.Request request) {
        WorkflowTemplateController.requireLocalUi(authority);return execution.confirm(id,request);
    }
    public record Retry(long expectedVersion) { }
    @PostMapping("/retry") public WorkflowWriteback.View retry(@PathVariable String id,
            @RequestHeader(value="X-Loopper-Local-UI",required=false) String authority,@RequestBody Retry request) {
        WorkflowTemplateController.requireLocalUi(authority);return records.retry(id,request.expectedVersion());
    }
}
