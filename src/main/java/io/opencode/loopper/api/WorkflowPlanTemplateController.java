package io.opencode.loopper.api;

import io.opencode.loopper.service.workflow.*;
import io.opencode.loopper.workflow.WorkflowTemplateExport;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workflows/requirements/{id}/templates")
public final class WorkflowPlanTemplateController {
    private final WorkflowPlanTemplates templates;
    public WorkflowPlanTemplateController(WorkflowPlanTemplates templates) { this.templates=templates; }
    @PostMapping("/preview") public WorkflowTemplateExport.Preview preview(@PathVariable String id,
            @RequestHeader(value="X-Loopper-Local-UI",required=false) String authority,
            @RequestBody WorkflowTemplateExport.PreviewRequest request) {
        WorkflowTemplateController.requireLocalUi(authority);return templates.preview(id,request);
    }
    @PostMapping public WorkflowCommands.Receipt save(@PathVariable String id,
            @RequestHeader(value="X-Loopper-Local-UI",required=false) String authority,
            @RequestBody WorkflowTemplateExport.Save request) {
        WorkflowTemplateController.requireLocalUi(authority);return templates.save(id,request);
    }
}
