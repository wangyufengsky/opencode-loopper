package io.opencode.loopper.api;
import io.opencode.loopper.service.workflow.*;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/workflows/requirements/{id}/plan")
public final class WorkflowPlanRevisionController {
    private final WorkflowPlanRevisions revisions;
    public WorkflowPlanRevisionController(WorkflowPlanRevisions revisions){this.revisions=revisions;}
    @PostMapping("/apply") public WorkflowCommands.Receipt apply(@PathVariable String id,@RequestHeader(value="X-Loopper-Local-UI",required=false) String authority,
            @RequestBody WorkflowRequests.RevisePlan request){WorkflowTemplateController.requireLocalUi(authority);return revisions.revise(id,request);}
}
