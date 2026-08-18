package io.opencode.loopper.api;

import io.opencode.loopper.service.AutomationService;
import io.opencode.loopper.service.LoopSpecTemplateService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Local automation/template API.  Webhooks remain loopback-only and carry their secret in the path. */
@RestController
@RequestMapping("/api/automations")
public class AutomationController {
    private final LoopSpecTemplateService templates;
    private final AutomationService automation;
    public AutomationController(LoopSpecTemplateService templates, AutomationService automation) { this.templates = templates; this.automation = automation; }

    @GetMapping("/templates") public List<LoopSpecTemplateService.TemplateView> templates() { return templates.list(); }
    @GetMapping("/workspace") public AutomationWorkspace workspace() {
        AutomationService.RunFeed runs = automation.allRuns();
        return new AutomationWorkspace(templates.list(), automation.rules(), runs.runs(), runs.serverTime());
    }
    @PostMapping("/templates") public LoopSpecTemplateService.TemplateView createTemplate(@RequestHeader(value = "X-Loopper-Local-UI", required = false) String localUi, @RequestBody FeatureContracts.CreateTemplateRequest request) { requireLocalUi(localUi);
        return templates.create(request.name(), request.description());
    }
    @GetMapping("/templates/{id}") public LoopSpecTemplateService.TemplateView template(@PathVariable String id) { return templates.get(id); }
    @PutMapping("/templates/{id}") public LoopSpecTemplateService.TemplateView updateTemplate(@PathVariable String id, @RequestHeader(value = "X-Loopper-Local-UI", required = false) String localUi, @RequestBody FeatureContracts.UpdateTemplateRequest request) { requireLocalUi(localUi);
        return templates.update(id, request.name(), request.description(), request.state(), request.version());
    }
    @GetMapping("/templates/{id}/versions") public List<LoopSpecTemplateService.VersionView> versions(@PathVariable String id) { return templates.versions(id); }
    @PostMapping("/templates/{id}/versions") public LoopSpecTemplateService.VersionView createVersion(@PathVariable String id, @RequestHeader(value = "X-Loopper-Local-UI", required = false) String localUi, @RequestBody FeatureContracts.CreateTemplateVersionRequest request) { requireLocalUi(localUi);
        return templates.createVersion(id, request.spec(), request.autoStartApproved());
    }
    @PostMapping(value = "/templates/import/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    public AutomationService.WorkspacePreview importPreview(@RequestHeader(value = "X-Loopper-Local-UI", required = false) String localUi, @RequestBody String source) { requireLocalUi(localUi); return automation.previewWorkspace(source); }
    @PostMapping("/templates/import/{previewId}/confirm") public AutomationService.WorkspaceImportResult confirmImport(@PathVariable String previewId, @RequestHeader(value = "X-Loopper-Local-UI", required = false) String localUi) { requireLocalUi(localUi);
        return automation.confirmWorkspaceImport(previewId);
    }
    @GetMapping(value = "/templates/{id}/export", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> export(@PathVariable String id) { return ResponseEntity.ok(templates.export(id)); }
    @GetMapping(value = "/templates/export", produces = MediaType.APPLICATION_JSON_VALUE)
    public AutomationService.WorkspaceExport exportWorkspace() { return automation.exportWorkspace(); }

    @GetMapping("/rules") public List<AutomationService.RuleView> rules() { return automation.rules(); }
    @PostMapping("/rules") public AutomationService.RuleMutation createRule(@RequestHeader(value = "X-Loopper-Local-UI", required = false) String localUi, @RequestBody FeatureContracts.CreateAutomationRuleRequest request) { requireLocalUi(localUi); return automation.create(input(request)); }
    @PutMapping("/rules/{id}") public AutomationService.RuleView updateRule(@PathVariable String id, @RequestHeader(value = "X-Loopper-Local-UI", required = false) String localUi, @RequestBody FeatureContracts.UpdateAutomationRuleRequest request) { requireLocalUi(localUi); return automation.update(id, input(automation.ruleById(id), request), request.version()); }
    @PostMapping("/rules/{id}/trigger") public AutomationService.RunView manual(@PathVariable String id, @RequestHeader(value = "X-Loopper-Local-UI", required = false) String localUi) { requireLocalUi(localUi); return automation.manual(id); }
    @GetMapping("/rules/{id}/runs") public List<AutomationService.RunView> runs(@PathVariable String id) { return automation.runs(id); }
    @GetMapping("/runs") public AutomationService.RunFeed allRuns() { return automation.allRuns(); }
    @PostMapping("/runs/{id}/confirm") public AutomationService.RunView confirm(@PathVariable String id, @RequestHeader(value = "X-Loopper-Local-UI", required = false) String localUi, @RequestBody(required = false) FeatureContracts.ConfirmAutomationRunRequest request) { requireLocalUi(localUi);
        return automation.confirmReview(id, request == null ? null : request.title());
    }
    @PostMapping("/webhooks/{ruleId}/{token}")
    public AutomationService.RunView webhook(@PathVariable String ruleId, @PathVariable String token, @RequestBody(required = false) String body,
                                              @RequestHeader(value = "X-Loopper-Delivery-Id", required = false) String deliveryId,
                                              HttpServletRequest request) {
        return automation.webhook(ruleId, token, request.getRemoteAddr(), body, deliveryId);
    }

    private AutomationService.RuleInput input(FeatureContracts.CreateAutomationRuleRequest request) {
        return new AutomationService.RuleInput(request.name(), request.projectId(), request.templateVersionId(), request.triggerType(),
                request.triggerConfig(), io.opencode.loopper.domain.AutomationRuleState.DISABLED.name(),
                io.opencode.loopper.domain.AutomationApprovalMode.REVIEW_REQUIRED);
    }
    private AutomationService.RuleInput input(AutomationService.RuleView old, FeatureContracts.UpdateAutomationRuleRequest request) {
        return new AutomationService.RuleInput(request.name(), old.projectId(), request.templateVersionId(), request.triggerType(),
                request.triggerConfig(), request.state(), request.approvalMode());
    }
    private void requireLocalUi(String localUi) { if (!"1".equals(localUi)) throw new io.opencode.loopper.service.BadRequestException("LOCAL_UI_HEADER_REQUIRED", "This operation is available only to the local Loopper UI"); }
    public record AutomationWorkspace(List<LoopSpecTemplateService.TemplateView> templates,
                                      List<AutomationService.RuleView> rules,
                                      List<AutomationService.RunView> runs, String serverTime) { }
}
