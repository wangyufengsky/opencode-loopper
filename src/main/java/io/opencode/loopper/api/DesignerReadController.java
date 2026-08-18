package io.opencode.loopper.api;

import io.opencode.loopper.service.DesignerReadService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/designer-sessions")
public class DesignerReadController {
    private final DesignerReadService reads;

    public DesignerReadController(DesignerReadService reads) {
        this.reads = reads;
    }

    @GetMapping("/history-page")
    public CursorPage<DesignerReadService.HistoryItem> history(
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "ACTIVE") String archive,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "newest") String order,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return reads.history(projectId, status, archive, q, order, cursor, limit);
    }

    @GetMapping("/{id}/overview")
    public DesignerReadService.Overview overview(@PathVariable String id) {
        return reads.overview(id);
    }

    @GetMapping("/{id}/messages-page")
    public CursorPage<DesignerReadService.MessageItem> messages(
            @PathVariable String id,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return reads.messages(id, cursor, limit);
    }
}
