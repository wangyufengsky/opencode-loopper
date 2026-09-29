package io.opencode.loopper.api;

import io.opencode.loopper.persistence.WorkflowRows;
import io.opencode.loopper.service.BadRequestException;
import io.opencode.loopper.service.workflow.*;
import io.opencode.loopper.workflow.*;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workflows/templates")
public final class WorkflowTemplateController {
    private final WorkflowTemplates templates;
    public WorkflowTemplateController(WorkflowTemplates templates) { this.templates = templates; }
    @GetMapping public CursorPage<WorkflowRows.TemplateSummary> list(
            @RequestParam(required=false) String query, @RequestParam(required=false) String kind,
            @RequestParam(required=false) String cursor, @RequestParam(defaultValue="50") int limit) {
        return templates.list(query, kind, cursor, limit);
    }
    @GetMapping("/{id}") public WorkflowTemplates.Detail get(@PathVariable String id, @RequestParam(required=false) Integer revision) {
        return templates.get(id, revision);
    }
    @PostMapping("/validate") public List<WorkflowGraphValidator.Diagnostic> validate(@RequestBody WorkflowGraph graph) {
        return WorkflowGraphValidator.validate(graph, WorkflowGraphValidator.Mode.EXECUTION);
    }
    @PostMapping public WorkflowCommands.Receipt create(@RequestHeader(value="X-Loopper-Local-UI",required=false) String authority,
            @RequestBody WorkflowRequests.CreateTemplate request) {
        requireLocalUi(authority); return templates.create(request);
    }
    @PutMapping("/{id}") public WorkflowCommands.Receipt revise(@PathVariable String id,
            @RequestHeader(value="X-Loopper-Local-UI",required=false) String authority, @RequestBody WorkflowRequests.ReviseTemplate request) {
        requireLocalUi(authority); return templates.revise(id, request);
    }
    @PostMapping("/{id}/copy") public WorkflowCommands.Receipt copy(@PathVariable String id,
            @RequestHeader(value="X-Loopper-Local-UI",required=false) String authority, @RequestBody WorkflowRequests.CopyTemplate request) {
        requireLocalUi(authority); return templates.copy(id, request);
    }
    @DeleteMapping("/{id}") public WorkflowCommands.Receipt archive(@PathVariable String id,
            @RequestHeader(value="X-Loopper-Local-UI",required=false) String authority, @RequestBody WorkflowRequests.VersionCommand request) {
        requireLocalUi(authority); return templates.archive(id, request);
    }
    @PutMapping("/{id}/layout") public WorkflowCommands.Receipt layout(@PathVariable String id,
            @RequestHeader(value="X-Loopper-Local-UI",required=false) String authority, @RequestBody WorkflowRequests.Layout request) {
        requireLocalUi(authority); return templates.layout(id, request);
    }
    static void requireLocalUi(String value) {
        if (!"1".equals(value)) throw new BadRequestException("LOCAL_UI_HEADER_REQUIRED", "请从本地页面修改流程");
    }
}
