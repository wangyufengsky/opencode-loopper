package io.opencode.loopper.service;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.template.*;
import io.opencode.loopper.template.SnapshotReview.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** Renders only independently reviewed, frozen claims; artifacts never come from model Markdown. */
@Service
public class SnapshotReviewReports {
    private final TemplateReportArtifactService artifacts;
    private final TemplateReportBundleService bundles;
    private final LoopperMapper tasks;
    private final SnapshotReviewBatches batches;
    private final SnapshotReviewMapper records;
    private final SnapshotReviewAuthors authors;
    public SnapshotReviewReports(TemplateReportArtifactService artifacts, TemplateReportBundleService bundles,
            LoopperMapper tasks, SnapshotReviewBatches batches, SnapshotReviewMapper records, SnapshotReviewAuthors authors) {
        this.artifacts = artifacts; this.bundles = bundles; this.tasks = tasks; this.batches = batches; this.records = records; this.authors = authors;
    }
    public void publish(String taskId, AttemptRow attempt, Snapshot snapshot, Plan plan,
                        List<TemplateTaskBatchRow> analyses, List<TemplateTaskBatchRow> relations, List<TemplateTaskBatchRow> reviews) {
        publish(taskId, attempt, snapshot, plan, analyses, relations, reviews, false);
    }
    public void publishLightweight(String taskId, AttemptRow attempt, Snapshot snapshot, Plan plan,
                        List<TemplateTaskBatchRow> analyses, List<TemplateTaskBatchRow> reviews) {
        publish(taskId, attempt, snapshot, plan, analyses, List.of(), reviews, true);
    }
    private void publish(String taskId, AttemptRow attempt, Snapshot snapshot, Plan plan,
                        List<TemplateTaskBatchRow> analyses, List<TemplateTaskBatchRow> relations, List<TemplateTaskBatchRow> reviews, boolean lightweight) {
        var task = tasks.findTask(taskId).orElseThrow();
        String start = date(snapshot.startInclusive() == null ? snapshot.capturedAt() : snapshot.startInclusive());
        String end = snapshot.endExclusive() == null ? start : date(Instant.parse(snapshot.endExclusive()).minusNanos(1).toString());
        var bundle = bundles.prepareNamed(task, attempt, snapshot.baselineSha() == null ? "代码审查-全面" : "代码审查-日期增量", start, end);
        var names = new TemplateReportNames(snapshot.baselineSha() == null ? "代码审查-全面" : "代码审查-日期增量", bundle.projectName(), start, end, bundle.sequence());
        var sources = new ArrayList<>(analyses); sources.addAll(relations);
        var accepted = sources.stream().map(r -> new SnapshotReviewReportCompiler.AnalysisResult(r.id(),batches.input(r),batches.output(r,Analysis.class))).toList();
        var judgments = reviews.stream().map(r -> new SnapshotReviewReportCompiler.ReviewResult(r.id(),batches.input(r).analysisBatchId(),batches.output(r,Review.class))).toList();
        var reuse = records.reuses(taskId).stream().filter(r -> analyses.stream().anyMatch(a -> a.id().equals(r.batchId()))).toList();
        String reuseSummary = "\n\n复用历史有效分析 " + reuse.size() + " 批；复用项未新建模型会话，历史无问题结论未经独立复核。\n";
        for (var r : reuse) reuseSummary += "\n- 来源任务 " + r.sourceTaskId() + " / 批次 " + r.sourceBatchId() + "；源结果 SHA-256 " + r.outputSha256();
        TemplateReportCompiler.Result result;
        try{result=SnapshotReviewReportCompiler.compile(bundle.projectName(),names,start,end,snapshot,plan,accepted,judgments,lightweight,true,reuseSummary,refs->authors.render(taskId,snapshot,refs));}
        catch(SnapshotReviewReportCompiler.Invalid invalid){throw new ConflictException(invalid.code(),invalid.getMessage());}
        artifacts.publishCompiled(attempt,result,bundle);
    }
    private static String date(String instant) { return Instant.parse(instant).atZone(TemplateDateRange.ZONE).toLocalDate().toString(); }
}
