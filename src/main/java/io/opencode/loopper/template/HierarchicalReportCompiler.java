package io.opencode.loopper.template;

import static io.opencode.loopper.template.TemplateReportCompiler.*;
import io.opencode.loopper.template.TemplateAnalysis.*;
import io.opencode.loopper.template.TemplateContributionFacts.Person;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Renders summary and detail views of the same validated evidence without additional model calls. */
final class HierarchicalReportCompiler {
    private HierarchicalReportCompiler() { }

    static Result review(String project, TemplateGitEvidence evidence, List<Unit> units, Map<String, UnitReview> reviews,
                         TemplateReportLayout.Frozen layout, TemplateReportNames names) {
        List<Document> documents = new ArrayList<>();
        String findingsPath = names.child("问题清单", 1), indexPath = names.child("提交审查目录", 1);
        documents.add(detail(layout, names, findingsPath, "完整问题清单", findings(units, reviews)));
        StringBuilder index = new StringBuilder("| 提交 | 提交说明 | 详细报告 |\n| --- | --- | --- |\n");
        int ordinal = 0;
        for (var commit : evidence.commits()) {
            String path = names.child("提交审查", ++ordinal);
            var single = new TemplateGitEvidence(evidence.version(), evidence.branchId(), evidence.head(), evidence.startDate(),
                    evidence.endDate(), evidence.timezone(), evidence.mailmapHash(), List.of(commit));
            documents.add(detail(layout, names, path, "提交审查 · " + commit.sha(), commits(single, units, reviews)));
            index.append("| ").append(text(commit.sha())).append(" | ").append(text(brief(commit.message())))
                    .append(" | ").append(link("查看详细审查", indexPath, path)).append(" |\n");
        }
        if (evidence.commits().isEmpty()) index.append("\n所选范围内没有 Git 提交。\n");
        documents.add(detail(layout, names, indexPath, "提交审查目录", index.toString()));
        String details = "- " + link("完整问题清单", names.main(), findingsPath) + "\n- "
                + link("逐提交审查目录（" + evidence.commits().size() + " 个提交）", names.main(), indexPath);
        String markdown = layout.render("review", Map.of("scope", scope(project, evidence, units.size()),
                "summary", reviewSummary(evidence, units, reviews), "highlights", highlights(units, reviews),
                "details", details, "limitations", limitations(evidence, units, reviews)));
        documents.addFirst(new Document(names.main(), markdown));
        return new Result(List.copyOf(documents), List.of());
    }

