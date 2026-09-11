package io.opencode.loopper.template;

import io.opencode.loopper.template.TemplateAnalysis.Accepted;
import io.opencode.loopper.template.TemplateAnalysis.ContributorCandidate;
import io.opencode.loopper.template.TemplateAnalysis.Unit;
import io.opencode.loopper.template.TemplateAnalysis.UnitReview;
import io.opencode.loopper.template.TemplateContributionFacts.Person;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Materializes complete Markdown only after exact coverage, attribution and score validation. */
final class LegacyTemplateReportCompiler {
    private LegacyTemplateReportCompiler() { }

    public static Result compile(TemplateTaskDefinition definition, String projectName,
                                  TemplateGitEvidence evidence, Accepted candidate) {
        List<Unit> units = TemplateAnalysisPartitioner.units(evidence);
        TemplateAnalysisValidation.batch(units, new TemplateAnalysis.BatchCandidate(candidate.reviews()));
        Map<String, UnitReview> reviews = candidate.reviews().stream().collect(Collectors.toMap(UnitReview::unitId, Function.identity()));
        Map<String, List<Unit>> byCommit = units.stream().collect(Collectors.groupingBy(Unit::commitSha));
        String heading = scope(definition.title(), projectName, evidence, units.size());
        if (definition == TemplateTaskDefinition.CODE_REVIEW) {
            return new Result(List.of(new Document("code-review.md", heading + reviewBody(evidence, byCommit, reviews))), List.of());
        }
        return contributions(projectName, heading, evidence, byCommit, reviews, candidate.contributors());
    }

