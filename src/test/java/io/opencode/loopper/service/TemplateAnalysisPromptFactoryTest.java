package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import io.opencode.loopper.template.TemplateAnalysis;
import io.opencode.loopper.template.TemplateContributionFacts;
import io.opencode.loopper.template.TemplateGitEvidence;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class TemplateAnalysisPromptFactoryTest {
    @Test void contributorResponseExampleUsesTheFrozenMachineIdentityInsteadOfANamePlaceholder() {
        String identity = TemplateGitEvidenceCollector.hash("alice@example.test");
        var person = new TemplateContributionFacts.Person(new TemplateGitEvidence.Contributor(identity,
                "Alice", "alice@example.test", false), List.of("commit"), Set.of("evidence"), 2, 2);
        String prompt = new TemplateAnalysisPromptFactory(new ObjectMapper()).contributor(person, List.of(), List.of(), "");
        assertThat(prompt).contains("返回 {\"identity\":\"" + identity + "\"")
                .contains("不得使用姓名、邮箱、提交 SHA").doesNotContain("\"identity\":\"给定身份\"");
    }

    @Test void continuationWithoutHunkHeaderCarriesOriginalLineAddresses() {
        var unit = new TemplateAnalysis.Unit("evidence:1", "commit", "evidence", "example.py", "ANALYZE",
                " context\n-old\n+new\n", List.of(
                new TemplateAnalysis.SourceLine(TemplateAnalysis.Side.BEFORE, 501),
                new TemplateAnalysis.SourceLine(TemplateAnalysis.Side.BEFORE, 502),
                new TemplateAnalysis.SourceLine(TemplateAnalysis.Side.AFTER, 700),
                new TemplateAnalysis.SourceLine(TemplateAnalysis.Side.AFTER, 701)));
        String prompt = new TemplateAnalysisPromptFactory(new ObjectMapper()).review(List.of(unit), "");
        assertThat(prompt).contains("不得从 1 重新计数", "501-502", "700-701", "evidence:1");
    }
    @Test void internalTransportNeverStripsEvidenceAndUsesFrozenFragments() {
        var factory = new TemplateAnalysisPromptFactory(new ObjectMapper());
        String marker = "CUSTOM TEXT TRANSPORT";
        var fragments = java.util.Map.of("prompt.v1.TemplateAnalysisPromptFactory.block01", marker,
                "prompt.v1.TemplateAnalysisPromptFactory.block04.segment2", "CUSTOM MCP RULES",
                "prompt.v1.TemplateAnalysisPromptFactory.block03.segment0", "CUSTOM CONTINUATION ");
        String prompt = io.opencode.loopper.service.roles.RolePromptResources.withFragments(fragments, () ->
                factory.internal(factory.review(List.of(), "user content: " + marker, false), "batch", "server_submit_template_analysis"));
        assertThat(prompt).contains("user content: " + marker, "CUSTOM MCP RULES", "server_submit_template_analysis")
                .doesNotContain(TemplateAnalysisPromptFactory.START);
        assertThat(prompt.indexOf(marker)).isEqualTo(prompt.lastIndexOf(marker));
        String legacy = io.opencode.loopper.service.roles.RolePromptResources.withFragments(fragments,
                () -> factory.review(List.of(), ""));
        assertThat(legacy).contains(marker).doesNotContain("CUSTOM MCP RULES");
        assertThat(io.opencode.loopper.service.roles.RolePromptResources.withFragments(fragments,
                () -> TemplateAnalysisPromptFactory.continuation("batch", "server", 2))).contains("CUSTOM CONTINUATION");
    }

    @Test void snapshotRoleFreezesItsSharedSubmissionAndContinuationFragments() {
        var fragments = new java.util.HashMap<>(io.opencode.loopper.service.roles.RolePromptResources.defaultsForRole("builtin.snapshot-review"));
        assertThat(fragments).containsKeys("snapshot.review.compact", "prompt.v1.TemplateAnalysisPromptFactory.block04.segment2",
                "prompt.v1.TemplateAnalysisPromptFactory.block03.segment0", "prompt.v1.TemplateAnalysisPromptFactory.block03.segment3");
        assertThat(fragments.get("snapshot.review.standard")).contains("授权项目知识工具", "替代冻结快照").doesNotContain("只能使用专用 MCP");
        assertThat(fragments.get("snapshot.review.compact")).contains("授权项目知识工具", "不得扩大本批范围");
        fragments.put("prompt.v1.TemplateAnalysisPromptFactory.block04.segment2", "快照角色专属提交规则");
        assertThat(io.opencode.loopper.service.roles.RolePromptResources.withFragments(fragments,
                () -> new TemplateAnalysisPromptFactory(new ObjectMapper()).internal("冻结快照", "batch", "submit")))
                .contains("快照角色专属提交规则");
    }
}
