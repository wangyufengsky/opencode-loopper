package io.opencode.loopper.api;

import io.opencode.loopper.service.workflow.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workflows/requirements/{id}/finish")
public final class WorkflowFinishController {
    private final WorkflowFinishes finishes;
    public WorkflowFinishController(WorkflowFinishes finishes){this.finishes=finishes;}
    @GetMapping public WorkflowFinishes.View get(@PathVariable String id){return finishes.get(id);}
    @PostMapping public WorkflowCommands.Receipt finish(@PathVariable String id,
            @RequestHeader(value="X-Loopper-Local-UI",required=false) String authority,@RequestBody WorkflowFinishes.Request request) {
        WorkflowTemplateController.requireLocalUi(authority);return finishes.request(id,request);
    }
}
