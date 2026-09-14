package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.template.*;
import io.opencode.loopper.template.RequirementCodeAssessment.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class DocumentAssessmentValidationTest {
    private final DocumentCodeMapper code = mock(DocumentCodeMapper.class);
    private final DocumentAssessmentValidation validator = new DocumentAssessmentValidation(code);
    private final DocumentTemplateModelRow model = new DocumentTemplateModelRow("model", "run", "REQUIREMENT_CODE_ASSESSMENT_V1",
            0, 0, "RUNNING", "{}", "hash", null, "session", null, null, null, null, null, "now", "now", 4);
    private final CodeReference reference = new CodeReference("Service.java", "b".repeat(40), 2, 2, "checkPermission();");
    @Test void missingSearchHitCannotBecomeMissingImplementationAndUncertaintyIsPreserved() {
        var input = input(List.of());
        assertThatThrownBy(() -> validator.assessment(model, input, candidate(Conclusion.NOT_IMPLEMENTED, List.of())))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("代码证据");
        assertThat(validator.assessment(model, input, candidate(Conclusion.UNDETERMINED, List.of())).items().getFirst().conclusion())
                .isEqualTo(Conclusion.UNDETERMINED);
    }
    @Test void sourceIdentityAndUnresolvedBusinessIssuesCannotBeApproved() {
        when(code.evidence("model", "Service.java", 2, 2)).thenReturn(Optional.of(new DocumentCodeMapper.Read(
                "model", "Service.java", "c".repeat(40), 1, 3, "void run() {\ncheckPermission();\n}", "hash", "now")));
        assertThatThrownBy(() -> validator.assessment(model, input(List.of()), candidate(Conclusion.SATISFIED, List.of(reference))))
                .hasMessageContaining("身份");
        receipt();
        assertThatThrownBy(() -> validator.assessment(model, input(List.of("权限规则冲突")), candidate(Conclusion.SATISFIED, List.of(reference))))
                .hasMessageContaining("未澄清");
        assertThat(validator.assessment(model, input(List.of()), candidate(Conclusion.SATISFIED, List.of(reference))).items()).hasSize(1);
    }
    @Test void independentReviewMustReadSourceBeforeApprovingAndCannotSkipARequirement() {
        var base = input(List.of());
        var input = new DocumentModelInput(base.sections(), base.requirements(), null, base.snapshotSha(), candidate(Conclusion.SATISFIED, List.of(reference)), null);
        assertThatThrownBy(() -> validator.review(model, input, new Review(base.snapshotSha(), true, List.of(), List.of(), List.of())))
                .hasMessageContaining("所有需求");
        assertThatThrownBy(() -> validator.review(model, input, new Review(base.snapshotSha(), true, List.of("RQ-1"), List.of(), List.of())))
                .hasMessageContaining("读取证据");
        receipt();
        assertThat(validator.review(model, input, new Review(base.snapshotSha(), true, List.of("RQ-1"), List.of(), List.of())).approved()).isTrue();
    }
    @Test void unknownRequirementDoesNotBecomeAllSatisfiedInCompletedReport() {
        var requirement = input(List.of()).requirements().requirements().getFirst();
        var report = RequirementReportCompiler.review("权限评审", "a".repeat(40), List.of(new RequirementReportCompiler.Row(requirement,
                candidate(Conclusion.UNDETERMINED, List.of()).items().getFirst())), List.of(), List.of("无法读取共享服务"));
        assertThat(report.allRequirementsSatisfied()).isFalse();
        assertThat(report.files().getFirst().content()).contains("已完成静态分析", "无法判断", "未运行构建、测试");
        assertThat(report.files().get(1).content()).contains("测试执行：本次未执行");
    }
    private void receipt() {
        when(code.evidence("model", "Service.java", 2, 2)).thenReturn(Optional.of(new DocumentCodeMapper.Read(
                "model", "Service.java", reference.blobSha(), 1, 3, "void run() {\ncheckPermission();\n}", "hash", "now")));
    }
    private DocumentModelInput input(List<String> issues) {
        var requirement = new DocumentRequirements.Requirement("RQ-1", "权限", "业务", DocumentRequirements.Kind.PERMISSION,
                "检查操作权限", List.of(new DocumentRequirements.Source("file", 0, "检查权限")), List.of("无权限操作被拒绝"), issues);
        return new DocumentModelInput(List.of(new DocumentModelInput.SectionRef("file", 0, "hash")),
                new DocumentRequirements.Candidate(List.of(requirement), List.of()), null, "a".repeat(40), null, null);
    }
    private Candidate candidate(Conclusion conclusion, List<CodeReference> evidence) {
        return new Candidate("a".repeat(40), List.of(new Item("RQ-1", conclusion, "已说明检查范围和证据", evidence, List.of(),
                null, "未发现相关测试源码；本次未执行", List.of())), List.of(), List.of());
    }
}
