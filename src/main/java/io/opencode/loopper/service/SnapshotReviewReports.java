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
    public SnapshotReviewReports(TemplateReportArtifactService artifacts, TemplateReportBundleService bundles,
            LoopperMapper tasks, SnapshotReviewBatches batches) {
        this.artifacts = artifacts; this.bundles = bundles; this.tasks = tasks; this.batches = batches;
    }
    public void publish(String taskId, AttemptRow attempt, Snapshot snapshot, Plan plan,
                        List<TemplateTaskBatchRow> analyses, List<TemplateTaskBatchRow> relations, List<TemplateTaskBatchRow> reviews) {
        var task = tasks.findTask(taskId).orElseThrow();
        String start = date(snapshot.startInclusive() == null ? snapshot.capturedAt() : snapshot.startInclusive());
        String end = snapshot.endExclusive() == null ? start : date(Instant.parse(snapshot.endExclusive()).minusNanos(1).toString());
        var bundle = bundles.prepareNamed(task, attempt, snapshot.baselineSha() == null ? "代码审查-全面" : "代码审查-日期增量", start, end);
        var names = new TemplateReportNames(snapshot.baselineSha() == null ? "代码审查-全面" : "代码审查-日期增量", bundle.projectName(), start, end, bundle.sequence());
        var documents = new ArrayList<TemplateReportCompiler.Document>();
        Map<String, Review> judgments = new LinkedHashMap<>();
        reviews.forEach(r -> judgments.put(batches.input(r).analysisBatchId(), batches.output(r, Review.class)));
        var sources = new ArrayList<>(analyses); sources.addAll(relations);
        StringBuilder supported = new StringBuilder(), uncertain = new StringBuilder(), coverage = new StringBuilder();
        coverage.append("| 单元 | 文件 | 分组归属 / 处理情况 |\n| --- | --- | --- |\n");
        for (var unit : snapshot.units()) {
            var owner = plan.groups().stream().filter(g -> g.unitIds().contains(unit.id())).findFirst().orElseThrow();
            coverage.append("| ").append(text(unit.id())).append(" | ").append(text(unit.path())).append(" | ").append(text(owner.title()))
                    .append(unit.limitation() == null ? "" : "；" + text(unit.limitation())).append(" |\n");
        }
        Map<String, StringBuilder> duplicateSources = new HashMap<>();
        Map<String, String> reviewIds = new HashMap<>();
        reviews.forEach(r -> reviewIds.put(batches.input(r).analysisBatchId(), r.id()));
        for (var r : sources) for (var d : judgments.get(r.id()).decisions()) if (d.verdict() == Verdict.DUPLICATE) {
            var f = batches.output(r, Analysis.class).findings().stream().filter(v -> v.key().equals(d.findingKey())).findFirst().orElseThrow();
            String target = d.duplicateOf().contains("/") ? d.duplicateOf() : reviewIds.get(r.id()) + "/" + d.duplicateOf();
            duplicateSources.computeIfAbsent(target, ignored -> new StringBuilder()).append("\n关联分析来源：")
                    .append(text(batches.input(r).objective())).append("；关联问题：").append(text(f.title())).append("\n\n")
                    .append("合并依据：").append(text(d.reason())).append("\n\n").append(references(f.evidence())).append(references(d.evidence()));
        }
        int found = 0, pending = 0, ordinal = 0;
        StringBuilder links = new StringBuilder();
        for (var row : sources) {
            var analysis = batches.output(row, Analysis.class); var review = judgments.get(row.id());
            if (review == null) throw new ConflictException("SNAPSHOT_REVIEW_MISSING", "缺少独立复核，不能生成完整报告");
            String path = names.child("功能审查", ++ordinal);
            StringBuilder detail = new StringBuilder("# ").append(text(batches.input(row).objective())).append("\n\n[返回总结](")
                    .append(TemplateReportNames.link(path, names.main())).append(")\n\n");
            links.append("- [").append(text(batches.input(row).objective())).append("](").append(TemplateReportNames.link(names.main(), path)).append(")\n");
            for (var item : analysis.coverage()) detail.append("## 单元 ").append(text(item.unitId())).append("\n\n").append(text(item.conclusion()))
                    .append("\n\n").append(references(item.evidence())).append("\n").append(String.join("\n", item.limitations().stream().map(SnapshotReviewReports::text).toList())).append("\n\n");
            for (var decision : review.decisions()) {
                var finding = analysis.findings().stream().filter(f -> f.key().equals(decision.findingKey())).findFirst().orElseThrow();
                String body = "### " + text(finding.title()) + "\n\n级别：" + finding.severity() + "；复核：" + verdict(decision.verdict())
                        + "；归因：" + attribution(finding.attribution()) + "\n\n触发条件：" + text(finding.trigger()) + "\n\n错误行为：" + text(finding.behavior())
                        + "\n\n建议：" + text(finding.recommendation()) + "\n\n复核依据：" + text(decision.reason()) + "\n\n"
                        + references(finding.evidence()) + references(decision.evidence())
                        + (decision.duplicateOf() == null ? "" : "\n合并目标（复核来源 / 问题编号）：" + text(decision.duplicateOf()) + "\n") + "\n";
                body += duplicateSources.getOrDefault(reviewIds.get(row.id()) + "/" + finding.key(), new StringBuilder());
                detail.append(body);
                if (decision.verdict() == Verdict.SUPPORTED) { supported.append(body); found++; }
                if (decision.verdict() == Verdict.UNDETERMINED) { uncertain.append(body); pending++; }
            }
            detail.append("## 独立复核与局限\n\n").append(text(review.conclusion())).append("\n\n")
                    .append(references(review.evidence())).append("\n");
            var limitations = new ArrayList<>(analysis.limitations()); limitations.addAll(review.limitations());
            analysis.coverage().forEach(c -> c.limitations().forEach(l -> limitations.add(c.unitId() + "：" + l)));
            limitations.forEach(l -> detail.append("- ").append(text(l)).append("\n"));
            if (!limitations.isEmpty()) uncertain.append("### ").append(text(batches.input(row).objective())).append("\n\n")
                    .append(String.join("\n", limitations.stream().map(l -> "- " + text(l)).toList())).append("\n\n");
            documents.add(new TemplateReportCompiler.Document(path, detail.toString()));
        }
        String findingsPath = names.child("当前问题", 1), pendingPath = names.child("待确认与局限", 1), coveragePath = names.child("覆盖清单", 1);
        documents.add(new TemplateReportCompiler.Document(findingsPath, "# 当前版本问题\n\n" + (found == 0 ? "在已覆盖范围内未发现经独立复核支持的问题；不等于证明无缺陷。\n" : supported)));
        documents.add(new TemplateReportCompiler.Document(pendingPath, "# 待确认项与证据局限\n\n" + uncertain));
        documents.add(new TemplateReportCompiler.Document(coveragePath, "# 审查范围与功能归属\n\n" + coverage
                + "\n读取记录与处理记录不证明语义无遗漏；测试源码仅作为证据，本轮未执行目标项目测试。\n"));
        String summary = "# 代码审查报告\n\n## 审查版本与范围\n\n项目：" + text(bundle.projectName())
                + "\n\n来源 SHA：" + snapshot.sourceSha() + "\n\n基线 SHA：" + Objects.toString(snapshot.baselineSha(), "无（全面审查）")
                + "\n\n目标 SHA：" + snapshot.targetSha() + "\n\n实际采集时间：" + snapshot.capturedAt()
                + "\n\n日期范围：" + (snapshot.baselineSha() == null ? "不适用" : start + " 00:00 至 " + end + " 24:00，北京时间")
                + "\n\n版本依据：" + (snapshot.baselineSha() == null ? "冻结所选分支 tip" : "当前分支第一父链的 committer 时间估算，不证明历史部署状态；结束边界尚未到达时仅覆盖采集时已取得的历史")
                + (snapshot.nonMonotonic() ? "\n\n发现非单调提交时间，已完整遍历并按拓扑顺序解析边界。" : "")
                + "\n\n## 结论\n\n" + (snapshot.noChanges() ? "基线与目标没有最终代码差异，未调用分析模型。" : "复核支持问题 " + found + " 项；待确认问题 " + pending + " 项；分析与关系检查 " + sources.size() + " 批。")
                + "\n\n本轮为静态审查，未执行目标项目构建、测试或脚本；完成不代表证明版本没有缺陷。仅使用文本和可识别的结构提示，不建立通用跨语言调用图；未知语言按文本检查并保留语义局限。\n\n## 详细报告\n\n"
                + link(names.main(), findingsPath, "当前问题") + link(names.main(), pendingPath, "待确认与局限") + link(names.main(), coveragePath, "覆盖清单")
                + "\n## 功能与衔接审查\n\n" + links;
        documents.addFirst(new TemplateReportCompiler.Document(names.main(), summary));
        artifacts.publishCompiled(attempt, new TemplateReportCompiler.Result(List.copyOf(documents), List.of()), bundle);
    }
    private static String references(List<Reference> refs) {
        StringBuilder out = new StringBuilder();
        for (var r : refs) out.append("- ").append(text(r.path())).append("：").append(r.startLine()).append("–").append(r.endLine())
                .append(" 行；版本 ").append(r.version()).append("；blob ").append(r.blob()).append("\n\n    ")
                .append(text(r.quote()).replace("\n", "\n    ")).append("\n\n");
        return out.toString();
    }
    private static String verdict(Verdict value) { return switch(value) { case SUPPORTED -> "复核支持"; case UNDETERMINED -> "待确认"; case DISMISSED -> "不成立"; case DUPLICATE -> "重复问题"; }; }
    private static String attribution(Attribution value) { return switch(value) { case CHANGE_RELATED -> "本次变化相关"; case EXISTING -> "附带发现的存量问题"; case UNDETERMINED -> "引入归因未确定"; }; }
    private static String date(String instant) { return Instant.parse(instant).atZone(TemplateDateRange.ZONE).toLocalDate().toString(); }
    private static String text(String value) { return TemplateReportCompiler.text(value); }
    private static String link(String from, String to, String label) { return "- [" + label + "](" + TemplateReportNames.link(from, to) + ")\n"; }
}
