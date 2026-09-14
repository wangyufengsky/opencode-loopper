package io.opencode.loopper.api;

import io.opencode.loopper.service.*;
import io.opencode.loopper.template.DocumentModelInput;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/template-tasks/document-runs/{id}/clarifications")
public final class DocumentClarificationController {
    private final DocumentRequirementClarifications clarifications;
    private final DocumentTemplateReadService reads;
    private final DocumentProgressEvents events;
    public DocumentClarificationController(DocumentRequirementClarifications clarifications, DocumentTemplateReadService reads,
            DocumentProgressEvents events) { this.clarifications = clarifications; this.reads = reads; this.events = events; }
    @PostMapping
    public DocumentTemplateReadService.Overview submit(@PathVariable String id,
            @RequestHeader(value = "X-Loopper-Local-UI", required = false) String localUi,
            @RequestBody DocumentRequirementClarifications.Request request) {
        if (!"1".equals(localUi)) throw new BadRequestException("LOCAL_UI_HEADER_REQUIRED", "请从本地页面回答需求问题");
        clarifications.submit(id, request); events.publish(id); return reads.overview(id);
    }
    @GetMapping
    public List<DocumentModelInput.Clarification> history(@PathVariable String id, @RequestParam int revision) {
        return clarifications.answers(id, revision);
    }
}
