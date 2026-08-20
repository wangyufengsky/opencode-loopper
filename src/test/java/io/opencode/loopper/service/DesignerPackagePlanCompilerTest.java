package io.opencode.loopper.service;

import static io.opencode.loopper.service.DesignerSemanticContracts.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opencode.loopper.domain.ImplementationKind;
import io.opencode.loopper.persistence.DesignWorkPackageRow;
import java.util.List;
import org.junit.jupiter.api.Test;

class DesignerPackagePlanCompilerTest {
    private final DesignerPackagePlanCompiler compiler =
            new DesignerPackagePlanCompiler(new DesignerEvidenceIndexer());

    @Test
    void compilesBusinessCriteriaAndDropsUnfocusedEngineeringMetaCriteria() {
        CompactPackageCompilationPlan input = new CompactPackageCompilationPlan("compiled", "summary", List.of(
                new CompactStage("实现事件分发", ImplementationKind.JAVA_PRODUCTION,
                        List.of("src/**"), List.of(), List.of("事件分发实现"), List.of(
                        new CompactCriterion("未注册事件必须被安全忽略", List.of("DS-L002"), null, null),
                        new CompactCriterion("mvn test 全量测试通过，退出码 0。", List.of("DS-L003"), null, null)),
                        List.of(focusedTest(List.of(0))), null)), "handoff", List.of());

        DesignerPackagePlanCompiler.Result result = compiler.compile(
                workPackage(), "# 目标\n未注册事件必须被安全忽略\nmvn test 全量测试通过，退出码 0。", input, 6, true);

        assertThat(result.normalizations()).contains("ENGINEERING_META_CRITERIA_SUPPLEMENTALIZED");
        assertThat(result.plan().stages()).hasSize(1);
        assertThat(result.plan().evidenceMappings())
                .extracting(AcceptanceEvidenceMapping::criterionId)
                .containsExactly("WP-1-AC-1");
        assertThat(result.plan().stages().getFirst().verifiers().getFirst().criterionIds())
                .containsExactly("WP-1-AC-1");
    }

    @Test
    void rejectsUnknownFrozenDesignReferencesBeforeProducingExecutablePlan() {
        CompactPackageCompilationPlan input = new CompactPackageCompilationPlan("COMPILED", "summary", List.of(
                new CompactStage("实现事件分发", ImplementationKind.JAVA_PRODUCTION,
                        List.of("src/**"), List.of(), List.of("事件分发实现"),
                        List.of(new CompactCriterion("可观察行为", List.of("DS-L999"), null, null)),
                        List.of(focusedTest(List.of(0))), null)), null, List.of());

        assertThatThrownBy(() -> compiler.compile(workPackage(), "可观察行为", input, 6, true))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("sourceRefs");
    }

    private CompactEvidence focusedTest(List<Integer> covers) {
        return new CompactEvidence("FOCUSED_TEST", List.of("mvn", "-Dtest=EventBusTest", "test"), covers,
                null, null, null, List.of(), List.of(), null, null, null, null, null, null, null, null,
                null, null, null, List.of(), List.of(), List.of());
    }

    private DesignWorkPackageRow workPackage() {
        return new DesignWorkPackageRow("package-row", "designer", "requirement", "decomposition",
                "WP-1", 0, "事件分发", "实现事件分发", "[]", "[]", "[]", "[]", "[]", "[]",
                "DESIGNING", null, null, null, 1, 0, 0, null, null, null, null, null, 0, null, null,
                "now", "now", 0);
    }
}
