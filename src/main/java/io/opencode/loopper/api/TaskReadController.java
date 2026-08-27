package io.opencode.loopper.api;

import io.opencode.loopper.service.TaskReadService;
import io.opencode.loopper.service.TaskReadService.ContentView;
import io.opencode.loopper.service.TaskReadService.TaskAudit;
import io.opencode.loopper.service.TaskReadService.TaskOverview;
import io.opencode.loopper.service.TaskReadService.TaskSummary;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tasks")
public class TaskReadController {
    private final TaskReadService reads;

    public TaskReadController(TaskReadService reads) { this.reads = reads; }

    @GetMapping("/summaries")
    public CursorPage<TaskSummary> summaries(
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) String statusGroup,
            @RequestParam(defaultValue = "ACTIVE") String archive,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "newest") String order,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return reads.summaries(projectId, status, statusGroup, archive, q, order, cursor, limit);
    }

    @GetMapping("/{id}/overview") public TaskOverview overview(@PathVariable String id) { return reads.overview(id); }
    @GetMapping("/{id}/audit") public TaskAudit audit(@PathVariable String id) { return reads.audit(id); }
    @GetMapping("/{id}/verifications/{entryId}/evidence")
    public ContentView verificationEvidence(@PathVariable String id, @PathVariable String entryId) {
        return reads.verificationEvidence(id, entryId);
    }
    @GetMapping("/{id}/errors/{entryId}/evidence")
    public ContentView errorEvidence(@PathVariable String id, @PathVariable String entryId) {
        return reads.errorEvidence(id, entryId);
    }
    @GetMapping("/{id}/judges/{entryId}/output")
    public ContentView judgeOutput(@PathVariable String id, @PathVariable String entryId) {
        return reads.judgeOutput(id, entryId);
    }
    @GetMapping("/{id}/artifacts/{entryId}/content")
    public ContentView artifactContent(@PathVariable String id, @PathVariable String entryId) {
        return reads.artifactContent(id, entryId);
    }
}
