package io.opencode.loopper.api;

import io.opencode.loopper.service.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** Document upload has an independent identity before an executable Task exists. */
@RestController
@RequestMapping("/api/template-tasks/document-runs")
public class DocumentTemplateController {
    private final DocumentTemplateService service;
    private final DocumentTemplateReadService reads;
    private final DocumentTemplateControl control;
    private final DocumentTemplateCoordinator coordinator;
    private final DocumentRequirementReportService reports;
    public DocumentTemplateController(DocumentTemplateService service, DocumentTemplateReadService reads,
            DocumentTemplateControl control, DocumentTemplateCoordinator coordinator, DocumentRequirementReportService reports) {
        this.service = service; this.reads = reads; this.control = control; this.coordinator = coordinator; this.reports = reports;
    }
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentTemplateReadService.Overview> create(
            @RequestHeader(value = "X-Loopper-Local-UI", required = false) String localUi,
            @RequestPart("metadata") DocumentTemplateService.Request request,
            @RequestPart("files") List<MultipartFile> files) throws IOException {
        requireLocalUi(localUi);
        if (files.isEmpty() || files.size() > 10) throw new BadRequestException("DOCUMENT_TEMPLATE_FILES_REQUIRED", "请上传 1–10 份需求文档");
        long total = 0;
        for (var file : files) {
            if (file.getSize() == 0 || file.getSize() > DocumentTemplateStorage.MAX_FILE_BYTES)
                throw new BadRequestException("DOCUMENT_TEMPLATE_FILE_SIZE", "单文件不能为空或超过 20 MiB");
            total += file.getSize();
        }
        if (total > DocumentTemplateStorage.MAX_BATCH_BYTES)
            throw new BadRequestException("DOCUMENT_TEMPLATE_BATCH_SIZE", "本次文档总大小超过 50 MiB");
        var incoming = new ArrayList<DocumentTemplateStorage.Incoming>();
        for (var file : files) incoming.add(new DocumentTemplateStorage.Incoming(file.getOriginalFilename(), file.getBytes()));
        var run = service.create(request, incoming);
        coordinator.dispatch(run.id());
        return ResponseEntity.accepted().body(reads.overview(run.id()));
    }
    @GetMapping("/{id}")
    public DocumentTemplateReadService.Overview overview(@PathVariable String id) { return reads.overview(id); }
    @GetMapping("/by-request/{key}")
    public DocumentTemplateReadService.Overview request(@PathVariable String key) { return reads.request(key); }
    @PostMapping("/{id}/{action:cancel|resume|archive|unarchive}")
    public ResponseEntity<DocumentTemplateReadService.Overview> command(@PathVariable String id, @PathVariable String action,
            @RequestHeader(value = "X-Loopper-Local-UI", required = false) String localUi,
            @RequestBody DocumentTemplateControl.Command command) {
        requireLocalUi(localUi); control.command(id, action, command); coordinator.dispatch(id);
        return ResponseEntity.accepted().body(reads.overview(id));
    }
    @GetMapping("/{id}/requirements")
    public DocumentTemplateReadService.RequirementPage requirements(@PathVariable String id, @RequestParam int revision,
            @RequestParam(defaultValue = "-1") int after, @RequestParam(defaultValue = "false") boolean issuesOnly) {
        return reads.requirements(id, revision, after, issuesOnly);
    }
    @GetMapping("/{id}/requirements/{key}")
    public DocumentTemplateReadService.RequirementDetail requirement(@PathVariable String id, @PathVariable String key,
            @RequestParam int revision) { return reads.requirement(id, revision, key); }
    @GetMapping("/{id}/reports")
    public CursorPage<io.opencode.loopper.persistence.DocumentArtifactMapper.Summary> reports(@PathVariable String id,
            @RequestParam(defaultValue = "") String cursor, @RequestParam(defaultValue = "50") int limit) {
        return reports.list(id, cursor, limit);
    }
    @GetMapping("/{id}/reports/{artifactId}")
    public io.opencode.loopper.persistence.DocumentArtifactMapper.Artifact report(@PathVariable String id, @PathVariable String artifactId) {
        return reports.read(id, artifactId);
    }
    @GetMapping("/{id}/reports/download")
    public ResponseEntity<byte[]> download(@PathVariable String id) {
        var download = reports.download(id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
                .header("Content-Disposition", org.springframework.http.ContentDisposition.attachment()
                        .filename(download.filename(), java.nio.charset.StandardCharsets.UTF_8).build().toString()).body(download.bytes());
    }
    @GetMapping("/{id}/reports/content")
    public io.opencode.loopper.persistence.DocumentArtifactMapper.Artifact named(@PathVariable String id, @RequestParam String name) {
        return reports.named(id, name);
    }
    @GetMapping("/{id}/documents/{fileId}/sections")
    public DocumentTemplateReadService.SectionPage sections(@PathVariable String id, @PathVariable String fileId,
            @RequestParam(defaultValue = "0") int offset) { return reads.sections(id, fileId, offset); }
    @GetMapping("/{id}/documents/{fileId}/sections/{ordinal}")
    public io.opencode.loopper.persistence.DocumentTemplateMapper.Section section(@PathVariable String id,
            @PathVariable String fileId, @PathVariable int ordinal, @RequestParam String expectedSha) {
        return reads.section(id, fileId, ordinal, expectedSha);
    }
    private static void requireLocalUi(String value) {
        if (!"1".equals(value)) throw new BadRequestException("LOCAL_UI_HEADER_REQUIRED", "请从本地页面发起模板任务");
    }
}
