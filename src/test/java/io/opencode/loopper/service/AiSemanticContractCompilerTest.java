package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AiSemanticContractCompilerTest {
    @Test
    void derivesPackageModeAndStableIds() {
        assertThat(AiSemanticContractCompiler.decompositionStatus("READY", 1)).isEqualTo("DIRECT_DESIGN");
        assertThat(AiSemanticContractCompiler.decompositionStatus("READY", 3)).isEqualTo("DECOMPOSED");
        assertThat(AiSemanticContractCompiler.workPackageId(2)).isEqualTo("WP-3");
        assertThat(AiSemanticContractCompiler.acceptanceId("WP-2", 4)).isEqualTo("WP-2-AC-4");
    }

    @Test
    void derivesVerificationModeWithoutModelMetadata() {
        assertThat(AiSemanticContractCompiler.verificationMode(true, null, null)).isEqualTo("MACHINE");
        assertThat(AiSemanticContractCompiler.verificationMode(true, "rubric", null)).isEqualTo("BOTH");
        assertThat(AiSemanticContractCompiler.verificationMode(false, "rubric", "cannot automate"))
                .isEqualTo("JUDGE");
        assertThatThrownBy(() -> AiSemanticContractCompiler.verificationMode(false, null, null))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void derivedStatusOverridesContradictoryLegacyMode() {
        assertThat(AiSemanticContractCompiler.decompositionStatus("READY", 3)).isNotEqualTo("DIRECT_DESIGN");
    }

    @Test
    void groupsStructuredMarkdownByBusinessSectionInsteadOfEveryPresentationLine() {
        var segments = DesignerSessionService.segmentRequirements("""
                # 需求快照

                - 版本：v1
                - 日期：2026-08-17

                ## 目标

                - 发布事件可推进状态。
                - 迁移成功后广播事件。

                ---

                ## 边界

                - 不新增依赖。
                - 保留现有 DSL。
                """);

        assertThat(segments).hasSize(2);
        assertThat(segments.get(0).id()).isEqualTo("RQ-1");
        assertThat(segments.get(0).text()).contains("目标", "发布事件可推进状态", "迁移成功后广播事件");
        assertThat(segments.get(1).text()).contains("边界", "不新增依赖", "保留现有 DSL");
        assertThat(segments).allSatisfy(segment -> assertThat(segment.text()).doesNotContain("版本：", "---"));
    }
}
