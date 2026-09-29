package io.opencode.loopper.api;

import io.opencode.loopper.persistence.WorkflowRows;
import io.opencode.loopper.service.workflow.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workflows/requirements")
public final class WorkflowRequirementController {
    private final WorkflowPlans plans;
    public WorkflowRequirementController(WorkflowPlans plans) { this.plans = plans; }
    @GetMapping public CursorPage<WorkflowRows.RequirementSummary> list(@RequestParam(required=false) String projectId,
            @RequestParam(required=false) String cursor, @RequestParam(defaultValue="50") int limit) {
        return plans.list(projectId, cursor, limit);
    }
    @GetMapping("/{id}") public WorkflowPlans.Detail get(@PathVariable String id, @RequestParam(required=false) Integer revision) {
        return plans.get(id, revision);
    }
    @PostMapping public WorkflowCommands.Receipt create(@RequestHeader(value="X-Loopper-Local-UI",required=false) String authority,
            @RequestBody WorkflowRequests.CreateRequirement request) {
        WorkflowTemplateController.requireLocalUi(authority); return plans.create(request);
    }
    @PutMapping("/{id}/plan") public WorkflowCommands.Receipt revise(@PathVariable String id,
            @RequestHeader(value="X-Loopper-Local-UI",required=false) String authority, @RequestBody WorkflowRequests.RevisePlan request) {
        WorkflowTemplateController.requireLocalUi(authority); return plans.revise(id, request);
    }
    @PostMapping("/{id}/confirm") public WorkflowCommands.Receipt confirm(@PathVariable String id,
            @RequestHeader(value="X-Loopper-Local-UI",required=false) String authority, @RequestBody WorkflowRequests.VersionCommand request) {
        WorkflowTemplateController.requireLocalUi(authority); return plans.confirm(id, request);
    }
    @PostMapping("/{id}/cancel") public WorkflowCommands.Receipt cancel(@PathVariable String id,
            @RequestHeader(value="X-Loopper-Local-UI",required=false) String authority, @RequestBody WorkflowRequests.VersionCommand request) {
        WorkflowTemplateController.requireLocalUi(authority); return plans.cancelPlanning(id, request);
    }
    @PutMapping("/{id}/layout") public WorkflowCommands.Receipt layout(@PathVariable String id,
            @RequestHeader(value="X-Loopper-Local-UI",required=false) String authority, @RequestBody WorkflowRequests.Layout request) {
        WorkflowTemplateController.requireLocalUi(authority); return plans.layout(id, request);
    }
}
