package io.opencode.loopper.template;

import static org.assertj.core.api.Assertions.*;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.service.TemplateTaskContractFactory;
import io.opencode.loopper.template.TemplateAnalysis.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class TemplateReportLayoutTest {
    private static final List<String> REVIEW = List.of("## 一、审查范围", "## 二、结论摘要", "## 三、问题清单", "## 四、逐提交审查", "## 五、覆盖与局限");
    private static final List<String> TOTAL = List.of("## 一、统计范围", "## 二、项目贡献概览", "## 三、贡献排名", "## 四、人员评分依据", "## 五、项目工作与问题明细", "## 六、内置评分标准", "## 七、统计口径与局限");
    private static final List<String> PERSONAL = List.of("## 一、统计范围", "## 二、贡献概览", "## 三、工作明细", "## 四、问题与改进建议", "## 五、评分依据", "## 六、内置评分标准", "## 七、统计口径与局限");

    @Test void allThreeReportShapesAreFixedAndPersonalCoverageIncludesOnlyOwnedCommits() throws Exception {
        var evidence = fixture();
        var candidate = candidate(evidence);
        var review = TemplateReportCompiler.compile(TemplateTaskDefinition.CODE_REVIEW, "模板示例项目（虚构）", evidence, candidate, TemplateReportLayout.freezeV2()).documents().getFirst();
        var total = TemplateReportCompiler.compile(TemplateTaskDefinition.CONTRIBUTION_REPORT, "模板示例项目（虚构）", evidence, candidate, TemplateReportLayout.freezeV2());
        assertThat(headings(review.markdown())).isEqualTo(REVIEW);
        assertThat(headings(total.documents().getFirst().markdown())).isEqualTo(TOTAL);
        assertThat(total.documents()).hasSize(3);
        for (var personal : total.documents().subList(1, 3)) {
            assertThat(headings(personal.markdown())).isEqualTo(PERSONAL);
            assertThat(personal.markdown()).contains("| 提交覆盖 | 1 / 1 |", "| 证据片段覆盖 | 1 / 1 |", "| **总分**", "CONTRIBUTION_SCORE_V1");
        }
        assertThat(total.documents().getFirst().markdown()).contains("| 提交覆盖 | 2 / 2 |", "数量 /30", "质量 /20", "贡献排名");
        assertThat(review.markdown()).contains("CODE_REVIEW_V2", "F001", "Calculator.java", "变更后第 1 行");
        String examples = System.getProperty("template.report.examplesDir");
        if (examples != null) {
            var documents = new java.util.ArrayList<>(total.documents());
            documents.add(review);
            for (var document : documents) {
                Path file = Path.of(examples).resolve(document.path());
                Files.createDirectories(file.getParent());
                Files.writeString(file, document.markdown());
            }
        }
    }

    @Test void emptyReportsRetainAllSectionsAndUntrustedTextCannotIntroduceHeadingsOrTableRows() {
        var empty = new TemplateGitEvidence("v2", "main", "head", "2026-09-11", "2026-09-11", "Asia/Shanghai", null, List.of());
        for (var definition : TemplateTaskDefinition.values()) {
            var result = TemplateReportCompiler.compile(definition, "project\n\n## 假章节\n| forged |", empty, new Accepted(List.of(), List.of()), TemplateReportLayout.freezeV2());
            assertThat(result.documents()).hasSize(1);
            assertThat(headings(result.documents().getFirst().markdown())).isEqualTo(definition == TemplateTaskDefinition.CODE_REVIEW ? REVIEW : TOTAL);
            assertThat(result.documents().getFirst().markdown()).contains("没有 Git 提交", "| 提交覆盖 | 0 / 0 |").doesNotContain("\n| forged |");
        }
    }

    @Test void frozenLayoutRoundTripsAndOldContractsKeepTheirOriginalReportFormat() {
        var properties = new LoopperProperties();
        properties.getOpenCode().setModel("fake/test-model");
        var frozen = new TemplateTaskContractFactory(properties).freeze(TemplateTaskDefinition.CODE_REVIEW, "project",
                TemplateDateRange.parse("2026-09-11", "2026-09-11", Clock.systemUTC()));
        var json = new ObjectMapper();
        var copy = json.readValue(json.writeValueAsString(frozen), TemplateTaskContractFactory.Frozen.class);
        assertThat(copy.reportTemplates()).isEqualTo(frozen.reportTemplates());
        var tree = (tools.jackson.databind.node.ObjectNode) json.valueToTree(frozen);
        tree.remove("reportTemplates");
        var old = json.treeToValue(tree, TemplateTaskContractFactory.Frozen.class);
        assertThat(old.reportTemplates()).isNull();
        String previous = TemplateReportCompiler.compile(TemplateTaskDefinition.CODE_REVIEW, "project", fixture(), candidate(fixture()), old.reportTemplates()).documents().getFirst().markdown();
        assertThat(previous).contains("## 审查结果").doesNotContain("CODE_REVIEW_V2", "## 一、审查范围");
        var changed = new java.util.HashMap<>(copy.reportTemplates().templates());
        changed.put("review", "different");
        assertThatThrownBy(() -> new TemplateReportLayout.Frozen(copy.reportTemplates().version(), changed, copy.reportTemplates().sha256()))
                .hasMessageContaining("校验和");
    }

    @Test void candidateOrderCannotChangeReportBytes() {
        var evidence = fixture();
        var candidate = candidate(evidence);
        var reordered = new Accepted(candidate.reviews().reversed(), candidate.contributors().reversed());
        for (var definition : TemplateTaskDefinition.values()) {
            assertThat(TemplateReportCompiler.compile(definition, "project", evidence, reordered))
                    .isEqualTo(TemplateReportCompiler.compile(definition, "project", evidence, candidate));
        }
    }

    private static List<String> headings(String markdown) { return markdown.lines().filter(line -> line.startsWith("## ")).toList(); }
    static TemplateGitEvidence fixture() {
        var alice = new TemplateGitEvidence.Contributor("alice", "Alice（示例）", "alice@example.test", false);
        var bob = new TemplateGitEvidence.Contributor("bob", "Bob（示例）", "bob@example.test", false);
        String patch = "@@ -1 +1 @@\n-return a + b;\n+return a - b;\n";
        var first = new TemplateGitEvidence.Commit("commit-alice", List.of("base"), "2026-09-11T00:00:00Z", "调整计算逻辑（虚构示例）", List.of(alice), "ANALYZE",
                List.of(new TemplateGitEvidence.Change("evidence-alice", "Calculator.java", "before", "after", 1, 1, false, 2, null, patch)));
        var second = new TemplateGitEvidence.Commit("commit-bob", List.of("commit-alice"), "2026-09-11T01:00:00Z", "补充说明（虚构示例）", List.of(bob), "ANALYZE",
                List.of(new TemplateGitEvidence.Change("evidence-bob", "README.md", "before", "after", 1, 1, false, 2, null, "@@ -1 +1 @@\n-old text\n+new text\n")));
        return new TemplateGitEvidence("v2", "main", "head-example", "2026-09-05", "2026-09-11", "Asia/Shanghai", null, List.of(first, second));
    }
    static Accepted candidate(TemplateGitEvidence evidence) {
        var reviews = TemplateAnalysisPartitioner.units(evidence).stream().map(unit -> new UnitReview(unit.id(), "已核对本片段变更（示例）",
                unit.path().equals("Calculator.java") ? List.of(new Finding(Severity.HIGH, Side.AFTER, 1, "求和变为相减（示例）",
                        "虚构示例：求和契约要求 a+b，变更后计算 a-b。", "恢复加法并验证正负数边界。")) : List.of(), List.of("未运行项目测试。"))).toList();
        var people = evidence.commits().stream().map(commit -> {
            String identity = commit.contributors().getFirst().identity();
            var grade = new ContributionScore.Assessment(1, "依据本人的局部变更给出示例等级。", List.of("evidence-" + identity));
            var quality = new ContributionScore.Assessment(identity.equals("alice") ? 0 : 1,
                    identity.equals("alice") ? "示例提交存在已定位的计算错误。" : "示例文档变更可读，未执行验证。", List.of("evidence-" + identity));
            return new ContributorCandidate(identity, "本周完成的 Git 变更摘要（虚构示例）。", grade, grade, quality, grade);
        }).toList();
        return new Accepted(reviews, people);
    }
}
