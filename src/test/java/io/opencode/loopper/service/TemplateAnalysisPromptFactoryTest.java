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
}
