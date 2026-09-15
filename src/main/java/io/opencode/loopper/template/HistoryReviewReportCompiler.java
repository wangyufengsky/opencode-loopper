package io.opencode.loopper.template;

import static io.opencode.loopper.template.TemplateReportCompiler.*;
import io.opencode.loopper.template.TemplateAnalysis.*;
import io.opencode.loopper.template.TemplateGitEvidence.*;
import java.util.*;
import java.util.stream.Collectors;

/** Commit identities are joined by the server; model findings never assign people. */
final class HistoryReviewReportCompiler {
    private HistoryReviewReportCompiler() { }
    static Result compile(String project, TemplateGitEvidence evidence, List<Unit> units, Map<String, UnitReview> reviews,
                          TemplateReportLayout.Frozen layout, long sequence) {
        var names = new TemplateReportNames("历史提交审查", project, evidence.startDate(), evidence.endDate(), sequence);
        var documents = new ArrayList<Document>();
        var bySha = evidence.commits().stream().collect(Collectors.toMap(Commit::sha, value -> value));
        var paths = new LinkedHashMap<String, String>();
        int ordinal = 0;
        StringBuilder index = new StringBuilder("| 提交 | 作者 | 提交者 | 说明 |\n| --- | --- | --- | --- |\n");
        for (Commit commit : evidence.commits()) {
            String path = names.child("提交审查", ++ordinal);
            paths.put(commit.sha(), path);
            index.append("| ").append(link(names.child("提交目录", 1), path, commit.sha())).append(" | ")
                    .append(person(commit.author())).append(" | ").append(person(commit.committer())).append(" | ")
                    .append(text(commit.message())).append(" |\n");
            var single = new TemplateGitEvidence(evidence.version(), evidence.branchId(), evidence.head(), evidence.startDate(),
                    evidence.endDate(), evidence.timezone(), evidence.mailmapHash(), List.of(commit));
            String body = "作者：" + identity(commit.author()) + "\n\n提交者：" + identity(commit.committer())
                    + "\n\n共同作者：" + (commit.coauthors().isEmpty() ? "无" : commit.coauthors().stream().map(HistoryReviewReportCompiler::identity).collect(Collectors.joining("、")))
                    + "\n\n" + commits(single, units, reviews);
            documents.add(detail(layout, names, path, "提交审查 · " + commit.sha(), body));
        }
        String findingPath = names.child("问题清单", 1);
        String full = findings(units, reviews, bySha, paths, findingPath, Integer.MAX_VALUE);
        documents.add(detail(layout, names, findingPath, "历史问题与提交身份", full));
        String indexPath = names.child("提交目录", 1);
        documents.add(detail(layout, names, indexPath, "提交目录", index.toString()));
        String personPath = names.child("人员索引", 1);
        documents.add(detail(layout, names, personPath, "人员与关联提交", people(evidence, paths, personPath)));
        String details = "- " + link(names.main(), findingPath, "完整历史问题与人员追溯") + "\n- "
                + link(names.main(), indexPath, "提交目录") + "\n- " + link(names.main(), personPath, "人员索引");
        documents.addFirst(new Document(names.main(), layout.render("review", Map.of("scope", scope(project, evidence, units.size()),
                "summary", reviewSummary(evidence, units, reviews).replace("发现问题：", "历史提交中发现问题："),
                "highlights", findings(units, reviews, bySha, paths, names.main(), 10), "details", details,
                "limitations", limitations(evidence, units, reviews) + "\n- 未核实历史问题在当前版本的存续状态。\n"))));
        return new Result(List.copyOf(documents), List.of());
    }
    private static String findings(List<Unit> units, Map<String, UnitReview> reviews, Map<String, Commit> commits,
                                   Map<String, String> paths, String from, int limit) {
        record Located(Unit unit, Finding finding) { }
        var located = units.stream().flatMap(unit -> reviews.get(unit.id()).findings().stream().map(f -> new Located(unit, f)))
                .sorted(Comparator.comparing((Located x) -> x.finding().severity()).thenComparing(x -> x.unit().commitSha())
                        .thenComparing(x -> x.unit().id()).thenComparingInt(x -> x.finding().line())).toList();
        if (located.isEmpty()) return "未发现有充分证据的历史问题。";
        Map<List<String>, List<Located>> grouped = new LinkedHashMap<>();
        for (var item : located) {
            var f = item.finding();
            // Only identical semantic claims are coalesced; never guess that different descriptions share a root cause.
            var key = List.of(item.unit().path(), f.severity().name(), f.title(), f.detail(), f.recommendation());
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(item);
        }
        StringBuilder table = new StringBuilder("| 编号 | 级别 | 所有关联提交 | 所有作者 | 所有提交者 | 所有关联位置 | 问题与依据 | 建议 |\n| --- | --- | --- | --- | --- | --- | --- | --- |\n");
        int i = 0;
        for (var group : grouped.values()) {
            if (i >= limit) break;
            var f = group.getFirst().finding();
            var related = group.stream().map(item -> commits.get(item.unit().commitSha())).distinct().toList();
            table.append("| F").append(String.format(Locale.ROOT, "%03d", ++i)).append(" | ").append(severity(f.severity())).append(" | ")
                    .append(related.stream().map(c -> link(from, paths.get(c.sha()), c.sha())).collect(Collectors.joining("、"))).append(" | ")
                    .append(related.stream().map(c -> person(c.author())).distinct().collect(Collectors.joining("、"))).append(" | ")
                    .append(related.stream().map(c -> person(c.committer())).distinct().collect(Collectors.joining("、"))).append(" | ")
                    .append(group.stream().map(item -> text(item.unit().path()) + " · " + (item.finding().side() == Side.AFTER ? "变更后 " : "变更前 ") + item.finding().line() + " 行（" + item.unit().commitSha() + "）").distinct().collect(Collectors.joining("；")))
                    .append(" | ").append(text(f.title())).append("：").append(text(f.detail())).append(" | ").append(text(f.recommendation())).append(" |\n");
        }
        return table.toString();
    }
    private static String people(TemplateGitEvidence evidence, Map<String, String> paths, String from) {
        var rows = new TreeMap<String, Set<String>>();
        for (Commit commit : evidence.commits()) {
            add(rows, commit.author(), "作者", commit.sha()); add(rows, commit.committer(), "提交者", commit.sha());
            commit.coauthors().forEach(person -> add(rows, person, "共同作者", commit.sha()));
        }
        StringBuilder body = new StringBuilder("关联提交详情包含本次该提交的全部问题；身份来自 Git，不表示缺陷责任认定。\n\n| 人员与角色 | 关联提交及问题 |\n| --- | --- |\n");
        rows.forEach((name, shas) -> body.append("| ").append(name).append(" | ")
                .append(shas.stream().map(sha -> link(from, paths.get(sha), sha)).collect(Collectors.joining("、"))).append(" |\n"));
        return body.toString();
    }
    private static void add(Map<String, Set<String>> rows, CommitIdentity identity, String role, String sha) {
        if (identity != null) rows.computeIfAbsent(person(identity) + " · " + role, ignored -> new TreeSet<>()).add(sha);
    }
    private static String person(CommitIdentity value) { return value == null ? "历史证据未采集" : text(value.name()) + "（" + text(value.email()) + "）"; }
    private static String identity(CommitIdentity value) {
        if (value == null) return "历史证据未采集";
        return person(value) + "；原始身份：" + text(value.rawName()) + "（" + text(value.rawEmail()) + "）"
                + (value.time() == null ? "" : "；时间：" + text(value.time()));
    }
    private static String link(String from, String to, String label) { return "[" + text(label) + "](" + TemplateReportNames.link(from, to) + ")"; }
    private static Document detail(TemplateReportLayout.Frozen layout, TemplateReportNames names, String path, String title, String body) {
        return new Document(path, layout.render("detail", Map.of("title", text(title), "navigation", link(path, names.main(), "返回总结报告"), "body", body)));
    }
}
