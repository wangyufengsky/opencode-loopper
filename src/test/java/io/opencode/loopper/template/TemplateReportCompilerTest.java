package io.opencode.loopper.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TemplateReportCompilerTest {
    private static final String PATCH = "diff --git a/main.py b/main.py\n--- a/main.py\n+++ b/main.py\n@@ -1 +1 @@\n-pass\n+raise ValueError()\n";

    @Test void losslessPartitionsBoundEveryModelInputAndRetainSourceAddresses() {
        String patch = "@@ -1 +1 @@\n-" + "x".repeat(100_000) + "\n+" + "y".repeat(100_000) + "\n";
        var evidence = evidence(patch, 2);
        var units = TemplateAnalysisPartitioner.units(evidence);
        assertThat(units).hasSizeGreaterThan(8);
        assertThat(units.stream().map(TemplateAnalysis.Unit::patch).reduce("", String::concat)).isEqualTo(patch);
        assertThat(units).allSatisfy(unit -> assertThat(unit.patch().length()).isLessThanOrEqualTo(24_000));
        assertThat(TemplateAnalysisPartitioner.batches(units)).allSatisfy(batch -> {
            assertThat(batch.size()).isLessThanOrEqualTo(12);
            assertThat(batch.stream().mapToInt(unit -> unit.patch().length()).sum()).isLessThanOrEqualTo(48_000);
        });
    }

    @Test void reviewMustCoverEveryUnitAndFindingsMustReferenceObservedBlobLines() {
        var units = TemplateAnalysisPartitioner.units(evidence(PATCH, 2));
        assertThatThrownBy(() -> TemplateAnalysisValidation.batch(units, new TemplateAnalysis.BatchCandidate(List.of())))
                .hasMessageContaining("全部证据");
        var invalid = new TemplateAnalysis.UnitReview(units.getFirst().id(), "review", List.of(
                new TemplateAnalysis.Finding(TemplateAnalysis.Severity.HIGH, TemplateAnalysis.Side.AFTER, 999,
                        "problem", "because", "fix")), List.of());
        assertThatThrownBy(() -> TemplateAnalysisValidation.batch(units, new TemplateAnalysis.BatchCandidate(List.of(invalid))))
                .hasMessageContaining("已读取的变更行");
        var crossScope = new TemplateAnalysis.UnitReview("another-task-evidence", "review", List.of(), List.of());
        assertThatThrownBy(() -> TemplateAnalysisValidation.batch(units, new TemplateAnalysis.BatchCandidate(List.of(crossScope))))
                .hasMessageContaining("未知证据");
    }

    @Test void realProblemsAndLowScoresDoNotInvalidateAnOtherwiseCompleteReport() {
        var evidence = evidence(PATCH, 2);
        var units = TemplateAnalysisPartitioner.units(evidence);
        var review = new TemplateAnalysis.UnitReview(units.getFirst().id(), "新增抛错路径", List.of(
                new TemplateAnalysis.Finding(TemplateAnalysis.Severity.HIGH, TemplateAnalysis.Side.AFTER, 1,
                        "未捕获异常", "调用时直接抛错", "增加必要处理")), List.of("未运行测试"));
        var candidate = new TemplateAnalysis.Accepted(List.of(review), List.of(assessment("alice", 1), assessment("bob", 1)));
        var result = TemplateReportCompiler.compile(TemplateTaskDefinition.CONTRIBUTION_REPORT, "project", evidence, candidate);
        assertThat(result.documents()).hasSize(5);
        assertThat(result.ranking()).extracting(ContributionScore.Ranked::rank).containsExactly(1, 1);
        assertThat(result.ranking().getFirst().total()).isEqualByComparingTo("47.50");
        assertThat(TemplateContributionFacts.people(evidence)).allSatisfy(person -> assertThat(person.effectiveLines()).isEqualTo(1));
        assertThat(result.documents().getFirst().markdown()).contains("CONTRIBUTION_SCORE_V1", "未捕获异常", "未运行测试", "测试文件存在不代表测试已运行通过");
        assertThat(result.documents()).anySatisfy(document -> assertThat(document.markdown()).contains("评分依据", "个人贡献详细报告"));
    }

    @Test void crossContributorEvidenceIsRejectedAndZeroContributorsRemainVisible() {
        assertThatThrownBy(() -> TemplateAnalysisValidation.contributor("alice", java.util.Set.of("another"), assessment("alice", 3)))
                .hasMessageContaining("其他贡献者");
        var evidence = evidence(PATCH, 0);
        var reviews = TemplateAnalysisPartitioner.units(evidence).stream()
                .map(unit -> new TemplateAnalysis.UnitReview(unit.id(), "生成文件，保留记录", List.of(), List.of())).toList();
        var result = TemplateReportCompiler.compile(TemplateTaskDefinition.CONTRIBUTION_REPORT, "project", evidence,
                new TemplateAnalysis.Accepted(reviews, List.of()));
        assertThat(result.documents()).hasSize(5);
        assertThat(result.ranking()).allSatisfy(row -> assertThat(row.total()).isZero());
    }

    @Test void emptyRangeNeedsNoModelClaimsAndGeneratedMarkdownEscapesUntrustedText() {
        var empty = new TemplateGitEvidence("v1", "local:main", "head", "2026-09-11", "2026-09-11", "Asia/Shanghai", null, List.of());
        var result = TemplateReportCompiler.compile(TemplateTaskDefinition.CONTRIBUTION_REPORT, "<script>alert(1)</script>", empty,
                new TemplateAnalysis.Accepted(List.of(), List.of()));
        assertThat(result.ranking()).isEmpty();
        assertThat(result.documents()).hasSize(3);
        assertThat(result.documents().getFirst().markdown()).contains("没有 Git 提交", "&lt;script&gt;").doesNotContain("<script>");
    }

    private static TemplateAnalysis.ContributorCandidate assessment(String identity, int level) {
        var grade = new ContributionScore.Assessment(level, "有变更证据", List.of("evidence"));
        return new TemplateAnalysis.ContributorCandidate(identity, "完成局部变更", grade, grade, grade, grade);
    }
    private static TemplateGitEvidence evidence(String patch, long effective) {
        var authors = List.of(new TemplateGitEvidence.Contributor("alice", "Alice", "alice@example.test", false),
                new TemplateGitEvidence.Contributor("bob", "Bob", "bob@example.test", false));
        var change = new TemplateGitEvidence.Change("evidence", "main.py", "old-blob", "new-blob", 1, 1, false,
                effective, effective == 0 ? "GENERATED_MARKER" : null, patch);
        var commit = new TemplateGitEvidence.Commit("sha", List.of("parent"), "2026-09-11T00:00:00Z", "change", authors, "ANALYZE", List.of(change));
        return new TemplateGitEvidence("v1", "local:main", "head", "2026-09-11", "2026-09-11", "Asia/Shanghai", null, List.of(commit));
    }
}
