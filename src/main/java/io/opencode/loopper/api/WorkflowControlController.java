package io.opencode.loopper.api;
import io.opencode.loopper.service.workflow.WorkflowControls;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/workflows/requirements/{id}/control")
public final class WorkflowControlController {
    private final WorkflowControls controls;
    public WorkflowControlController(WorkflowControls controls){this.controls=controls;}
    @GetMapping public WorkflowControls.View get(@PathVariable String id){return controls.get(id);}
    @PostMapping("/start") public WorkflowControls.View start(@PathVariable String id,
            @RequestHeader(value="X-Loopper-Local-UI",required=false) String authority,@RequestBody WorkflowControls.Start request) {
        WorkflowTemplateController.requireLocalUi(authority);return controls.start(id,request);
    }
    @PostMapping("/pause") public WorkflowControls.View pause(@PathVariable String id,
            @RequestHeader(value="X-Loopper-Local-UI",required=false) String authority,@RequestBody WorkflowControls.Pause request) {
        WorkflowTemplateController.requireLocalUi(authority);return controls.pause(id,request);
    }
}
