package io.opencode.loopper.template;

import io.opencode.loopper.template.SnapshotReview.*;
import java.util.*;
import java.util.function.Function;

/** Common immutable report rendering for original tasks and composable workflow nodes. */
public final class SnapshotReviewReportCompiler {
    private SnapshotReviewReportCompiler(){ }
    public record AnalysisResult(String id,Input input,Analysis analysis){ }
    public record ReviewResult(String id,String analysisId,Review review){ }
    public static final class Invalid extends IllegalArgumentException {
        private final String code;
        public Invalid(String code,String message){super(message);this.code=code;}
        public String code(){return code;}
    }
    public static TemplateReportCompiler.Result compile(String projectName,TemplateReportNames names,String start,String end,
            Snapshot snapshot,Plan plan,List<AnalysisResult> analyses,List<ReviewResult> reviews,boolean lightweight,
            boolean requireReviews,String reuseSummary,Function<List<Reference>,String> authors) {
        var documents = new ArrayList<TemplateReportCompiler.Document>();
        Map<String, Review> judgments = new LinkedHashMap<>();
        reviews.forEach(r -> judgments.put(r.analysisId(), r.review()));
        var sources = new ArrayList<>(analyses);
        StringBuilder supported = new StringBuilder(), uncertain = new StringBuilder();
        var coverage=coverage(snapshot,plan,lightweight,uncertain);
        Map<String, String> reviewIds = new HashMap<>();
        reviews.forEach(r -> reviewIds.put(r.analysisId(), r.id()));
        for (var row : sources) if (requireReviews && !judgments.containsKey(row.id()) && (!lightweight || !row.analysis().findings().isEmpty()))
            throw new Invalid("SNAPSHOT_REVIEW_MISSING","缺少独立复核，不能生成完整报告");
        var duplicateSources=duplicateSources(sources,judgments,reviewIds,authors);
        int found = 0, pending = 0, ordinal = 0;
        StringBuilder links = new StringBuilder();
        for (var row : sources) {
            var analysis = row.analysis(); var review = judgments.get(row.id()); var input = row.input();
            String path = names.child("功能审查", ++ordinal);
            StringBuilder detail = new StringBuilder("# ").append(text(row.input().objective())).append("\n\n[返回总结](")
                    .append(TemplateReportNames.link(path, names.main())).append(")\n\n");
            links.append("- [").append(text(row.input().objective())).append("](").append(TemplateReportNames.link(names.main(), path)).append(")\n");
            for (var item : analysis.coverage()) {
                detail.append("## 单元 ").append(text(item.unitId())).append("\n\n").append(text(item.conclusion()))
                        .append("\n\n").append(references(item.evidence())).append("\n").append(String.join("\n", item.limitations().stream().map(SnapshotReviewReportCompiler::text).toList())).append("\n\n");
                if (item.evidence().isEmpty() && input.compact()) {
                    var unit = input.units().stream().filter(u -> u.id().equals(item.unitId())).findFirst().orElseThrow();
                    for (var ref : unit.initialEvidence()) detail.append("初始证据：").append(text(ref.path())).append("：").append(ref.startLine()).append("–")
                            .append(ref.endLine()).append("；版本 ").append(ref.version()).append("；blob ").append(ref.blob()).append("\n\n");
                }
            }
            for (var decision : review == null ? List.<Decision>of() : review.decisions()) {
                var finding = analysis.findings().stream().filter(f -> f.key().equals(decision.findingKey())).findFirst().orElseThrow();
                String body = "### " + text(finding.title()) + "\n\n级别：" + finding.severity() + "；复核：" + verdict(decision.verdict())
                        + "；归因：" + attribution(finding.attribution()) + "\n\n触发条件：" + text(finding.trigger()) + "\n\n错误行为：" + text(finding.behavior())
                        + "\n\n建议：" + text(finding.recommendation()) + "\n\n复核依据：" + text(decision.reason()) + "\n\n"
                        + references(finding.evidence()) + references(decision.evidence()) + authorReferences(authors, finding, decision)
                        + (decision.duplicateOf() == null ? "" : "\n合并目标（复核来源 / 问题编号）：" + text(decision.duplicateOf()) + "\n") + "\n";
                body += duplicateSources.getOrDefault(reviewIds.get(row.id()) + "/" + finding.key(), new StringBuilder());
                detail.append(body);
                if (decision.verdict() == Verdict.SUPPORTED) { supported.append(body); found++; }
                if (decision.verdict() == Verdict.UNDETERMINED) { uncertain.append(body); pending++; }
            }
            if (review == null && !analysis.findings().isEmpty()) for (var finding : analysis.findings()) {
                String candidate = "### " + text(finding.title()) + "\n\n仅候选，未经独立复核；级别：" + finding.severity()
                    + "；归因：" + attribution(finding.attribution()) + "\n\n触发条件：" + text(finding.trigger())
                    + "\n\n错误行为：" + text(finding.behavior()) + "\n\n建议：" + text(finding.recommendation()) + "\n\n"
                    + references(finding.evidence()) + authors.apply(finding.evidence());
                detail.append(candidate); uncertain.append(candidate); pending++;
            }
            detail.append("## 独立复核与局限\n\n").append(review == null ? (analysis.findings().isEmpty() ? "本批未发现候选问题；按轻量策略未进行独立复核，不代表证明无缺陷。" : "本批候选问题未经独立复核；按用户选择保留为候选，不认定为已确认缺陷。") : text(review.conclusion())).append("\n\n")
                    .append(review == null ? "" : references(review.evidence())).append("\n");
            var limitations = new ArrayList<>(analysis.limitations()); if (review != null) limitations.addAll(review.limitations());
            analysis.coverage().forEach(c -> c.limitations().forEach(l -> limitations.add(c.unitId() + "：" + l)));
            limitations.forEach(l -> detail.append("- ").append(text(l)).append("\n"));
            if (!limitations.isEmpty()) uncertain.append("### ").append(text(row.input().objective())).append("\n\n")
                    .append(String.join("\n", limitations.stream().map(l -> "- " + text(l)).toList())).append("\n\n");
            documents.add(new TemplateReportCompiler.Document(path, detail.toString()));
        }
        String findingsPath = names.child("当前问题", 1), pendingPath = names.child("待确认与局限", 1), coveragePath = names.child("覆盖清单", 1);
        documents.add(new TemplateReportCompiler.Document(findingsPath, "# 当前版本问题\n\n" + (found == 0 ? "在已覆盖范围内未发现经独立复核支持的问题；不等于证明无缺陷。\n" : supported)));
        documents.add(new TemplateReportCompiler.Document(pendingPath, "# 待确认项与证据局限\n\n" + uncertain));
        documents.add(new TemplateReportCompiler.Document(coveragePath, "# 审查范围与功能归属\n\n" + coverage
                + "\n读取记录与处理记录不证明语义无遗漏；测试源码仅作为证据，本轮未执行目标项目测试。\n"));
        String summary = "# 代码审查报告\n\n## 审查版本与范围\n\n项目：" + text(projectName)
                + "\n\n来源 SHA：" + snapshot.sourceSha() + "\n\n基线 SHA：" + Objects.toString(snapshot.baselineSha(), "无（全面审查）")
                + "\n\n目标 SHA：" + snapshot.targetSha() + "\n\n实际采集时间：" + snapshot.capturedAt()
                + "\n\n日期范围：" + (snapshot.baselineSha() == null ? "不适用" : start + " 00:00 至 " + end + " 24:00，北京时间")
                + "\n\n版本依据：" + (snapshot.baselineSha() == null ? "冻结所选分支 tip" : "当前分支第一父链的 committer 时间估算，不证明历史部署状态；结束边界尚未到达时仅覆盖采集时已取得的历史")
                + (snapshot.nonMonotonic() ? "\n\n发现非单调提交时间，已完整遍历并按拓扑顺序解析边界。" : "")
                + (lightweight ? (requireReviews ? "\n\n## 审查策略\n\n轻量审查：程序按容量分批，一轮代码分析，仅对候选问题独立复核；不追加关系或补充批次。无问题结论未经独立复核。" : "\n\n## 审查策略\n\n程序按容量分批，一轮代码分析；用户选择不要求独立复核。报告保留已配置节点的实际复核情况，未复核问题仅为候选，不认定为已确认缺陷。无问题结论未经独立复核。") : "")
                + "\n\n## 结论\n\n" + (snapshot.noChanges() ? "基线与目标没有最终代码差异，未调用分析模型。" : "复核支持问题 " + found + " 项；待确认问题 " + pending + " 项；分析" + (lightweight ? "" : "与关系检查") + " " + sources.size() + (lightweight ? " 批；独立复核 " + reviews.size() + " 批。" : " 批。"))
                + reuseSummary + "\n\n本轮为静态审查，未执行目标项目构建、测试或脚本；完成不代表证明版本没有缺陷。仅使用文本和可识别的结构提示，不建立通用跨语言调用图；未知语言按文本检查并保留语义局限。\n\n## 详细报告\n\n"
                + link(names.main(), findingsPath, "当前问题") + link(names.main(), pendingPath, "待确认与局限") + link(names.main(), coveragePath, "覆盖清单")
                + "\n## 功能与衔接审查\n\n" + links;
        documents.addFirst(new TemplateReportCompiler.Document(names.main(), summary));
        return new TemplateReportCompiler.Result(List.copyOf(documents), List.of());
    }
    private static StringBuilder coverage(Snapshot snapshot,Plan plan,boolean lightweight,StringBuilder uncertain) {
        var coverage=new StringBuilder();
        coverage.append("| 单元 | 文件 | 分组归属 / 处理情况 |\n| --- | --- | --- |\n");
        for (var unit : snapshot.units()) {
            var owner = plan.groups().stream().filter(g -> g.unitIds().contains(unit.id())).findFirst().orElse(null);
            if (owner == null && (!lightweight || unit.limitation() == null || !unit.excerpt().isBlank()))
                throw new Invalid("SNAPSHOT_COVERAGE_MISSING","可读代码片段缺少分析归属");
            coverage.append("| ").append(text(unit.id())).append(" | ").append(text(unit.path())).append(" | ").append(owner == null ? "已记录排除，未调用模型" : text(owner.title()))
                    .append(unit.limitation() == null ? "" : "；" + text(unit.limitation())).append(" |\n");
            if (lightweight && unit.limitation() != null) uncertain.append("- ").append(text(unit.path())).append("：").append(text(unit.limitation())).append("\n");
        }
        return coverage;
    }
    private static Map<String,StringBuilder> duplicateSources(List<AnalysisResult> sources,Map<String,Review> judgments,
            Map<String,String> reviewIds,Function<List<Reference>,String> authors) {
        var duplicateSources=new HashMap<String,StringBuilder>();
        for (var r : sources) for (var d : judgments.containsKey(r.id()) ? judgments.get(r.id()).decisions() : List.<Decision>of()) if (d.verdict() == Verdict.DUPLICATE) {
            var f = r.analysis().findings().stream().filter(v -> v.key().equals(d.findingKey())).findFirst().orElseThrow();
            String target = d.duplicateOf().contains("/") ? d.duplicateOf() : reviewIds.get(r.id()) + "/" + d.duplicateOf();
            duplicateSources.computeIfAbsent(target, ignored -> new StringBuilder()).append("\n关联分析来源：")
                    .append(text(r.input().objective())).append("；关联问题：").append(text(f.title())).append("\n\n")
                    .append("合并依据：").append(text(d.reason())).append("\n\n").append(references(f.evidence())).append(references(d.evidence()))
                    .append(authorReferences(authors, f, d));
        }
        return duplicateSources;
    }
    private static String authorReferences(Function<List<Reference>,String> authors,Finding finding,Decision decision) {
        var refs=new ArrayList<>(finding.evidence());refs.addAll(decision.evidence());return authors.apply(refs);
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
    private static String text(String value) { return TemplateReportCompiler.text(value); }
    private static String link(String from, String to, String label) { return "- [" + label + "](" + TemplateReportNames.link(from, to) + ")\n"; }
}
