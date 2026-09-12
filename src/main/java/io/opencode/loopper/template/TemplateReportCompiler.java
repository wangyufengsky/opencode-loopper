package io.opencode.loopper.template;

import io.opencode.loopper.template.TemplateAnalysis.*;
import io.opencode.loopper.template.TemplateContributionFacts.Person;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Validates evidence and fills a frozen report shell; models never choose headings, tables or score formulas. */
public final class TemplateReportCompiler {
    private TemplateReportCompiler() { }

    public static Result compile(TemplateTaskDefinition definition, String project, TemplateGitEvidence evidence, Accepted candidate) {
        return compile(definition, project, evidence, candidate, TemplateReportLayout.freeze());
    }

    public static Result compile(TemplateTaskDefinition definition, String project, TemplateGitEvidence evidence,
                                  Accepted candidate, TemplateReportLayout.Frozen layout) {
        return compile(definition, project, evidence, candidate, layout, 1);
    }

    public static Result compile(TemplateTaskDefinition definition, String project, TemplateGitEvidence evidence,
                                  Accepted candidate, TemplateReportLayout.Frozen layout, long sequence) {
        // Missing layout identifies a previously frozen V1 task, not a request to use current resources.
        if (layout == null) {
            var previous = LegacyTemplateReportCompiler.compile(definition, project, evidence, candidate);
            return new Result(previous.documents().stream().map(doc -> new Document(doc.path(), doc.markdown())).toList(), previous.ranking());
        }
        List<Unit> units = TemplateAnalysisPartitioner.units(evidence);
        TemplateAnalysisValidation.batch(units, new BatchCandidate(candidate.reviews()));
        Map<String, UnitReview> reviews = candidate.reviews().stream().collect(Collectors.toMap(UnitReview::unitId, Function.identity()));
        var names = TemplateReportNames.of(definition, project, evidence, sequence);
        if (definition == TemplateTaskDefinition.CODE_REVIEW) {
            if (layout.hierarchical()) return HierarchicalReportCompiler.review(project, evidence, units, reviews, layout, names);
            String markdown = layout.render("review", Map.of("scope", scope(project, evidence, units.size()),
                    "summary", reviewSummary(evidence, units, reviews), "findings", findings(units, reviews),
                    "commits", commits(evidence, units, reviews), "limitations", limitations(evidence, units, reviews)));
            return new Result(List.of(new Document("code-review.md", markdown)), List.of());
        }
        return contributions(project, evidence, units, reviews, candidate.contributors(), layout, names);
    }

