package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opencode.loopper.domain.ExecutionStrategy;
import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.domain.StageKind;
import io.opencode.loopper.domain.TaskIntent;
import io.opencode.loopper.domain.WorkflowTemplate;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class DirectArtifactConfirmationPolicyTest {
    private final TaskProfileService.View profile = mock(TaskProfileService.View.class);
    private final LoopSpec.StageSpec authoring = JsonMapper.builder().build().readValue("""
            {"objective":"撰写正文","deliverables":["docs/design.md"],
             "stageKind":"DOCUMENT_AUTHORING","executionStrategy":"OPEN_CODE_IMPLEMENTATION",
             "verifiers":[{"type":"DOCUMENT_STRUCTURE","path":"docs/design.md"}],
             "acceptanceCriteria":[{"id":"AC-2","description":"正文完整",
               "verificationMode":"JUDGE","judgeRubric":"与来源核对"}]}
            """, LoopSpec.StageSpec.class);

    DirectArtifactConfirmationPolicyTest() {
        when(profile.state()).thenReturn("FROZEN");
        when(profile.intent()).thenReturn(TaskIntent.DOCUMENT_AUTHORING);
        when(profile.workflowTemplate()).thenReturn(WorkflowTemplate.DIRECT_ARTIFACT);
        when(profile.executionStrategy()).thenReturn(ExecutionStrategy.OPEN_CODE_IMPLEMENTATION);
    }

    @Test void simpleAndPackagedWritingNeedNoMaterializationPlan() {
        assertThat(eligible(authoring)).isTrue();
        when(profile.workflowTemplate()).thenReturn(WorkflowTemplate.PACKAGED_ARTIFACT);
        assertThat(eligible(authoring)).isTrue();
    }

    @Test void anUnfrozenOrMismatchedProfileCannotConfirmAuthoring() {
        when(profile.state()).thenReturn("PROPOSED");
        assertThat(eligible(authoring)).isFalse();
        when(profile.state()).thenReturn("FROZEN");
        when(profile.executionStrategy()).thenReturn(ExecutionStrategy.SERVER_DOCUMENT_MATERIALIZATION);
        assertThat(eligible(authoring)).isFalse();
        when(profile.executionStrategy()).thenReturn(ExecutionStrategy.OPEN_CODE_IMPLEMENTATION);
        when(profile.intent()).thenReturn(TaskIntent.DATA_CONVERSION);
        assertThat(eligible(authoring)).isFalse();
    }

    @Test void formatChecksAloneCannotMakeAnAuthoringPlanConfirmable() {
        assertThat(eligible(stage(StageKind.DOCUMENT_AUTHORING, ExecutionStrategy.OPEN_CODE_IMPLEMENTATION,
                null, List.of()))).isFalse();
    }

    @Test void anAuthoringStageCannotFallBackToAnUnrelatedFrozenPlan() {
        assertThat(eligible(stage(StageKind.DOCUMENT_AUTHORING, ExecutionStrategy.SERVER_DOCUMENT_MATERIALIZATION,
                "frozen-plan", authoring.acceptanceCriteria()))).isFalse();
    }

    @Test void historicalDocumentAndTabularPlansStillRequireAFrozenPlan() {
        for (StageKind kind : List.of(StageKind.DOCUMENT_MATERIALIZATION, StageKind.TABULAR_CONVERSION)) {
            ExecutionStrategy strategy = kind == StageKind.TABULAR_CONVERSION
                    ? ExecutionStrategy.SERVER_TABULAR_CONVERSION : ExecutionStrategy.SERVER_DOCUMENT_MATERIALIZATION;
            when(profile.executionStrategy()).thenReturn(strategy);
            var stage = stage(kind, strategy, "frozen-plan", List.of());
            assertThat(eligible(stage)).isTrue();
            assertThat(DirectArtifactConfirmationPolicy.eligible(profile, spec(stage), id -> false)).isFalse();
            assertThat(eligible(stage(kind, strategy, null, List.of()))).isFalse();
        }
    }

    private boolean eligible(LoopSpec.StageSpec stage) {
        return DirectArtifactConfirmationPolicy.eligible(profile, spec(stage), "frozen-plan"::equals);
    }

    private LoopSpec spec(LoopSpec.StageSpec stage) {
        return new LoopSpec("v2", "project", "文档", "需求", List.of(stage), null, null, null, null);
    }

    private LoopSpec.StageSpec stage(StageKind kind, ExecutionStrategy strategy, String plan,
                                     List<LoopSpec.AcceptanceCriterion> criteria) {
        return new LoopSpec.StageSpec(authoring.objective(), List.of(), List.of(), authoring.deliverables(),
                authoring.verifiers(), criteria, null, null, "WP-1", kind, strategy, plan);
    }
}
