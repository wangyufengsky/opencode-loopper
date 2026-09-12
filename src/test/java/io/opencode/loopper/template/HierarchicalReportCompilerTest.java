package io.opencode.loopper.template;

import static org.assertj.core.api.Assertions.*;
import static io.opencode.loopper.template.TemplateReportLayoutTest.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class HierarchicalReportCompilerTest {
    @Test void hierarchyHasCompleteDetailsAndEveryOfflineLinkResolvesWithinTheBundle() throws Exception {
        var evidence = fixture();
        for (var type : TemplateTaskDefinition.values()) {
            var result = TemplateReportCompiler.compile(type, "模板示例项目（虚构）", evidence, candidate(evidence));
            var names = TemplateReportNames.of(type, "模板示例项目（虚构）", evidence, 1);
            assertThat(result.documents()).hasSize(5);
            assertThat(result.documents().getFirst().path()).isEqualTo(names.main());
            String main = result.documents().getFirst().markdown();
            assertThat(main).contains("总结报告", "| 提交覆盖 | 2 / 2 |", "求和变为相减").doesNotContain("### commit-alice", "虚构示例：求和契约");
            var paths = result.documents().stream().map(TemplateReportCompiler.Document::path).toList();
            assertThat(new HashSet<>(paths)).hasSize(paths.size());
            for (var document : result.documents()) {
                assertThat(Path.of(document.path()).getFileName().toString()).matches(".+_.+_20260905-20260911_\\d{3}\\.md");
                if (!document.path().equals(names.main())) assertThat(document.markdown()).contains("返回总结报告");
                var links = Pattern.compile("\\]\\(([^)]+)\\)").matcher(document.markdown());
                while (links.find()) {
                    Path parent = Path.of(document.path()).getParent();
                    var target = (parent == null ? Path.of("") : parent).resolve(URLDecoder.decode(links.group(1), StandardCharsets.UTF_8)).normalize();
                    assertThat(paths).contains(target.toString().replace('\\', '/'));
                }
                String examples = System.getProperty("template.report.hierarchyExamplesDir");
                if (examples != null) {
                    Path output = Path.of(examples).resolve(names.folder()).resolve(document.path());
                    Files.createDirectories(output.getParent()); Files.writeString(output, document.markdown().stripTrailing() + "\n");
                }
            }
            if (type == TemplateTaskDefinition.CONTRIBUTION_REPORT) {
                for (var personal : result.documents().stream().filter(doc -> doc.path().contains("/个人贡献-")).toList()) {
                    assertThat(personal.markdown()).contains("| 提交覆盖 | 1 / 1 |", "| **总分**", "评分标准");
                    if (personal.path().contains("Alice")) assertThat(personal.markdown()).contains("F001", "commit-alice").doesNotContain("### commit-bob");
                    if (personal.path().contains("Bob")) assertThat(personal.markdown()).contains("commit-bob").doesNotContain("### commit-alice", "求和变为相减");
                }
            }
        }
    }

    @Test void currentLayoutIsDeterministicAndLegacyV2StillUsesTheFrozenBytes() {
        var evidence = fixture();
        var accepted = candidate(evidence);
        var reversed = new TemplateAnalysis.Accepted(accepted.reviews().reversed(), accepted.contributors().reversed());
        for (var type : TemplateTaskDefinition.values()) {
            assertThat(TemplateReportCompiler.compile(type, "project", evidence, reversed))
                    .isEqualTo(TemplateReportCompiler.compile(type, "project", evidence, accepted));
        }
        var legacy = TemplateReportCompiler.compile(TemplateTaskDefinition.CODE_REVIEW, "project", evidence, accepted, TemplateReportLayout.freezeV2());
        assertThat(legacy.documents()).hasSize(1);
        assertThat(legacy.documents().getFirst().path()).isEqualTo("code-review.md");
        assertThat(legacy.documents().getFirst().markdown()).contains("CODE_REVIEW_V2", "逐提交审查");
    }

    @Test void namesUseRangeDatesAndSafeBoundedSegmentsWithStableNumbering() {
        var names = TemplateReportNames.of(TemplateTaskDefinition.CODE_REVIEW, "../项目 /\\ :*?\"<>|\n😀".repeat(50), fixture(), 1001);
        assertThat(names.folder()).endsWith("_20260905-20260911_1001").doesNotContain("/", "\\", ":", "\n");
        assertThat(names.folder().getBytes(StandardCharsets.UTF_8).length).isLessThan(240);
        assertThat(names.child("个人贡献-" + "名字".repeat(100), 2).split("/")).allSatisfy(part ->
                assertThat(part.getBytes(StandardCharsets.UTF_8).length).isLessThan(240));
        assertThat(TemplateReportNames.of(TemplateTaskDefinition.CODE_REVIEW, "project", fixture(), 1).namespace())
                .isEqualTo(TemplateReportNames.of(TemplateTaskDefinition.CODE_REVIEW, "PROJECT", fixture(), 2).namespace());
        assertThat(TemplateReportNames.link("明细/个人.md", "主报告.md")).startsWith("../");
    }

    @Test void emptyRangeKeepsSummaryAndNavigationWithoutInventingPeopleOrCommits() {
        var empty = new TemplateGitEvidence("v2", "main", "head", "2026-09-11", "2026-09-11", "Asia/Shanghai", null, List.of());
        for (var type : TemplateTaskDefinition.values()) {
            var result = TemplateReportCompiler.compile(type, "project\n## forged", empty, new TemplateAnalysis.Accepted(List.of(), List.of()));
            assertThat(result.documents()).hasSize(3);
            assertThat(result.documents().getFirst().markdown()).contains("没有 Git 提交", "| 提交覆盖 | 0 / 0 |").doesNotContain("\n## forged");
            assertThat(result.ranking()).isEmpty();
        }
    }
}
