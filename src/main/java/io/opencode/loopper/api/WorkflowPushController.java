package io.opencode.loopper.api;

import io.opencode.loopper.service.workflow.*;
import io.opencode.loopper.workflow.WorkflowPush;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workflows/requirements/{id}/publication/push")
public final class WorkflowPushController {
    private final WorkflowPushes store;
    private final WorkflowPushPreviews previews;
    private final WorkflowPushExecution execution;
    public WorkflowPushController(WorkflowPushes store,WorkflowPushPreviews previews,WorkflowPushExecution execution){this.store=store;this.previews=previews;this.execution=execution;}
    public record Selection(String remote) { }
    @GetMapping public WorkflowPush.View get(@PathVariable String id){return store.get(id);}
    @GetMapping("/remotes") public List<String> remotes(@PathVariable String id){return previews.remotes(id);}
    @PostMapping("/preview") public WorkflowPush.Preview preview(@PathVariable String id,@RequestHeader(value="X-Loopper-Local-UI",required=false) String authority,@RequestBody Selection request){WorkflowTemplateController.requireLocalUi(authority);return previews.inspect(id,request.remote()).preview();}
    @PostMapping public WorkflowPush.View confirm(@PathVariable String id,@RequestHeader(value="X-Loopper-Local-UI",required=false) String authority,@RequestBody WorkflowPush.Request request){WorkflowTemplateController.requireLocalUi(authority);return execution.confirm(id,request);}
    @PostMapping("/retry") public WorkflowPush.View retry(@PathVariable String id,@RequestHeader(value="X-Loopper-Local-UI",required=false) String authority,@RequestBody WorkflowRequests.VersionCommand request){WorkflowTemplateController.requireLocalUi(authority);return execution.retry(id,request.expectedVersion());}
}
