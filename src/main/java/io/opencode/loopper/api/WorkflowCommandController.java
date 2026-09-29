package io.opencode.loopper.api;
import io.opencode.loopper.service.workflow.WorkflowCommandActions;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/workflows/requirements/{id}/nodes/{key}/attempts/{attempt}/command")
public final class WorkflowCommandController {
    private final WorkflowCommandActions actions;
    public WorkflowCommandController(WorkflowCommandActions actions){this.actions=actions;}
    @GetMapping public WorkflowCommandActions.View get(@PathVariable String id,@PathVariable String key,@PathVariable String attempt){return actions.get(id,key,attempt);}
    @GetMapping("/evidence") public WorkflowCommandActions.Evidence evidence(@PathVariable String id,@PathVariable String key,@PathVariable String attempt){return actions.evidence(id,key,attempt);}
    @PostMapping("/stop") public WorkflowCommandActions.View stop(@PathVariable String id,@PathVariable String key,@PathVariable String attempt,
            @RequestHeader(value="X-Loopper-Local-UI",required=false) String authority,@RequestBody WorkflowCommandActions.Command request) {
        WorkflowTemplateController.requireLocalUi(authority);return actions.stop(id,key,attempt,request);
    }
    @PostMapping("/resume") public WorkflowCommandActions.View resume(@PathVariable String id,@PathVariable String key,@PathVariable String attempt,
            @RequestHeader(value="X-Loopper-Local-UI",required=false) String authority,@RequestBody WorkflowCommandActions.Command request) {
        WorkflowTemplateController.requireLocalUi(authority);return actions.resume(id,key,attempt,request);
    }
}
