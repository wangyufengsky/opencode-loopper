package io.opencode.loopper.api;

import io.opencode.loopper.service.workflow.WorkflowSnapshotPartialReports;
import io.opencode.loopper.workflow.WorkflowSnapshotPartialReport;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workflows/requirements/{id}/nodes/{key}/attempts/{attempt}")
public final class WorkflowSnapshotReportController {
    private final WorkflowSnapshotPartialReports reports;
    public WorkflowSnapshotReportController(WorkflowSnapshotPartialReports reports){this.reports=reports;}
    @GetMapping("/snapshot-partial-report") public WorkflowSnapshotPartialReport read(@PathVariable String id,@PathVariable String key,@PathVariable String attempt){return reports.read(id,key,attempt);}
}