    private static Result contributions(String projectName, String heading, TemplateGitEvidence evidence,
                                          Map<String, List<Unit>> units, Map<String, UnitReview> reviews, List<ContributorCandidate> candidates) {
        List<Person> people = TemplateContributionFacts.people(evidence);
        Map<String, Person> byPerson = people.stream().collect(Collectors.toMap(person -> person.author().identity(), Function.identity()));
        Map<String, TemplateGitEvidence.Commit> commits = evidence.commits().stream().collect(Collectors.toMap(TemplateGitEvidence.Commit::sha, Function.identity()));
        Map<String, ContributorCandidate> assessments = new HashMap<>();
        for (ContributorCandidate candidate : candidates) {
            if (candidate == null || assessments.putIfAbsent(candidate.identity(), candidate) != null) throw new IllegalArgumentException("个人报告身份重复");
        }
        if (!byPerson.keySet().containsAll(assessments.keySet())) {
            throw new IllegalArgumentException("个人报告包含未出现在 Git 范围内的贡献者");
        }
        List<ContributionScore.Input> inputs = new ArrayList<>();
        for (Person person : people) {
            ContributorCandidate candidate = assessment(person, assessments.get(person.author().identity()));
            assessments.put(person.author().identity(), candidate);
            inputs.add(new ContributionScore.Input(person.author().identity(), person.author().name(), person.author().robot(),
                    person.effectiveLines(), candidate.value(), candidate.difficulty(), candidate.quality(), candidate.maintenance()));
        }
        List<ContributionScore.Ranked> rankings = ContributionScore.rank(inputs);
        StringBuilder total = new StringBuilder(heading).append("\n## 贡献排名\n\n");
        total.append("| 排名 | 贡献者 | 原始变更行 | 有效变更行 | 数量 | 价值 | 难度 | 质量 | 维护 | 总分 |\n");
        total.append("| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        List<Document> documents = new ArrayList<>();
        for (var score : rankings) {
            Person person = byPerson.get(score.identity());
            appendRanking(total, person, score);
            String body = personal(person, score, assessments.get(score.identity()), commits, units, reviews);
            documents.add(new Document("contributors/" + score.identity() + ".md",
                    scope("个人贡献周报 · " + score.displayName(), projectName, evidence, reviews.size()) + body + rubric()));
        }
        if (people.isEmpty()) total.append("\n所选范围内没有 Git 提交，不生成贡献者排名和个人报告。\n");
        total.append(rubric()).append("\n## 项目工作与问题明细\n").append(reviewBody(evidence, units, reviews));
        documents.addFirst(new Document("contribution-report.md", total.toString()));
        return new Result(List.copyOf(documents), rankings);
    }

    private static ContributorCandidate assessment(Person person, ContributorCandidate candidate) {
        if (person.effectiveLines() > 0 && !person.author().robot()) {
            return TemplateAnalysisValidation.contributor(person.author().identity(), person.evidenceIds(), candidate);
        }
        var zero = new ContributionScore.Assessment(0, person.author().robot() ? "显式机器人身份，单列且不参与个人排名" : "无可计分的有效文本变更",
                List.of(person.commits().getFirst()));
        return new ContributorCandidate(person.author().identity(), zero.reason(), zero, zero, zero, zero);
    }

    private static void appendRanking(StringBuilder target, Person person, ContributionScore.Ranked score) {
        target.append("| ").append(score.rank() == null ? "机器人" : score.rank()).append(" | [")
                .append(text(person.author().name())).append("](contributors/").append(score.identity()).append(".md) | ")
                .append(number(person.rawLines())).append(" | ").append(number(person.effectiveLines())).append(" | ")
                .append(score.quantity()).append(" | ").append(score.value()).append(" | ").append(score.difficulty()).append(" | ")
                .append(score.quality()).append(" | ").append(score.maintenance()).append(" | **").append(score.total()).append("** |\n");
    }

    private static String personal(Person person, ContributionScore.Ranked score, ContributorCandidate candidate,
                                      Map<String, TemplateGitEvidence.Commit> commits, Map<String, List<Unit>> units, Map<String, UnitReview> reviews) {
        StringBuilder result = new StringBuilder("\n## 贡献概览\n\n");
        result.append("贡献者：").append(text(person.author().name())).append(" · ").append(text(person.author().email()))
                .append("\n\n范围内提交：").append(person.commits().size()).append("；原始变更行：").append(number(person.rawLines()))
                .append("；有效变更行：").append(number(person.effectiveLines())).append("。\n\n总分：**").append(score.total())
                .append("**；排名：").append(score.rank() == null ? "不参与" : score.rank()).append("。\n\n")
                .append(text(candidate.summary())).append("\n\n## 评分依据\n\n");
        List<ContributionScore.Assessment> assessments = List.of(candidate.value(), candidate.difficulty(), candidate.quality(), candidate.maintenance());
        for (int i = 0; i < assessments.size(); i++) {
            var grade = assessments.get(i);
            result.append("- ").append(ContributionScore.DIMENSIONS.get(i).title()).append("：").append(grade.level()).append("/4。")
                    .append(text(grade.reason())).append(" 证据：").append(String.join("、", grade.evidenceIds())).append("。\n");
        }
        result.append("\n## 工作明细\n");
        person.commits().forEach(sha -> appendCommit(result, commits.get(sha), units, reviews));
        return result.toString();
    }

    private static String reviewBody(TemplateGitEvidence evidence, Map<String, List<Unit>> units, Map<String, UnitReview> reviews) {
        StringBuilder result = new StringBuilder("\n## 审查结果\n\n");
        long findings = reviews.values().stream().mapToLong(review -> review.findings().size()).sum();
        result.append("发现问题：").append(findings).append(" 项。问题数量不代表报告执行失败。\n");
        if (evidence.commits().isEmpty()) result.append("\n所选范围内没有 Git 提交。\n");
        else if (findings == 0) result.append("\n本次审查未发现有充分证据的问题；这不等同于证明代码没有缺陷。\n");
        evidence.commits().forEach(commit -> appendCommit(result, commit, units, reviews));
        return result.toString();
    }

    private static void appendCommit(StringBuilder target, TemplateGitEvidence.Commit commit, Map<String, List<Unit>> units,
                                      Map<String, UnitReview> reviews) {
        target.append("\n### ").append(commit.sha()).append("\n\n").append(text(commit.message())).append("\n\n")
                .append("提交时间：").append(java.time.Instant.parse(commit.committedAt()).atZone(TemplateDateRange.ZONE))
                .append("；贡献者：").append(commit.contributors().stream().map(author -> text(author.name()) + "（" + text(author.email()) + "）")
                        .collect(Collectors.joining("、"))).append("。\n");
        for (var change : commit.changes()) {
            target.append("\n文件：").append(text(change.path())).append("；原始 +").append(change.additions()).append(" / -")
                    .append(change.deletions()).append("；有效行：").append(change.effectiveLines()).append("；证据：")
                    .append(change.evidenceId()).append("。\n");
            if (change.exclusionReason() != null) target.append("\n计量说明：").append(exclusion(change.exclusionReason())).append("。\n");
        }
        for (Unit unit : units.getOrDefault(commit.sha(), List.of())) {
            UnitReview review = reviews.get(unit.id());
            target.append("\n").append(text(review.summary())).append("\n");
            for (var finding : review.findings()) {
                target.append("\n- **").append(severity(finding.severity())).append(" · ").append(text(finding.title())).append("**：")
                        .append(text(unit.path())).append("，").append(finding.side() == TemplateAnalysis.Side.AFTER ? "变更后" : "变更前")
                        .append("第 ").append(finding.line()).append(" 行。 ").append(text(finding.detail())).append("\n  建议：")
                        .append(text(finding.recommendation())).append("\n");
            }
            review.limitations().forEach(value -> target.append("\n局限：").append(text(value)).append("\n"));
        }
    }

    private static String scope(String title, String projectName, TemplateGitEvidence evidence, int units) {
        return "# " + text(title) + "\n\n项目：" + text(projectName) + "\n\n分支来源：" + text(evidence.branchId())
                + "\n\n冻结版本：" + evidence.head() + "\n\n范围：" + evidence.startDate() + " 至 " + evidence.endDate()
                + "（北京时间，含起止日期；按 committer 时间）\n\n提交覆盖：" + evidence.commits().size()
                + " / " + evidence.commits().size() + "；证据片段覆盖：" + units + " / " + units + "。\n\n"
                + "身份映射快照：" + (evidence.mailmapHash() == null ? "无 .mailmap" : evidence.mailmapHash())
                + "。共同作者等分计量；合并仅计独有解决变更；二进制文件无文本行计量。报告只评价可观察的 Git 贡献，"
                + "不推测工时、未记录的评审、业务收益或没有提交的人员。测试文件存在不代表测试已运行通过。\n";
    }

    private static String rubric() {
        StringBuilder result = new StringBuilder("\n## 内置评分标准\n\n版本：").append(ContributionScore.VERSION)
                .append("。\n\n公式：").append(ContributionScore.FORMULA).append("。L 为等分归属后的有效增删行，Lmax 为本次人类贡献者最大值；")
                .append("全为零时数量分为零。无有效贡献记零；机器人单列。保留两位小数，同分排名为 1、1、3。\n\n")
                .append("数量占 30 分；其他维度按完全有证据支持的最高等级评分，等级为 0–4：\n\n");
        for (var dimension : ContributionScore.DIMENSIONS) {
            result.append("- **").append(dimension.title()).append("（").append(dimension.weight()).append(" 分）**：");
            for (int i = 0; i < dimension.levels().size(); i++) result.append(i).append("=").append(dimension.levels().get(i)).append(i == 4 ? "。\n" : "；");
        }
        return result.append("\n这是本项目的贡献观察标准；权重不代表行业统一标准，也不衡量个人全部工作价值。\n").toString();
    }

    private static String exclusion(String reason) {
        return switch (reason) {
            case "DUPLICATE_PATCH" -> "相同路径的重复补丁，数量只计一次";
            case "BINARY_NO_LINE_METRIC" -> "二进制文件，保留记录但不折算文本行";
            case "SENSITIVE_CONTENT_WITHHELD" -> "敏感文件仅保留元数据，正文未发送给模型，内容未审查";
            case "VENDORED_OR_CACHE", "DECLARED_LINGUIST_VENDORED" -> "第三方依赖或缓存，不计有效变更行";
            default -> "有生成标记的文件，不计有效变更行";
        };
    }
    private static String severity(TemplateAnalysis.Severity severity) {
        return switch (severity) { case CRITICAL -> "严重"; case HIGH -> "高"; case MEDIUM -> "中"; case LOW -> "低"; };
    }
    private static String number(double value) { return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString(); }
    private static String text(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\\", "\\\\").replace("`", "\\`").replace("*", "\\*").replace("_", "\\_")
                .replace("[", "\\[").replace("]", "\\]").replace("|", "\\|").replace("#", "\\#");
    }
    public record Document(String path, String markdown) { }
    public record Result(List<Document> documents, List<ContributionScore.Ranked> ranking) { }
}
