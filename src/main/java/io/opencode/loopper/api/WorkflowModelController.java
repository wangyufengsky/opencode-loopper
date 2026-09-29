package io.opencode.loopper.api;

import io.opencode.loopper.service.workflow.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workflows/requirements/{id}/nodes/{key}")
public final class WorkflowModelController {
    private final WorkflowModelAdmission admission;
    private final WorkflowModelActions actions;
    public WorkflowModelController(WorkflowModelAdmission admission,WorkflowModelActions actions) { this.admission=admission; this.actions=actions; }
    @PostMapping("/model/start")
    public WorkflowNodeActions.Receipt start(@PathVariable String id,@PathVariable String key,
            @RequestHeader(value="X-Loopper-Local-UI",required=false) String authority,@RequestBody WorkflowModelAdmission.Start request) {
        WorkflowTemplateController.requireLocalUi(authority); return admission.start(id,key,request);
    }
    @GetMapping("/attempts/{attemptId}/model")
    public WorkflowModelActions.View get(@PathVariable String id,@PathVariable String key,@PathVariable String attemptId) {
        return actions.get(id,key,attemptId);
    }
    @PostMapping("/attempts/{attemptId}/model/stop")
    public WorkflowModelActions.View stop(@PathVariable String id,@PathVariable String key,@PathVariable String attemptId,
            @RequestHeader(value="X-Loopper-Local-UI",required=false) String authority,@RequestBody WorkflowModelActions.Command request) {
        WorkflowTemplateController.requireLocalUi(authority); return actions.stop(id,key,attemptId,request);
    }
    @PostMapping("/attempts/{attemptId}/model/resume")
    public WorkflowModelActions.View resume(@PathVariable String id,@PathVariable String key,@PathVariable String attemptId,
            @RequestHeader(value="X-Loopper-Local-UI",required=false) String authority,@RequestBody WorkflowModelActions.Command request) {
        WorkflowTemplateController.requireLocalUi(authority); return actions.resume(id,key,attemptId,request);
    }
}
