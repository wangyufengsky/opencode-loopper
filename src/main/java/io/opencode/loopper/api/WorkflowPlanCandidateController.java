package io.opencode.loopper.api;
import io.opencode.loopper.persistence.WorkflowPlanCandidateMapper;
import io.opencode.loopper.service.workflow.*;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/workflows/requirements/{id}/candidates")
public final class WorkflowPlanCandidateController {
    private final WorkflowPlanCandidates candidates;
    public WorkflowPlanCandidateController(WorkflowPlanCandidates candidates){this.candidates=candidates;}
    @GetMapping public CursorPage<WorkflowPlanCandidateMapper.Summary> list(@PathVariable String id,@RequestParam(required=false) String state,
            @RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int limit){return candidates.list(id,state,cursor,limit);}
    @GetMapping("/{key}") public WorkflowPlanCandidates.Detail get(@PathVariable String id,@PathVariable String key){return candidates.get(id,key);}
    @PostMapping("/{key}/apply") public WorkflowCommands.Receipt apply(@PathVariable String id,@PathVariable String key,@RequestHeader(value="X-Loopper-Local-UI",required=false) String authority,@RequestBody WorkflowPlanCandidates.Apply request){WorkflowTemplateController.requireLocalUi(authority);return candidates.apply(id,key,request);}
    @PostMapping("/{key}/reject") public WorkflowCommands.Receipt reject(@PathVariable String id,@PathVariable String key,@RequestHeader(value="X-Loopper-Local-UI",required=false) String authority,@RequestBody WorkflowPlanCandidates.Reject request){WorkflowTemplateController.requireLocalUi(authority);return candidates.reject(id,key,request);}
}
