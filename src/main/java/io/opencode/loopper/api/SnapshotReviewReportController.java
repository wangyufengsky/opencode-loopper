package io.opencode.loopper.api;

import io.opencode.loopper.service.SnapshotReviewPartialReports;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class SnapshotReviewReportController {
    private final SnapshotReviewPartialReports reports;
    public SnapshotReviewReportController(SnapshotReviewPartialReports reports) { this.reports = reports; }
    @GetMapping("/api/tasks/{taskId}/snapshot-review/partial-report")
    public ResponseEntity<SnapshotReviewPartialReports.Report> read(@PathVariable String taskId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reports.read(taskId));
    }
}
