package io.opencode.loopper.service;

import io.opencode.loopper.domain.ExecutionStrategy;
import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.domain.StageKind;
import io.opencode.loopper.domain.TaskIntent;
import io.opencode.loopper.domain.WorkflowTemplate;
import java.util.Set;
import java.util.function.Predicate;

/** Review eligibility follows the frozen execution strategy, not the artifact's file format. */
final class DirectArtifactConfirmationPolicy {
    private DirectArtifactConfirmationPolicy() { }

    static boolean eligible(TaskProfileService.View profile, LoopSpec spec, Predicate<String> frozenPlan) {
        return eligible(profile.state(), profile.intent(), profile.workflowTemplate(), profile.executionStrategy(), spec, frozenPlan);
    }

    static boolean eligible(io.opencode.loopper.persistence.DesignerTaskProfileRow profile, LoopSpec spec,
                            Predicate<String> frozenPlan) {
        return eligible(profile.state(), TaskIntent.valueOf(profile.intent()), WorkflowTemplate.valueOf(profile.workflowTemplate()),
                ExecutionStrategy.valueOf(profile.executionStrategy()), spec, frozenPlan);
    }

    private static boolean eligible(String state, TaskIntent intent, WorkflowTemplate workflow,
                                    ExecutionStrategy strategy, LoopSpec spec, Predicate<String> frozenPlan) {
        if (spec.stages().size() != 1) return false;
        LoopSpec.StageSpec stage = spec.stages().getFirst();
        if (workflow == WorkflowTemplate.LOCAL_MAINTENANCE) {
            return stage.stageKind() == StageKind.LOCAL_MAINTENANCE
                    && stage.verifiers().stream().anyMatch(verifier -> "GIT_DIFF".equals(verifier.type())
                    && Boolean.TRUE.equals(verifier.forbidDeletes()));
        }
        if (!Set.of(WorkflowTemplate.DIRECT_ARTIFACT, WorkflowTemplate.PACKAGED_ARTIFACT)
                .contains(workflow)) return false;
        if (stage.stageKind() == StageKind.DOCUMENT_AUTHORING
                || (intent == TaskIntent.DOCUMENT_AUTHORING && strategy == ExecutionStrategy.OPEN_CODE_IMPLEMENTATION)) {
            return "FROZEN".equals(state) && intent == TaskIntent.DOCUMENT_AUTHORING
                    && strategy == ExecutionStrategy.OPEN_CODE_IMPLEMENTATION
                    && stage.stageKind() == StageKind.DOCUMENT_AUTHORING
                    && stage.executionStrategy() == ExecutionStrategy.OPEN_CODE_IMPLEMENTATION
                    && stage.artifactPlanId() == null
                    && stage.verifiers().stream().anyMatch(verifier -> "DOCUMENT_STRUCTURE".equals(verifier.type())
                    && stage.deliverables().contains(verifier.path()))
                    && stage.acceptanceCriteria().stream().anyMatch(criterion ->
                    Set.of("JUDGE", "BOTH").contains(criterion.verificationMode())
                            && criterion.judgeRubric() != null && !criterion.judgeRubric().isBlank());
        }
        return stage.executionStrategy() == strategy
                && ((strategy == ExecutionStrategy.SERVER_DOCUMENT_MATERIALIZATION && stage.stageKind() == StageKind.DOCUMENT_MATERIALIZATION)
                || (strategy == ExecutionStrategy.SERVER_TABULAR_CONVERSION && stage.stageKind() == StageKind.TABULAR_CONVERSION))
                && stage.artifactPlanId() != null && frozenPlan.test(stage.artifactPlanId());
    }
}
