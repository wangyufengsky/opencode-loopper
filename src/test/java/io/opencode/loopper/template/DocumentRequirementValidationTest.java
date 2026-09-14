package io.opencode.loopper.template;

import static io.opencode.loopper.template.DocumentRequirements.*;
import static org.assertj.core.api.Assertions.*;
import io.opencode.loopper.service.BadRequestException;
import java.util.List;
import org.junit.jupiter.api.Test;

class DocumentRequirementValidationTest {
    private final List<SourceSection> input = List.of(
            new SourceSection("doc-a", 0, "订单", "金额必须大于零；只能撤回本人创建的待审批订单。", "sha-a"),
            new SourceSection("doc-b", 0, "背景", "现有系统使用 Java。", "sha-b"));
    private final Requirement requirement = new Requirement("RQ-1", "校验金额", "订单", Kind.RULE,
            "金额必须大于零", List.of(new Source("doc-a", 0, "金额必须大于零")),
            List.of("零和负金额应被拒绝"), List.of());
    private Candidate candidate(Requirement item) {
        return new Candidate(List.of(item), List.of(
                new Coverage("doc-a", 0, Disposition.REQUIREMENT, List.of("RQ-1"), "业务规则"),
                new Coverage("doc-b", 0, Disposition.BACKGROUND, List.of(), "系统技术背景")));
    }
    @Test void structuredCoverageDoesNotHideSemanticOmissionFromIndependentReviewer() {
        var candidate = candidate(requirement);
        assertThat(DocumentRequirementValidation.extraction(input, candidate)).isEqualTo(candidate);
        var review = new Review(false, List.of("RQ-1"), candidate.coverage(), List.of(
                new Correction(null, "OMISSION", "遗漏撤回归属和状态限制", List.of(
                        new Source("doc-a", 0, "只能撤回本人创建的待审批订单")))));
        assertThat(DocumentRequirementValidation.review(input, candidate, review).approved()).isFalse();
        assertThatThrownBy(() -> DocumentRequirementValidation.review(input, candidate,
                new Review(true, review.reviewedRequirementKeys(), review.coverage(), review.corrections())))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("不能批准");
    }
    @Test void missingSectionAndForgedSourceAreRejected() {
        assertThatThrownBy(() -> DocumentRequirementValidation.extraction(input,
                new Candidate(List.of(requirement), List.of(candidate(requirement).coverage().getFirst()))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("每个输入分段");
        var forged = new Requirement("RQ-1", "金额", "订单", Kind.RULE, "金额允许为零",
                List.of(new Source("doc-a", 0, "金额允许为零")), List.of(), List.of());
        assertThatThrownBy(() -> DocumentRequirementValidation.extraction(input, candidate(forged)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("逐字存在");
    }
    @Test void crossDocumentAttributionAndUnreviewedRequirementCannotPass() {
        var candidate = new Candidate(List.of(requirement), List.of(
                new Coverage("doc-a", 0, Disposition.BACKGROUND, List.of(), "背景"),
                new Coverage("doc-b", 0, Disposition.REQUIREMENT, List.of("RQ-1"), "错误归属")));
        assertThatThrownBy(() -> DocumentRequirementValidation.extraction(input, candidate))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("真实原文引用");
        assertThatThrownBy(() -> DocumentRequirementValidation.review(input, candidate(requirement),
                new Review(true, List.of(), candidate(requirement).coverage(), List.of())))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("全部需求");
    }
}
