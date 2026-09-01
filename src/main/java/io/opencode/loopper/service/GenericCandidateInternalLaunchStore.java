package io.opencode.loopper.service;

import io.opencode.loopper.domain.LifecycleMachineType;
import io.opencode.loopper.domain.LifecycleScopeType;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.GenericCandidateInternalLaunchRow;
import io.opencode.loopper.persistence.LoopperGenericCandidateLaunchMapper;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Durable PREPARED checkpoint store for V57 generic launches. */
@Component
class GenericCandidateInternalLaunchStore {
    private final LoopperGenericCandidateLaunchMapper mapper;
    private final LifecycleTransitionService lifecycle;

    GenericCandidateInternalLaunchStore(
            @Qualifier("loopperGenericCandidateLaunchMapper") LoopperGenericCandidateLaunchMapper mapper,
            LifecycleTransitionService lifecycle) {
        this.mapper = mapper;
        this.lifecycle = lifecycle;
    }

    Optional<GenericCandidateInternalLaunchRow> findActive(
            MachineCandidateSubmission.CandidateOwnerRef owner, String workflowStep) {
        return mapper.findActiveGenericCandidateInternalLaunchForOwner(
                owner.type().name(), owner.id(), workflowStep);
    }

    Optional<GenericCandidateInternalLaunchRow> findForRun(String runId) {
        return mapper.findGenericCandidateInternalLaunchForRun(runId);
    }

    @Transactional
    GenericCandidateInternalLaunchRow insert(GenericCandidateInternalLaunchRow row) {
        lifecycle.create(subject(row), row.state(), Map.of("candidateKind", row.candidateKind()),
                () -> mapper.insertGenericCandidateInternalLaunch(row),
                GenericCandidateInternalLaunchStore::conflict);
        return mapper.findGenericCandidateInternalLaunch(row.id()).orElseThrow(
                GenericCandidateInternalLaunchStore::conflict);
    }

    private static LifecycleTransitionService.Subject subject(GenericCandidateInternalLaunchRow row) {
        LifecycleScopeType type = row.designerSessionId() != null ? LifecycleScopeType.DESIGNER
                : row.taskId() != null ? LifecycleScopeType.TASK : LifecycleScopeType.PROJECT;
        String id = row.designerSessionId() != null ? row.designerSessionId()
                : row.taskId() != null ? row.taskId() : row.projectId();
        return new LifecycleTransitionService.Subject(
                LifecycleMachineType.GENERIC_CANDIDATE_INTERNAL_LAUNCH, row.id(), type, id);
    }

    private static ConflictException conflict() {
        return new ConflictException("GENERIC_CANDIDATE_INTERNAL_LAUNCH_CREATE_CONFLICT",
                "通用候选 internal launch 无法冻结");
    }
}
