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
        if (spec.stages().size() != 1) return false;
        LoopSpec.StageSpec stage = spec.stages().getFirst();
        if (profile.workflowTemplate() == WorkflowTemplate.LOCAL_MAINTENANCE) {
            return stage.stageKind() == StageKind.LOCAL_MAINTENANCE
                    && stage.verifiers().stream().anyMatch(verifier -> "GIT_DIFF".equals(verifier.type())
                    && Boolean.TRUE.equals(verifier.forbidDeletes()));
        }
        if (!Set.of(WorkflowTemplate.DIRECT_ARTIFACT, WorkflowTemplate.PACKAGED_ARTIFACT)
                .contains(profile.workflowTemplate())) return false;
        if (stage.stageKind() == StageKind.DOCUMENT_AUTHORING) {
            return "FROZEN".equals(profile.state()) && profile.intent() == TaskIntent.DOCUMENT_AUTHORING
                    && profile.executionStrategy() == ExecutionStrategy.OPEN_CODE_IMPLEMENTATION
                    && stage.executionStrategy() == ExecutionStrategy.OPEN_CODE_IMPLEMENTATION
                    && stage.artifactPlanId() == null
                    && stage.verifiers().stream().anyMatch(verifier -> "DOCUMENT_STRUCTURE".equals(verifier.type())
                    && stage.deliverables().contains(verifier.path()))
                    && stage.acceptanceCriteria().stream().anyMatch(criterion ->
                    Set.of("JUDGE", "BOTH").contains(criterion.verificationMode())
                            && criterion.judgeRubric() != null && !criterion.judgeRubric().isBlank());
        }
        return stage.artifactPlanId() != null && frozenPlan.test(stage.artifactPlanId());
    }
}
