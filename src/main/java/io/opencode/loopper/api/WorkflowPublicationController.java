package io.opencode.loopper.api;

import io.opencode.loopper.service.workflow.*;
import io.opencode.loopper.workflow.WorkflowPublication;
import io.opencode.loopper.workflow.WorkflowPublicationPreview;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workflows/requirements/{id}/publication")
public final class WorkflowPublicationController {
    private final WorkflowPublicationReads reads;
    private final WorkflowPublications publications;
    public WorkflowPublicationController(WorkflowPublicationReads reads,WorkflowPublications publications){this.reads=reads;this.publications=publications;}
    @GetMapping public WorkflowPublication.View get(@PathVariable String id){return publications.get(id);}
    @PostMapping public WorkflowPublication.View confirm(@PathVariable String id,
            @RequestHeader(value="X-Loopper-Local-UI",required=false) String authority,@RequestBody WorkflowPublication.Request request){
        WorkflowTemplateController.requireLocalUi(authority);return publications.confirm(id,request);
    }
    @PostMapping("/retry") public WorkflowPublication.View retry(@PathVariable String id,
            @RequestHeader(value="X-Loopper-Local-UI",required=false) String authority,@RequestBody WorkflowRequests.VersionCommand request){
        WorkflowTemplateController.requireLocalUi(authority);return publications.retry(id,request.expectedVersion());
    }
    @GetMapping("/sources") public CursorPage<WorkflowPublicationPreview.Source> sources(@PathVariable String id,@RequestParam int revision,
            @RequestParam(required=false) String cursor,@RequestParam(defaultValue="50") int limit){return reads.sources(id,revision,cursor,limit);}
    @GetMapping("/preview") public WorkflowPublicationPreview preview(@PathVariable String id,@RequestParam int revision,@RequestParam String node,
            @RequestParam String attempt,@RequestParam String output){return reads.preview(id,revision,node,attempt,output);}
}
