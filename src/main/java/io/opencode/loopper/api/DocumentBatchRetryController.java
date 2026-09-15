package io.opencode.loopper.api;

import io.opencode.loopper.persistence.TemplateTaskReadMapper;
import io.opencode.loopper.service.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/template-tasks/document-runs/{id}")
public class DocumentBatchRetryController {
    private final DocumentBatchRetryService retries;
    private final DocumentTemplateCoordinator coordinator;
    public DocumentBatchRetryController(DocumentBatchRetryService retries, DocumentTemplateCoordinator coordinator) {
        this.retries = retries; this.coordinator = coordinator;
    }
    @GetMapping("/failed-batches") public CursorPage<TemplateTaskReadMapper.FailedBatch> list(@PathVariable String id,
            @RequestParam(defaultValue = "") String cursor, @RequestParam(defaultValue = "50") int limit) {
        return retries.list(id, cursor, limit);
    }
    @PostMapping("/batches/{batchId}/retry") public TemplateTaskController.Created retry(@PathVariable String id,
            @PathVariable String batchId, @RequestHeader(value = "X-Loopper-Local-UI", required = false) String localUi,
            @RequestBody TemplateTaskController.Retry request) {
        if (!"1".equals(localUi)) throw new BadRequestException("LOCAL_UI_HEADER_REQUIRED", "请从本地页面重试批次");
        var next = retries.retry(id, batchId, request.expectedVersion());
        coordinator.dispatch(id);
        return new TemplateTaskController.Created(next.id(), next.state());
    }
    @PostMapping("/batches/retry") public java.util.List<TemplateTaskController.Created> retrySelected(@PathVariable String id,
            @RequestHeader(value = "X-Loopper-Local-UI", required = false) String localUi,
            @RequestBody BatchRetrySelection request) {
        if (!"1".equals(localUi)) throw new BadRequestException("LOCAL_UI_HEADER_REQUIRED", "请从本地页面重试批次");
        var next = retries.retrySelected(id, request);
        coordinator.dispatch(id);
        return next.stream().map(row -> new TemplateTaskController.Created(row.id(), row.state())).toList();
    }

}
