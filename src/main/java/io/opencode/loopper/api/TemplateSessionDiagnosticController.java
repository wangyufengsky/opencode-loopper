package io.opencode.loopper.api;

import io.opencode.loopper.service.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tasks/{taskId}/session-diagnostics")
public class TemplateSessionDiagnosticController {
    private final TemplateSessionDiagnostics diagnostics;
    private final TemplateBatchRecoveryStore recovery;
    private final TemplateTaskCoordinator coordinator;
    public TemplateSessionDiagnosticController(TemplateSessionDiagnostics diagnostics, TemplateBatchRecoveryStore recovery,
            TemplateTaskCoordinator coordinator) { this.diagnostics = diagnostics; this.recovery = recovery; this.coordinator = coordinator; }

    @GetMapping public CursorPage<TemplateSessionDiagnostics.Diagnostic> list(@PathVariable String taskId,
            @RequestParam(defaultValue = "ATTENTION") String filter, @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) { return diagnostics.list(taskId, filter, cursor, limit); }

    @GetMapping("/{batchId}") public TemplateSessionDiagnostics.Diagnostic get(@PathVariable String taskId,
            @PathVariable String batchId) { return diagnostics.get(taskId, batchId); }

    @PostMapping("/{batchId}/recover") public TemplateSessionDiagnostics.Diagnostic recover(@PathVariable String taskId,
            @PathVariable String batchId, @RequestHeader(value = "X-Loopper-Local-UI", required = false) String localUi,
            @RequestBody Request request) {
        if (!"1".equals(localUi)) throw new BadRequestException("LOCAL_UI_HEADER_REQUIRED", "请从本地页面发起批次恢复");
        diagnostics.get(taskId, batchId);
        recovery.request(taskId, batchId, request.action(), request.expectedVersion(), request.commandId());
        coordinator.dispatch(taskId);
        return diagnostics.get(taskId, batchId);
    }

    public record Request(String action, long expectedVersion, String commandId) { }
}
