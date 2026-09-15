package io.opencode.loopper.template;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class HistoryReviewReportTest {
    @Test void everyFindingHasServerOwnedAuthorAndCommitterAndPeopleIndexPreservesRawIdentity() {
        var a = new TemplateGitEvidence.CommitIdentity("Raw Alice", "old@example.test", "Alice", "alice@example.test", "2026-09-01T01:00:00Z");
        var c = new TemplateGitEvidence.CommitIdentity("Bot", "bot@example.test", "Bot", "bot@example.test", "2026-09-02T01:00:00Z");
        var co = new TemplateGitEvidence.CommitIdentity("Bob", "bob@example.test", "Bob", "bob@example.test", null);
        var commit = new TemplateGitEvidence.Commit("abc123", List.of("parent"), c.time(), "修订", List.of(new TemplateGitEvidence.Contributor("alice", a.name(), a.email(), false)),
                "ANALYZE", List.of(new TemplateGitEvidence.Change("e", "main.java", "old", "new", 1, 0, false, 1, null, "@@ -0,0 +1 @@\n+throw new Error();\n")), a, c, List.of(co));
        var evidence = new TemplateGitEvidence(TemplateGitEvidence.VERSION, "main", "abc123", "2026-09-01", "2026-09-10", "Asia/Shanghai", "map", List.of(commit));
        var unit = TemplateAnalysisPartitioner.units(evidence).getFirst();
        var accepted = new TemplateAnalysis.Accepted(List.of(new TemplateAnalysis.UnitReview(unit.id(), "新增代码", List.of(new TemplateAnalysis.Finding(
                TemplateAnalysis.Severity.HIGH, TemplateAnalysis.Side.AFTER, 1, "错误", "支持输入触发异常", "修正")), List.of())), List.of());
        var report = TemplateReportCompiler.compile(TemplateTaskDefinition.CODE_REVIEW, "项目", evidence, accepted, TemplateReportLayout.freezeHistory());
        assertThat(report.documents().getFirst().markdown()).contains("历史提交审查", "abc123", "alice@example.test", "bot@example.test", "未复核");
        assertThat(report.documents()).anySatisfy(d -> assertThat(d.markdown()).contains("人员与关联提交", "Bob", "abc123"));
        assertThat(report.documents()).anySatisfy(d -> assertThat(d.markdown()).contains("Raw Alice", "old@example.test", "共同作者"));
        var old = TemplateReportCompiler.compile(TemplateTaskDefinition.CODE_REVIEW, "项目", evidence, accepted, TemplateReportLayout.freeze());
        assertThat(old.documents().getFirst().markdown()).contains("CODE_REVIEW_V3").doesNotContain("HISTORY_REVIEW_V1");
    }
}
