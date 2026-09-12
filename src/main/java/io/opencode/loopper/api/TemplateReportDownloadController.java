package io.opencode.loopper.api;

import io.opencode.loopper.service.TemplateReportDownloadService;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class TemplateReportDownloadController {
    private final TemplateReportDownloadService reports;
    public TemplateReportDownloadController(TemplateReportDownloadService reports) { this.reports = reports; }
    @GetMapping("/api/tasks/{taskId}/template-reports/{artifactId}/download")
    public ResponseEntity<byte[]> download(@PathVariable String taskId, @PathVariable String artifactId) {
        var result = reports.download(taskId, artifactId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(result.filename(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(result.bytes());
    }
}
