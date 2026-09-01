package io.opencode.loopper.service;

import io.opencode.loopper.domain.AcceptanceCandidateInternalLaunchState;
import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.domain.LifecycleMachineType;
import io.opencode.loopper.domain.LifecycleScopeType;
import io.opencode.loopper.domain.LoopSpecCompilationState;
import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.domain.MachineCandidateRunState;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.AcceptanceCandidateInternalLaunchRow;
import io.opencode.loopper.persistence.LoopSpecCompilationRow;
import io.opencode.loopper.persistence.LoopperMapper;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Atomically transfers one CREATED internal remote to its frozen candidate run. */
@Service
class AcceptanceCandidateInternalLaunchSettlementService {
    private static final int MAX_ATTEMPTS = 2;
    private final LoopperMapper mapper;
    private final LifecycleTransitionService lifecycle;
    private final MachineCandidateSubmission submissions;

    AcceptanceCandidateInternalLaunchSettlementService(
            LoopperMapper mapper, LifecycleTransitionService lifecycle,
            MachineCandidateSubmission submissions) {
        this.mapper = mapper;
        this.lifecycle = lifecycle;
        this.submissions = submissions;
    }

    @Transactional
    Settlement settle(String launchId) {
        AcceptanceCandidateInternalLaunchRow launch = mapper
                .findAcceptanceCandidateInternalLaunch(launchId)
                .orElseThrow(AcceptanceCandidateInternalLaunchSettlementService::stale);
        if (AcceptanceCandidateInternalLaunchState.SETTLED.name().equals(launch.state())) {
            MachineCandidateSubmission.RunSnapshot run = submissions.find(launch.candidateRunId())
                    .orElseThrow(AcceptanceCandidateInternalLaunchSettlementService::stale);
            requireExact(launch, run, launch.settledOwnerVersion());
            return new Settlement(launch, run);
        }
        if (!AcceptanceCandidateInternalLaunchState.CREATED.name().equals(launch.state())
                || blank(launch.externalSessionId())
                || launch.settledOwnerVersion() != null
                || !mapper.listAcceptanceCandidateInternalLaunchCleanupRemotes(launch.id()).isEmpty()) {
            throw stale();
        }
        LoopSpecCompilationRow owner = mapper.findLoopSpecCompilation(launch.compilationId())
                .orElseThrow(AcceptanceCandidateInternalLaunchSettlementService::stale);
        if (!LoopSpecCompilationState.PENDING_HANDOFF.name().equals(owner.state())
                || owner.version() != launch.preparedOwnerVersion()
                || owner.externalSessionId() != null
                || !owner.designerSessionId().equals(launch.designerSessionId())
                || !owner.workPackageId().equals(launch.workPackageId())
                || owner.designRevision() != launch.sourceDesignRevision()
                || !owner.sourceDesignMessageId().equals(launch.sourceDesignMessageId())
                || owner.sourceDraftVersion() != launch.sourceDraftVersion()) throw stale();

        long attachedVersion = launch.preparedOwnerVersion() + 1;
        String at = Instant.now().toString();
        lifecycle.transition(compilationSubject(owner), owner.state(), LoopSpecCompilationState.RUNNING.name(),
                LifecycleEvent.DISPATCH, "ACCEPTANCE_INTERNAL_CANDIDATE_ATTACHED",
                Map.of("launchId", launch.id()),
                () -> mapper.advanceAcceptanceCandidateCompilationForInternalLaunch(
                        launch.id(), launch.version(), launch.preparedOwnerVersion(),
                        launch.externalSessionId(), at),
                AcceptanceCandidateInternalLaunchSettlementService::stale);

        MachineCandidateSubmission.RunSnapshot run = submissions.open(
                new MachineCandidateSubmission.OpenCommand(
                        launch.candidateRunId(),
                        MachineCandidateSubmission.CandidateScope.designerSession(launch.designerSessionId()),
                        MachineCandidateSubmission.CandidateOwnerRef.loopSpecCompilation(launch.compilationId()),
                        MachineCandidateKind.ACCEPTANCE_CLOSED_CHOICE_V7, launch.workflowStep(),
                        launch.sourceDesignRevision(), attachedVersion,
                        MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP,
                        launch.contractVersion(), launch.runtimeGenerationId(),
                        launch.externalSessionId(), MAX_ATTEMPTS));
        requireExact(launch, run, attachedVersion);
        if (run.state() != MachineCandidateRunState.OPEN || run.attemptsUsed() != 0) throw stale();

        lifecycle.transition(launchSubject(launch), launch.state(),
                AcceptanceCandidateInternalLaunchState.SETTLED.name(), LifecycleEvent.COMPLETE,
                "ACCEPTANCE_INTERNAL_CANDIDATE_SETTLED", Map.of("ownerVersion", attachedVersion),
                () -> mapper.settleAcceptanceCandidateInternalLaunch(
                        launch.id(), launch.version(), attachedVersion, at),
                AcceptanceCandidateInternalLaunchSettlementService::stale);
        AcceptanceCandidateInternalLaunchRow settled = mapper
                .findAcceptanceCandidateInternalLaunch(launch.id())
                .orElseThrow(AcceptanceCandidateInternalLaunchSettlementService::stale);
        return new Settlement(settled, run);
    }

    private void requireExact(AcceptanceCandidateInternalLaunchRow launch,
            MachineCandidateSubmission.RunSnapshot run, Long ownerVersion) {
        if (run == null || ownerVersion == null
                || !launch.candidateRunId().equals(run.runId())
                || run.scope().type() != MachineCandidateSubmission.CandidateScopeType.DESIGNER_SESSION
                || !launch.designerSessionId().equals(run.scope().id())
                || run.owner().type() != MachineCandidateSubmission.CandidateOwnerType.LOOP_SPEC_COMPILATION
                || !launch.compilationId().equals(run.owner().id())
                || run.candidateKind() != MachineCandidateKind.ACCEPTANCE_CLOSED_CHOICE_V7
                || !launch.workflowStep().equals(run.workflowStep())
                || run.sourceRevision() != launch.sourceDesignRevision()
                || run.ownerVersion() != ownerVersion
                || run.submissionChannel() != MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP
                || !launch.contractVersion().equals(run.contractVersion())
                || !launch.runtimeGenerationId().equals(run.runtimeGenerationId())
                || !launch.externalSessionId().equals(run.externalSessionId())
                || run.maxAttempts() != MAX_ATTEMPTS) throw stale();
    }

    private LifecycleTransitionService.Subject compilationSubject(LoopSpecCompilationRow owner) {
        String projectId = mapper.findDesignerSession(owner.designerSessionId())
                .orElseThrow(AcceptanceCandidateInternalLaunchSettlementService::stale).projectId();
        return new LifecycleTransitionService.Subject(LifecycleMachineType.LOOPSPEC_COMPILATION,
                owner.id(), LifecycleScopeType.PROJECT, projectId);
    }

    private LifecycleTransitionService.Subject launchSubject(AcceptanceCandidateInternalLaunchRow launch) {
        return new LifecycleTransitionService.Subject(
                LifecycleMachineType.ACCEPTANCE_CANDIDATE_INTERNAL_LAUNCH,
                launch.id(), LifecycleScopeType.DESIGNER, launch.designerSessionId());
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static ConflictException stale() {
        return new ConflictException("ACCEPTANCE_INTERNAL_SETTLEMENT_STALE",
                "验收内部 MCP 启动、owner 或候选运行已变化");
    }

    record Settlement(AcceptanceCandidateInternalLaunchRow launch,
                      MachineCandidateSubmission.RunSnapshot run) { }
}
