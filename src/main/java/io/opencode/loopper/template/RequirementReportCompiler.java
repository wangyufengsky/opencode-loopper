package io.opencode.loopper.template;

import java.util.*;
import java.util.stream.Collectors;

/** Server-derived requirement satisfaction; completion of a review is a separate lifecycle fact. */
public final class RequirementReportCompiler {
    private RequirementReportCompiler() { }
    public record Row(DocumentRequirements.Requirement requirement, RequirementCodeAssessment.Item assessment) { }
    public record File(String name, String content) { }
    public record Result(List<File> files, Map<String, Long> conclusions, boolean allRequirementsSatisfied, int findings) { }
    public static Result review(String title, String sha, List<Row> rows,
            List<RequirementCodeAssessment.Finding> findings, List<String> limitations) {
        if (rows.isEmpty() || rows.stream().anyMatch(row -> row.assessment() == null))
            throw new IllegalArgumentException("Requirement report requires a conclusion for every requirement");
        var counts = rows.stream().collect(Collectors.groupingBy(row -> row.assessment().conclusion().name(), TreeMap::new, Collectors.counting()));
        boolean satisfied = rows.stream().allMatch(row -> row.assessment().conclusion() == RequirementCodeAssessment.Conclusion.SATISFIED);
        var files = new ArrayList<File>();
        var main = new StringBuilder("# " + text(title) + "\n\n")
                .append("评审状态：已完成静态分析与独立复核。\n\n")
                .append("需求满足情况：").append(satisfied ? "全部需求均有符合需求的静态证据。" : "存在未完全满足或无法判断的需求，详见逐项矩阵。")
                .append("\n\n冻结提交：`").append(sha).append("`\n\n")
                .append("测试执行：本次未运行构建、测试或项目脚本。测试源码覆盖不代表执行通过。\n\n")
                .append("| 需求 | 功能组 | 结论 | 详情 |\n| --- | --- | --- | --- |\n");
        for (var row : rows) {
            String key = row.requirement().key();
            if (!key.matches("RQ-[1-9][0-9]{0,8}")) throw new IllegalArgumentException("Invalid report requirement key");
            main.append("| ").append(key).append(" ").append(cell(row.requirement().title())).append(" | ")
                    .append(cell(row.requirement().group())).append(" | ").append(label(row.assessment().conclusion()))
                    .append(" | [逐项证据](requirements/").append(key).append(".md) |\n");
            files.add(new File("requirements/" + key + ".md", detail(row)));
        }
        main.append("\n问题明细：").append(findings.size()).append(" 项。\n\n");
        for (int i = 0; i < findings.size(); i++) {
            var finding = findings.get(i); String path = "issues/F-" + (i + 1) + ".md";
            main.append("- [").append(cell(finding.title())).append("](").append(path).append(")（")
                    .append(finding.kind()).append(" / ").append(finding.severity()).append("）\n");
            files.add(new File(path, finding(finding)));
        }
        main.append("\n提取与评审局限：\n\n");
        limitations.stream().distinct().forEach(value -> main.append("- ").append(text(value)).append("\n"));
        main.append("\n段落处理覆盖仅证明已登记处理归属；不等于对语义绝对无遗漏的保证。\n");
        files.addFirst(new File("summary.md", main.toString()));
        return new Result(List.copyOf(files), counts, satisfied, findings.size());
    }
    private static String detail(Row row) {
        var requirement = row.requirement(); var assessment = row.assessment();
        var body = new StringBuilder("# " + requirement.key() + " " + text(requirement.title()) + "\n\n")
                .append(text(requirement.statement())).append("\n\n结论：").append(label(assessment.conclusion()))
                .append("\n\n").append(text(assessment.rationale())).append("\n\n原文来源：\n\n");
        for (var ref : requirement.sources()) body.append("- 文档 ").append(text(ref.fileId())).append("，分段 ")
                .append(ref.section()).append("：").append(text(ref.quote())).append("\n");
        body.append("\n代码证据：\n\n");
        references(body, assessment.evidence());
        body.append("\n测试源码覆盖：").append(text(assessment.testSourceCoverage())).append("\n\n测试执行：本次未执行。\n");
        if (assessment.missingEntryEvidence() != null) body.append("\n必要入口检查：").append(text(assessment.missingEntryEvidence())).append("\n");
        for (var path : assessment.checkedPaths()) body.append("\n已检查路径：").append(text(path)).append("\n");
        for (var issue : requirement.issues()) body.append("\n待澄清：").append(text(issue)).append("\n");
        for (var limitation : assessment.limitations()) body.append("\n证据局限：").append(text(limitation)).append("\n");
        return body.toString();
    }
    private static String finding(RequirementCodeAssessment.Finding finding) {
        var body = new StringBuilder("# " + text(finding.title()) + "\n\n")
                .append("分类：").append(finding.kind()).append("；严重程度：").append(finding.severity())
                .append("\n\n触发条件：").append(text(finding.trigger())).append("\n\n影响：").append(text(finding.impact()))
                .append("\n\n建议：").append(text(finding.recommendation())).append("\n\n关联需求：")
                .append(String.join("、", finding.requirementKeys())).append("\n\n");
        references(body, finding.evidence()); return body.toString();
    }
    private static void references(StringBuilder body, List<RequirementCodeAssessment.CodeReference> references) {
        for (var ref : references) body.append("- ").append(text(ref.path())).append(":").append(ref.startLine()).append("–")
                .append(ref.endLine()).append("；内容身份 ").append(text(ref.blobSha())).append("\n\n")
                .append("    ").append(text(ref.quote()).replace("\n", "\n    ")).append("\n\n");
    }
    public static String label(RequirementCodeAssessment.Conclusion conclusion) {
        return switch (conclusion) {
            case SATISFIED -> "符合需求"; case PARTIAL -> "部分实现"; case INCORRECT -> "实现不符";
            case NOT_IMPLEMENTED -> "未实现"; case UNDETERMINED -> "无法判断";
        };
    }
    private static String cell(String value) { return text(value).replace("|", "\\|").replace("\n", " "); }
    private static String text(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("[", "\\[").replace("]", "\\]");
    }
}
