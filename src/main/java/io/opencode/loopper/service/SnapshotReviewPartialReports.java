package io.opencode.loopper.service;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.template.SnapshotReview.*;
import io.opencode.loopper.template.TemplateReportCompiler;
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
    public SnapshotReviewPartialReports(SnapshotReviewStore snapshots, SnapshotReviewMapper records, LoopperMapper tasks, ObjectMapper json) {
        this.snapshots = snapshots; this.records = records; this.tasks = tasks; this.json = json;
    }
    public record Report(String content, String sha256, String capturedAt, int analyzedUnits, int pendingUnits, int excludedUnits) { }
    @Transactional(readOnly = true)
    public Report read(String taskId) {
        var task = tasks.findTask(taskId).orElseThrow(() -> new NotFoundException("任务不存在"));
        var snapshot = snapshots.snapshot(taskId);
        if (records.reportSize(taskId) > 32_000_000) throw new ConflictException("SNAPSHOT_REPORT_TOO_LARGE", "阶段报告超过在线读取容量，请分别查看批次结果");
        var rows = records.currentBatches(taskId);
        Map<String, Analysis> analyses = new LinkedHashMap<>(); Map<String, Review> reviews = new HashMap<>();
        Set<String> completed = new HashSet<>();
        for (var row : rows) {
            if (!row.state().equals("VALIDATED") || row.outputJson() == null) continue;
            if (Set.of("SNAPSHOT_ANALYSIS", "SNAPSHOT_SUPPLEMENT", "SNAPSHOT_RELATION_ANALYSIS").contains(row.purpose())) {
                var result = json.readValue(row.outputJson(), Analysis.class); analyses.put(row.id(), result);
                result.coverage().forEach(c -> completed.add(c.unitId()));
            } else if (Set.of("SNAPSHOT_REVIEW", "SNAPSHOT_RELATION_REVIEW").contains(row.purpose())) {
                var input = json.readValue(row.inputJson(), TemplateBatchExecution.Input.class).snapshot();
                reviews.put(input.analysisBatchId(), json.readValue(row.outputJson(), Review.class));
            }
        }
        String at = Instant.now().toString();
        StringBuilder out = new StringBuilder("# 代码审查阶段报告（非完整报告）\n\n");
        out.append("生成时间：").append(at).append("\n\n任务状态：").append(task.state())
                .append("\n\n目标 SHA：").append(snapshot.targetSha()).append("\n\n基线 SHA：").append(Objects.toString(snapshot.baselineSha(), "无"))
                .append("\n\n只包含已校验且完成收尾的批次；正在执行、失败、已取消或停止尚未证实的结果不计入已分析。无问题项未经独立复核；本轮未执行目标项目测试。\n\n");
        var reused = records.reuses(taskId).stream().filter(r -> analyses.containsKey(r.batchId())).toList();
        out.append("复用历史有效分析 ").append(reused.size()).append(" 批（未创建新模型会话）。\n\n");
        reused.forEach(r -> out.append("- 复用来源任务 ").append(r.sourceTaskId()).append(" / 批次 ").append(r.sourceBatchId()).append("；源结果 SHA-256 ").append(r.outputSha256()).append("\n"));
        int excluded = 0, analyzed = 0, pending = 0;
        out.append("## 覆盖清单\n\n| 文件 / 单元 | 状态 |\n| --- | --- |\n");
        for (var unit : snapshot.units()) {
            boolean omitted = unit.excerpt().isBlank() && unit.limitation() != null;
            String state;
            if (omitted) { excluded++; state = "已排除，未审查：" + unit.limitation(); }
            else if (completed.contains(unit.id())) { analyzed++; state = "已分析，复核状态见问题详情"; }
            else { pending++; state = "尚未完成分析"; }
            out.append("| ").append(text(unit.path())).append(" / ").append(text(unit.id())).append(" | ").append(text(state)).append(" |\n");
        }
        out.append("\n已分析 ").append(analyzed).append("；未完成 ").append(pending).append("；排除 ").append(excluded).append("。\n\n## 已保存问题与证据\n\n");
        for (var entry : analyses.entrySet()) {
            var review = reviews.get(entry.getKey());
            out.append("### 分析批次 ").append(entry.getKey()).append("\n\n");
            for (var finding : entry.getValue().findings()) {
                var decision = review == null ? null : review.decisions().stream().filter(d -> d.findingKey().equals(finding.key())).findFirst().orElse(null);
                String verdict = decision == null ? "尚未独立复核（仅候选）" : switch (decision.verdict()) {
                    case SUPPORTED -> "独立复核支持"; case DISMISSED -> "复核不成立";
                    case UNDETERMINED -> "复核待确认"; case DUPLICATE -> "复核判定重复";
                };
                out.append("#### ").append(text(finding.title())).append("\n\n").append(finding.severity()).append(" · ").append(verdict)
                        .append("\n\n触发条件：").append(text(finding.trigger())).append("\n\n错误行为：").append(text(finding.behavior()))
                        .append("\n\n建议：").append(text(finding.recommendation())).append("\n\n");
                references(out, finding.evidence());
                if (decision != null) { out.append(text(decision.reason())).append("\n\n"); references(out, decision.evidence()); }
            }
            if (entry.getValue().findings().isEmpty()) out.append("本批未报告候选问题，未经独立复核，不代表证明无缺陷。\n\n");
            entry.getValue().limitations().forEach(l -> out.append("- 局限：").append(text(l)).append("\n"));
            entry.getValue().coverage().forEach(c -> c.limitations().forEach(l -> out.append("- 单元 ").append(text(c.unitId())).append("：").append(text(l)).append("\n")));
            if (review != null) review.limitations().forEach(l -> out.append("- 复核局限：").append(text(l)).append("\n"));
        }
        String content = out.toString();
        return new Report(content, TemplateGitEvidenceCollector.hash(content), at, analyzed, pending, excluded);
    }
    private static void references(StringBuilder out, List<Reference> refs) {
        for (var r : refs) out.append("- ").append(text(r.path())).append("：").append(r.startLine()).append("–").append(r.endLine())
                .append("；版本 ").append(r.version()).append("；blob ").append(r.blob()).append("\n\n    ")
                .append(text(r.quote()).replace("\n", "\n    ")).append("\n\n");
    }
    private static String text(String value) { return TemplateReportCompiler.text(value); }
}
