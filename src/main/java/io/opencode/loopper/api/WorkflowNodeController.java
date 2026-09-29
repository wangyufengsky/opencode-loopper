package io.opencode.loopper.api;

import io.opencode.loopper.service.workflow.WorkflowNodeActions;
import io.opencode.loopper.service.workflow.WorkflowInputPages;
import io.opencode.loopper.workflow.WorkflowDelivery;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workflows/requirements/{id}/nodes")
public final class WorkflowNodeController {
    private final WorkflowNodeActions actions;
    public WorkflowNodeController(WorkflowNodeActions actions) { this.actions = actions; }
    @GetMapping public WorkflowNodeActions.Overview overview(@PathVariable String id) { return actions.overview(id); }
    @PostMapping("/{key}/human/start") public WorkflowNodeActions.Receipt start(@PathVariable String id, @PathVariable String key,
            @RequestHeader(value="X-Loopper-Local-UI",required=false) String authority, @RequestBody WorkflowNodeActions.Start request) {
        WorkflowTemplateController.requireLocalUi(authority); return actions.startHuman(id, key, request);
    }
    @PostMapping("/{key}/human/complete") public WorkflowNodeActions.Receipt complete(@PathVariable String id, @PathVariable String key,
            @RequestHeader(value="X-Loopper-Local-UI",required=false) String authority, @RequestBody WorkflowNodeActions.Complete request) {
        WorkflowTemplateController.requireLocalUi(authority); return actions.completeHuman(id, key, request);
    }
    @GetMapping("/{key}/attempts/{attemptId}/inputs") public WorkflowDelivery.Inputs inputs(
            @PathVariable String id, @PathVariable String key, @PathVariable String attemptId) { return actions.inputs(id, key, attemptId); }
    @GetMapping("/{key}/attempts/{attemptId}/result") public WorkflowNodeActions.Result result(
            @PathVariable String id, @PathVariable String key, @PathVariable String attemptId) { return actions.result(id, key, attemptId); }
    @GetMapping("/{key}/attempts/{attemptId}/inputs/{name}/content") public WorkflowInputPages.Page inputContent(
            @PathVariable String id, @PathVariable String key, @PathVariable String attemptId, @PathVariable String name,
            @RequestParam(defaultValue="0") int offset, @RequestParam(defaultValue="12000") int limit) {
        return actions.inputContent(id, key, attemptId, name, offset, limit);
    }
}
