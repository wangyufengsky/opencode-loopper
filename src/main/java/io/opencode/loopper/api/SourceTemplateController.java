package io.opencode.loopper.api;

import io.opencode.loopper.persistence.SourceTemplateMapper;
import io.opencode.loopper.persistence.SourceTemplateModelMapper;
import io.opencode.loopper.service.*;
import io.opencode.loopper.template.SourceTemplateRequests;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/template-tasks/source-runs")
public final class SourceTemplateController {
    private final SourceTemplateService service;
    private final SourceTemplateAdmission admission;
    private final SourceTemplateReadService reads;
    private final SourceTemplateControl controls;
    private final SourceTemplateCoordinator coordinator;
    public SourceTemplateController(SourceTemplateService service, SourceTemplateAdmission admission, SourceTemplateReadService reads,
            SourceTemplateControl controls, SourceTemplateCoordinator coordinator) {
        this.service = service; this.admission = admission; this.reads = reads;
        this.controls = controls; this.coordinator = coordinator;
    }
    @PostMapping("/preview")
    public SourceTemplateService.Preview preview(@RequestHeader(value = "X-Loopper-Local-UI", required = false) String local,
            @RequestBody SourceTemplateRequests.Create input) {
        requireLocal(local); return service.preview(input);
    }
    @PostMapping
    public SourceTemplateReadService.Overview create(@RequestHeader(value = "X-Loopper-Local-UI", required = false) String local,
            @RequestBody SourceTemplateRequests.Create input) {
        requireLocal(local); return reads.overview(service.create(input).id());
    }
    @PostMapping("/{id}/start")
    public SourceTemplateReadService.Overview start(@PathVariable String id,
            @RequestHeader(value = "X-Loopper-Local-UI", required = false) String local,
            @RequestBody SourceTemplateRequests.Command command) {
        requireLocal(local); admission.start(id, command); coordinator.dispatch(id); return reads.overview(id);
    }
    @PostMapping("/{id}/controls/{action}")
    public SourceTemplateReadService.Overview control(@PathVariable String id, @PathVariable String action,
            @RequestHeader(value = "X-Loopper-Local-UI", required = false) String local,
            @RequestBody SourceTemplateControl.Command command) {
        requireLocal(local); controls.command(id, action, command); coordinator.dispatch(id); return reads.overview(id);
    }
    @GetMapping("/{id}")
    public SourceTemplateReadService.Overview overview(@PathVariable String id) { return reads.overview(id); }
    @GetMapping("/{id}/batches")
    public CursorPage<SourceTemplateModelMapper.Metadata> batches(@PathVariable String id,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "50") int limit) {
        return reads.batches(id, cursor, limit);
    }
    @GetMapping("/{id}/coverage")
    public CursorPage<SourceTemplateMapper.Coverage> coverage(@PathVariable String id,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "50") int limit) {
        return reads.coverage(id, cursor, limit);
    }
    @GetMapping("/{id}/source")
    public SourceTemplateReadService.SourceText source(@PathVariable String id, @RequestParam String path,
            @RequestParam(defaultValue = "1") int startLine, @RequestParam(defaultValue = "100") int limit) {
        return reads.source(id, path, startLine, limit);
    }
    @GetMapping("/{id}/coverage/item")
    public SourceTemplateMapper.Coverage coverageItem(@PathVariable String id, @RequestParam String path) { return reads.coverageItem(id, path); }
    static void requireLocal(String local) {
        if (!"1".equals(local)) throw new BadRequestException("LOCAL_UI_HEADER_REQUIRED", "请从本地页面操作源码模板");
    }
}