    private static Result contributions(String project, TemplateGitEvidence evidence, List<Unit> units,
                                          Map<String, UnitReview> reviews, List<ContributorCandidate> candidates, TemplateReportLayout.Frozen layout, TemplateReportNames names) {
        List<Person> people = TemplateContributionFacts.people(evidence);
        Map<String, Person> byPerson = people.stream().collect(Collectors.toMap(person -> person.author().identity(), Function.identity()));
        Map<String, ContributorCandidate> assessments = new LinkedHashMap<>();
        for (ContributorCandidate candidate : candidates) {
            if (candidate == null || assessments.putIfAbsent(candidate.identity(), candidate) != null)
                throw new IllegalArgumentException("个人报告身份重复");
        }
        if (!byPerson.keySet().containsAll(assessments.keySet())) throw new IllegalArgumentException("个人报告包含未出现在 Git 范围内的贡献者");
        List<ContributionScore.Input> inputs = new ArrayList<>();
        for (Person person : people) {
            var candidate = assessment(person, assessments.get(person.author().identity()));
            assessments.put(person.author().identity(), candidate);
            inputs.add(new ContributionScore.Input(person.author().identity(), person.author().name(), person.author().robot(),
                    person.effectiveLines(), candidate.value(), candidate.difficulty(), candidate.quality(), candidate.maintenance()));
        }
        List<ContributionScore.Ranked> rankings = ContributionScore.rank(inputs);
        if (layout.hierarchical()) return HierarchicalReportCompiler.contributions(project, evidence, units, reviews,
                people, assessments, rankings, layout, names);
        List<Document> documents = new ArrayList<>();
        StringBuilder ranking = new StringBuilder("| 排名 | 贡献者 | 提交数 | 原始变更行 | 有效变更行 | 数量 /30 | 价值 /25 | 难度 /15 | 质量 /20 | 维护 /10 | 总分 /100 |\n")
                .append("| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        StringBuilder reasons = new StringBuilder();
        for (var score : rankings) {
            Person person = byPerson.get(score.identity());
            var candidate = assessments.get(score.identity());
            ranking.append("| ").append(rank(score)).append(" | [").append(text(person.author().name()))
                    .append("](contributors/").append(score.identity()).append(".md) | ").append(person.commits().size()).append(" | ")
                    .append(number(person.rawLines())).append(" | ").append(number(person.effectiveLines())).append(" | ")
                    .append(score.quantity()).append(" | ").append(score.value()).append(" | ").append(score.difficulty()).append(" | ")
                    .append(score.quality()).append(" | ").append(score.maintenance()).append(" | **").append(score.total()).append("** |\n");
            reasons.append("### ").append(text(person.author().name())).append(" · ").append(text(person.author().email()))
                    .append("\n\n").append(text(candidate.summary())).append("\n\n").append(scoreTable(person, score, candidate)).append("\n");
            var personal = subset(evidence, person);
            List<Unit> ownUnits = units.stream().filter(unit -> person.commits().contains(unit.commitSha())).toList();
            String markdown = layout.render("personal", Map.of("contributor", text(person.author().name()),
                    "scope", scope(project, personal, ownUnits.size()), "summary", personalSummary(person, score, candidate),
                    "commits", commits(personal, ownUnits, reviews), "findings", findings(ownUnits, reviews),
                    "assessment", scoreTable(person, score, candidate), "rubric", rubric(),
                    "limitations", limitations(personal, ownUnits, reviews)));
            documents.add(new Document("contributors/" + score.identity() + ".md", markdown));
        }
        if (people.isEmpty()) {
            ranking.append("\n所选范围内没有 Git 提交，不生成贡献者排名和个人报告。\n");
            reasons.append("无贡献者评分记录。\n");
        }
        String summary = "范围内提交：" + evidence.commits().size() + "；贡献者：" + people.size()
                + "（其中机器人 " + people.stream().filter(person -> person.author().robot()).count() + "）。\n\n"
                + "原始变更行：" + number(people.stream().mapToDouble(Person::rawLines).sum()) + "；有效变更行："
                + number(people.stream().mapToDouble(Person::effectiveLines).sum()) + "。\n\n" + reviewSummary(evidence, units, reviews);
        String markdown = layout.render("total", Map.of("scope", scope(project, evidence, units.size()), "summary", summary,
                "ranking", ranking.toString(), "assessments", reasons.toString(), "commits", commits(evidence, units, reviews),
                "rubric", rubric(), "limitations", limitations(evidence, units, reviews)));
        documents.addFirst(new Document("contribution-report.md", markdown));
        return new Result(List.copyOf(documents), rankings);
    }

    private static ContributorCandidate assessment(Person person, ContributorCandidate candidate) {
        if (person.effectiveLines() > 0 && !person.author().robot())
            return TemplateAnalysisValidation.contributor(person.author().identity(), person.evidenceIds(), candidate);
        var zero = new ContributionScore.Assessment(0, person.author().robot() ? "显式机器人身份，不参与个人排名" : "无可计分的有效文本变更",
                List.of(person.commits().getFirst()));
        return new ContributorCandidate(person.author().identity(), zero.reason(), zero, zero, zero, zero);
    }

    static TemplateGitEvidence subset(TemplateGitEvidence evidence, Person person) {
        return new TemplateGitEvidence(evidence.version(), evidence.branchId(), evidence.head(), evidence.startDate(), evidence.endDate(),
                evidence.timezone(), evidence.mailmapHash(), evidence.commits().stream().filter(commit -> person.commits().contains(commit.sha())).toList());
    }

    static String scope(String project, TemplateGitEvidence evidence, int units) {
        return "| 项目 | 内容 |\n| --- | --- |\n| 项目名称 | " + text(project) + " |\n| 分支来源 | " + text(evidence.branchId())
                + " |\n| 冻结提交 | " + text(evidence.head()) + " |\n| 日期范围 | " + evidence.startDate() + " 至 " + evidence.endDate()
                + "，含起止日期 |\n| 时区与时间口径 | 北京时间（Asia/Shanghai）；committer 时间 |\n| 提交覆盖 | "
                + evidence.commits().size() + " / " + evidence.commits().size() + " |\n| 证据片段覆盖 | " + units + " / " + units
                + " |\n| 身份映射快照 | " + (evidence.mailmapHash() == null ? "无 .mailmap" : text(evidence.mailmapHash())) + " |\n";
    }

    static String reviewSummary(TemplateGitEvidence evidence, List<Unit> units, Map<String, UnitReview> reviews) {
        StringBuilder output = new StringBuilder("| 严重 | 高 | 中 | 低 | 合计 |\n| ---: | ---: | ---: | ---: | ---: |\n|");
        for (Severity severity : Severity.values()) output.append(" ").append(units.stream()
                .flatMap(unit -> reviews.get(unit.id()).findings().stream()).filter(finding -> finding.severity() == severity).count()).append(" |");
        long count = units.stream().mapToLong(unit -> reviews.get(unit.id()).findings().size()).sum();
        output.append(" ").append(count).append(" |\n\n");
        if (evidence.commits().isEmpty()) output.append("所选范围内没有 Git 提交，本报告不包含代码审查结论。");
        else if (count == 0) output.append("本次审查未发现有充分证据的问题；这不等同于证明代码没有缺陷。");
        else output.append("发现问题：").append(count).append(" 项。请依据问题清单核对与处理；问题数量不代表报告执行失败。");
        return output.toString();
    }

    static String findings(List<Unit> units, Map<String, UnitReview> reviews) {
        return findings(units, reviews, units);
    }

    static String findings(List<Unit> selected, Map<String, UnitReview> reviews, List<Unit> reportUnits) {
        record Located(Unit unit, Finding finding) { }
        var included = selected.stream().map(Unit::id).collect(Collectors.toSet());
        var findings = reportUnits.stream().flatMap(unit -> reviews.get(unit.id()).findings().stream().map(finding -> new Located(unit, finding)))
                .sorted(Comparator.comparing((Located value) -> value.finding().severity()).thenComparing(value -> value.unit().commitSha())
                        .thenComparing(value -> value.unit().path()).thenComparingInt(value -> value.finding().line())
                        .thenComparing(value -> value.finding().title())).toList();
        StringBuilder output = new StringBuilder("| 编号 | 级别 | 提交 | 文件与位置 | 问题 | 影响与依据 | 改进建议 |\n")
                .append("| --- | --- | --- | --- | --- | --- | --- |\n");
        boolean found = false;
        for (int i = 0; i < findings.size(); i++) {
            var located = findings.get(i);
            if (!included.contains(located.unit().id())) continue;
            found = true;
            var finding = located.finding();
            output.append("| F").append(String.format(java.util.Locale.ROOT, "%03d", i + 1)).append(" | ")
                    .append(severity(finding.severity())).append(" | ").append(text(located.unit().commitSha())).append(" | ")
                    .append(text(located.unit().path())).append(" · ").append(finding.side() == Side.AFTER ? "变更后" : "变更前")
                    .append("第 ").append(finding.line()).append(" 行 | ").append(text(finding.title())).append(" | ")
                    .append(text(finding.detail())).append(" | ").append(text(finding.recommendation())).append(" |\n");
        }
        if (!found) output.append("\n无已确认的问题记录。\n");
        return output.toString();
    }

    static String commits(TemplateGitEvidence evidence, List<Unit> units, Map<String, UnitReview> reviews) {
        if (evidence.commits().isEmpty()) return "所选范围内没有 Git 提交。\n";
        StringBuilder output = new StringBuilder();
        for (var commit : evidence.commits()) {
            List<Unit> own = units.stream().filter(unit -> unit.commitSha().equals(commit.sha())).toList();
            output.append("### ").append(text(commit.sha())).append("\n\n提交说明：").append(text(commit.message()))
                    .append("\n\n提交时间：").append(java.time.Instant.parse(commit.committedAt()).atZone(TemplateDateRange.ZONE)
                            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("（北京时间）")
                    .append("\n\n贡献者：").append(commit.contributors().stream().map(author -> text(author.name()) + "（" + text(author.email()) + "）")
                            .collect(Collectors.joining("、"))).append("\n\n")
                    .append("| 文件 | 新增行 | 删除行 | 有效变更行 | 计量说明 | 证据 |\n| --- | ---: | ---: | ---: | --- | --- |\n");
            for (var change : commit.changes()) output.append("| ").append(text(change.path())).append(" | ").append(change.additions())
                    .append(" | ").append(change.deletions()).append(" | ").append(change.effectiveLines()).append(" | ")
                    .append(exclusion(change.exclusionReason())).append(" | ").append(text(change.evidenceId())).append(" |\n");
            if (commit.changes().isEmpty()) output.append("\n无独有文件变更（空提交或无独有解决变更的合并）。\n");
            for (Unit unit : own) output.append("\n分析：").append(text(reviews.get(unit.id()).summary())).append("\n");
            output.append("\n").append(findings(own, reviews, units)).append("\n");
        }
        return output.toString();
    }

    static String personalSummary(Person person, ContributionScore.Ranked score, ContributorCandidate candidate) {
        return "贡献者：" + text(person.author().name()) + " · " + text(person.author().email()) + "\n\n范围内提交：" + person.commits().size()
                + "；原始变更行：" + number(person.rawLines()) + "；有效变更行：" + number(person.effectiveLines())
                + "。\n\n总分：**" + score.total() + "**；排名：" + rank(score) + "。\n\n" + text(candidate.summary());
    }

    static String scoreTable(Person person, ContributionScore.Ranked score, ContributorCandidate candidate) {
        StringBuilder output = new StringBuilder("| 维度 | 等级 | 权重 | 得分 | 依据 |\n| --- | --- | ---: | ---: | --- |\n")
                .append("| 有效变更数量 | 程序计算 | 30 | ").append(score.quantity()).append(" | 有效变更行 L = ")
                .append(number(person.effectiveLines())).append("；按本次项目人类贡献者最大值归一化 |\n");
        List<ContributionScore.Assessment> assessments = List.of(candidate.value(), candidate.difficulty(), candidate.quality(), candidate.maintenance());
        List<BigDecimal> points = List.of(score.value(), score.difficulty(), score.quality(), score.maintenance());
        for (int i = 0; i < assessments.size(); i++) {
            var grade = assessments.get(i);
            var dimension = ContributionScore.DIMENSIONS.get(i);
            output.append("| ").append(dimension.title()).append(" | ").append(grade.level()).append(" /4 | ").append(dimension.weight())
                    .append(" | ").append(points.get(i)).append(" | ").append(text(grade.reason())).append("；证据：")
                    .append(grade.evidenceIds().stream().map(TemplateReportCompiler::text).collect(Collectors.joining("、"))).append(" |\n");
        }
        return output.append("| **总分** | — | **100** | **").append(score.total()).append("** | 排名：").append(rank(score)).append(" |\n").toString();
    }

    static String rubric() {
        StringBuilder output = new StringBuilder("版本：").append(ContributionScore.VERSION).append("\n\n公式：")
                .append(ContributionScore.FORMULA).append("。\n\nL 为共同作者等分归属后的有效增删行，Lmax 为本次项目人类贡献者的最大值；")
                .append("全为零时数量分为零。无有效贡献记零；机器人单列，不参与个人排名。保留两位小数，同分排名为 1、1、3。\n\n")
                .append("| 维度 | 权重 | 等级 0 | 等级 1 | 等级 2 | 等级 3 | 等级 4 |\n| --- | ---: | --- | --- | --- | --- | --- |\n")
                .append("| 有效变更数量 | 30 | 按公式计算 | 按公式计算 | 按公式计算 | 按公式计算 | 按公式计算 |\n");
        for (var dimension : ContributionScore.DIMENSIONS) output.append("| ").append(dimension.title()).append(" | ").append(dimension.weight())
                .append(" | ").append(dimension.levels().stream().map(TemplateReportCompiler::text).collect(Collectors.joining(" | "))).append(" |\n");
        return output.append("\n其他维度按完全有证据支持的最高等级评分。该标准是本项目内置观察标准，权重不代表行业统一标准，也不衡量个人全部工作价值。\n").toString();
    }

    static String limitations(TemplateGitEvidence evidence, List<Unit> units, Map<String, UnitReview> reviews) {
        StringBuilder output = new StringBuilder("- 仅覆盖所选分支可达、且 committer 时间位于北京时间日期范围内的提交。\n")
                .append("- 身份按冻结 .mailmap 与邮箱归并；同名不同邮箱分开；共同作者等分计量。\n")
                .append("- 合并仅计独有解决变更；生成文件、依赖、缓存与重复补丁不计有效变更行；二进制文件无文本行计量。\n")
                .append("- 报告只评价可观察的 Git 贡献，不推测工时、未记录的评审、业务收益或没有提交的人员。\n")
                .append("- 测试文件存在不代表测试已运行通过。本流程未执行项目构建或测试。\n");
        long withheld = evidence.commits().stream().flatMap(commit -> commit.changes().stream())
                .filter(change -> "SENSITIVE_CONTENT_WITHHELD".equals(change.exclusionReason())).count();
        if (withheld > 0) output.append("- 敏感文件变更记录：").append(withheld).append("；仅保留元数据，正文未发送给模型，内容未审查。\n");
        units.stream().flatMap(unit -> reviews.get(unit.id()).limitations().stream()).distinct().sorted()
                .forEach(value -> output.append("- ").append(text(value)).append("\n"));
        return output.toString();
    }

    static String rank(ContributionScore.Ranked score) { return score.rank() == null ? "机器人（不参与排名）" : score.rank().toString(); }
    static String severity(Severity value) { return switch (value) { case CRITICAL -> "严重"; case HIGH -> "高"; case MEDIUM -> "中"; case LOW -> "低"; }; }
    private static String exclusion(String reason) {
        if (reason == null) return "计入有效变更";
        return switch (reason) {
            case "DUPLICATE_PATCH" -> "重复补丁，数量只计一次";
            case "BINARY_NO_LINE_METRIC" -> "二进制文件，无文本行计量";
            case "SENSITIVE_CONTENT_WITHHELD" -> "敏感文件，正文未审查";
            case "VENDORED_OR_CACHE", "DECLARED_LINGUIST_VENDORED" -> "第三方依赖或缓存";
            default -> "有生成标记，不计有效变更行";
        };
    }
    static String number(double value) { return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString(); }
    static String text(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\\", "\\\\")
                .replace("`", "\\`").replace("*", "\\*").replace("_", "\\_").replace("[", "\\[").replace("]", "\\]")
                .replace("|", "\\|").replace("#", "\\#").replace("\r\n", "\n").replace("\r", "\n").replace("\n", "<br>");
    }
    public record Document(String path, String markdown) { }
    public record Result(List<Document> documents, List<ContributionScore.Ranked> ranking) { }
}
