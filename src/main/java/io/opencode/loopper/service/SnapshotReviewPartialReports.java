package io.opencode.loopper.service;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.template.SnapshotReview.*;
import io.opencode.loopper.template.SnapshotReviewPartialCompiler;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** A read-only observation of persisted results, usable after cancellation without resuming work. */
@Service
public class SnapshotReviewPartialReports {
    private final SnapshotReviewStore snapshots;
    private final SnapshotReviewMapper records;
    private final LoopperMapper tasks;
    private final ObjectMapper json;
    private final SnapshotReviewAuthors authors;
    public SnapshotReviewPartialReports(SnapshotReviewStore snapshots, SnapshotReviewMapper records, LoopperMapper tasks, ObjectMapper json, SnapshotReviewAuthors authors) {
        this.snapshots = snapshots; this.records = records; this.tasks = tasks; this.json = json; this.authors = authors;
    }
    public record Report(String content, String sha256, String capturedAt, int analyzedUnits, int pendingUnits, int excludedUnits) { }
    @Transactional(readOnly = true)
    public Report read(String taskId) {
        var task = tasks.findTask(taskId).orElseThrow(() -> new NotFoundException("任务不存在"));
        var snapshot = snapshots.snapshot(taskId);
        if (records.reportSize(taskId) > 32_000_000) throw new ConflictException("SNAPSHOT_REPORT_TOO_LARGE", "阶段报告超过在线读取容量，请分别查看批次结果");
        var rows = records.currentBatches(taskId);
        Map<String, Analysis> analyses = new LinkedHashMap<>(); Map<String, SnapshotReviewPartialCompiler.Judgment> reviews = new LinkedHashMap<>();
        for (var row : rows) {
            if (!row.state().equals("VALIDATED") || row.outputJson() == null) continue;
            if (Set.of("SNAPSHOT_ANALYSIS", "SNAPSHOT_SUPPLEMENT", "SNAPSHOT_RELATION_ANALYSIS").contains(row.purpose())) {
                var result = json.readValue(row.outputJson(), Analysis.class); analyses.put(row.id(), result);
            } else if (Set.of("SNAPSHOT_REVIEW", "SNAPSHOT_RELATION_REVIEW").contains(row.purpose())) {
                var input = json.readValue(row.inputJson(), TemplateBatchExecution.Input.class).snapshot();
                reviews.put(input.analysisBatchId(), new SnapshotReviewPartialCompiler.Judgment(row.id(), "独立复核", input.analysisBatchId(), json.readValue(row.outputJson(), Review.class)));
            }
        }
        String at = Instant.now().toString();
        var reused = records.reuses(taskId).stream().filter(r -> analyses.containsKey(r.batchId())).toList();
        var note = new StringBuilder("复用历史有效分析 ").append(reused.size()).append(" 批（未创建新模型会话）。\n\n");
        reused.forEach(r -> note.append("- 复用来源任务 ").append(r.sourceTaskId()).append(" / 批次 ").append(r.sourceBatchId()).append("；源结果 SHA-256 ").append(r.outputSha256()).append("\n"));
        var batches = analyses.entrySet().stream().map(e -> new SnapshotReviewPartialCompiler.Batch(e.getKey(), "分析批次 " + e.getKey(), e.getValue())).toList();
        var judgments = List.copyOf(reviews.values());
        var rendered = SnapshotReviewPartialCompiler.compile(snapshot, at, task.state(), note.toString(), batches, judgments, refs -> authors.render(taskId, snapshot, refs));
        return new Report(rendered.content(), TemplateGitEvidenceCollector.hash(rendered.content()), at, rendered.analyzedUnits(), rendered.pendingUnits(), rendered.excludedUnits());
    }
}