    static Result contributions(String project, TemplateGitEvidence evidence, List<Unit> units, Map<String, UnitReview> reviews,
                                List<Person> people, Map<String, ContributorCandidate> assessments,
                                List<ContributionScore.Ranked> rankings, TemplateReportLayout.Frozen layout, TemplateReportNames names) {
        Map<String, Person> byPerson = people.stream().collect(Collectors.toMap(person -> person.author().identity(), Function.identity()));
        List<Document> documents = new ArrayList<>();
        String scoringPath = names.child("评分标准", 1), findingsPath = names.child("问题清单", 1);
        documents.add(detail(layout, names, scoringPath, "贡献评分标准", rubric()));
        documents.add(detail(layout, names, findingsPath, "项目完整问题清单", findings(units, reviews)));
        StringBuilder ranking = new StringBuilder("| 排名 | 贡献者 | 主要工作 | 提交数 | 有效变更行 | 总分 /100 | 详细报告 |\n")
                .append("| --- | --- | --- | ---: | ---: | ---: | --- |\n");
        int ordinal = 0;
        for (var score : rankings) {
            Person person = byPerson.get(score.identity());
            var candidate = assessments.get(score.identity());
            String path = names.child("个人贡献-" + person.author().name(), ++ordinal);
            ranking.append("| ").append(rank(score)).append(" | ").append(text(person.author().name())).append(" | ")
                    .append(text(brief(candidate.summary()))).append(" | ").append(person.commits().size()).append(" | ")
                    .append(number(person.effectiveLines())).append(" | **").append(score.total()).append("** | ")
                    .append(link("查看个人报告", names.main(), path)).append(" |\n");
            var personal = subset(evidence, person);
            List<Unit> own = units.stream().filter(unit -> person.commits().contains(unit.commitSha())).toList();
            String navigation = navigation(names, path) + " · " + link("评分标准", path, scoringPath);
            String markdown = layout.render("personal", Map.of("contributor", text(person.author().name()), "navigation", navigation,
                    "scope", scope(project, personal, own.size()), "summary", personalSummary(person, score, candidate),
                    "commits", commits(personal, units, reviews), "findings", findings(own, reviews, units),
                    "assessment", scoreTable(person, score, candidate), "limitations", limitations(personal, own, reviews)));
            documents.add(new Document(path, markdown));
        }
        if (people.isEmpty()) ranking.append("\n所选范围内没有 Git 提交，不生成贡献者排名和个人报告。\n");
        String summary = "范围内提交：" + evidence.commits().size() + "；贡献者：" + people.size() + "（其中机器人 "
                + people.stream().filter(person -> person.author().robot()).count() + "）。\n\n原始变更行："
                + number(people.stream().mapToDouble(Person::rawLines).sum()) + "；有效变更行："
                + number(people.stream().mapToDouble(Person::effectiveLines).sum()) + "。\n\n" + reviewSummary(evidence, units, reviews);
        String details = "- 个人工作明细与评分依据：点击上方人员表中的详细报告。\n- "
                + link("完整问题清单", names.main(), findingsPath) + "\n- " + link("完整评分标准与公式", names.main(), scoringPath)
                + "\n\n评分权重：数量 30、价值 25、难度 15、质量 20、维护 10；版本：" + ContributionScore.VERSION + "。";
        documents.addFirst(new Document(names.main(), layout.render("total", Map.of("scope", scope(project, evidence, units.size()),
                "summary", summary, "ranking", ranking.toString(), "highlights", highlights(units, reviews), "details", details,
                "limitations", limitations(evidence, units, reviews)))));
        return new Result(List.copyOf(documents), rankings);
    }

    private static String highlights(List<Unit> units, Map<String, UnitReview> reviews) {
        record Located(Unit unit, Finding finding) { }
        var findings = units.stream().flatMap(unit -> reviews.get(unit.id()).findings().stream().map(f -> new Located(unit, f)))
                .sorted(Comparator.comparing((Located value) -> value.finding().severity()).thenComparing(value -> value.unit().commitSha())
                        .thenComparing(value -> value.unit().path()).thenComparingInt(value -> value.finding().line())
                        .thenComparing(value -> value.finding().title())).toList();
        if (findings.isEmpty()) return "未发现有充分证据的问题；请结合覆盖与局限阅读。";
        StringBuilder output = new StringBuilder("按严重程度优先列出前 10 项；完整证据见详细问题清单。\n\n")
                .append("| 级别 | 位置 | 问题 | 建议 |\n| --- | --- | --- | --- |\n");
        for (var located : findings.stream().limit(10).toList()) output.append("| ").append(severity(located.finding().severity()))
                .append(" | ").append(text(located.unit().path())).append(" · ").append(located.finding().line()).append(" 行 | ")
                .append(text(brief(located.finding().title()))).append(" | ").append(text(brief(located.finding().recommendation()))).append(" |\n");
        return output.toString();
    }

    private static Document detail(TemplateReportLayout.Frozen layout, TemplateReportNames names, String path, String title, String body) {
        return new Document(path, layout.render("detail", Map.of("title", text(title), "navigation", navigation(names, path), "body", body)));
    }
    private static String navigation(TemplateReportNames names, String path) { return link("返回总结报告", path, names.main()); }
    private static String link(String label, String from, String to) { return "[" + text(label) + "](" + TemplateReportNames.link(from, to) + ")"; }
    private static String brief(String value) { return value.codePointCount(0, value.length()) <= 180 ? value : value.substring(0, value.offsetByCodePoints(0, 180)) + "…"; }
}
