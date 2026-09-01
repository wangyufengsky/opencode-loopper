package io.opencode.loopper.service;

import io.opencode.loopper.domain.GenericCandidateInternalLaunchState;
import io.opencode.loopper.domain.JudgeRunState;
import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.domain.LifecycleMachineType;
import io.opencode.loopper.domain.LifecycleScopeType;
import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.domain.MachineCandidateRunState;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.GenericCandidateInternalLaunchRow;
import io.opencode.loopper.persistence.GenericCandidateInternalLaunchRunRequirementRow;
import io.opencode.loopper.persistence.GenericCandidateInternalLaunchSettlementCertificateRow;
import io.opencode.loopper.persistence.JudgeRunRow;
import io.opencode.loopper.persistence.LoopperMapper;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Atomically attaches one V57 remote, opens its exact run, and issues the settlement certificate. */
@Service
class GenericCandidateInternalLaunchSettlementService {
    private final LoopperMapper mapper;
    private final LifecycleTransitionService lifecycle;
    private final MachineCandidateSubmission submissions;

    GenericCandidateInternalLaunchSettlementService(
            LoopperMapper mapper, LifecycleTransitionService lifecycle,
            MachineCandidateSubmission submissions) {
        this.mapper = mapper;
        this.lifecycle = lifecycle;
        this.submissions = submissions;
    }

    @Transactional
    Settlement settle(String launchId) {
        GenericCandidateInternalLaunchRow launch = requireLaunch(launchId);
        if (GenericCandidateInternalLaunchState.SETTLED.name().equals(launch.state())) {
            MachineCandidateSubmission.RunSnapshot run = submissions.find(launch.candidateRunId())
                    .orElseThrow(GenericCandidateInternalLaunchSettlementService::stale);
            requireExact(launch, run, launch.settledOwnerVersion());
            return certified(launch, run);
        }
        if (!GenericCandidateInternalLaunchState.CREATED.name().equals(launch.state())
                || blank(launch.externalSessionId()) || launch.settledOwnerVersion() != null
                || !mapper.listGenericCandidateInternalLaunchCleanupRemotes(launch.id()).isEmpty()) throw stale();

        long attachedVersion = launch.preparedOwnerVersion() + 1;
        String at = Instant.now().toString();
        attachOwner(launch, at);

        MachineCandidateSubmission.RunSnapshot run = submissions.open(
                new MachineCandidateSubmission.OpenCommand(
                        launch.candidateRunId(), scope(launch), owner(launch),
                        MachineCandidateKind.valueOf(launch.candidateKind()), launch.workflowStep(),
                        launch.sourceRevision(), attachedVersion,
                        MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP,
                        launch.contractVersion(), launch.runtimeGenerationId(),
                        launch.externalSessionId(), launch.maxAttempts()));
        requireExact(launch, run, attachedVersion);
        if (run.state() != MachineCandidateRunState.OPEN || run.attemptsUsed() != 0) throw stale();

        lifecycle.transition(launchSubject(launch), launch.state(),
                GenericCandidateInternalLaunchState.SETTLED.name(), LifecycleEvent.COMPLETE,
                "GENERIC_CANDIDATE_INTERNAL_SETTLED", Map.of("ownerVersion", attachedVersion),
                () -> mapper.settleGenericCandidateInternalLaunch(
                        launch.id(), launch.version(), attachedVersion, at),
                GenericCandidateInternalLaunchSettlementService::stale);
        return certified(requireLaunch(launch.id()), run);
    }

    private void attachOwner(GenericCandidateInternalLaunchRow launch, String at) {
        switch (MachineCandidateKind.valueOf(launch.candidateKind())) {
            case REVIEWER_REPORT_V1 -> lifecycle.mutateWithoutTransition(
                    () -> mapper.attachGenericReviewerOwner(
                            launch.id(), launch.version(), launch.preparedOwnerVersion(),
                            launch.externalSessionId(), at),
                    GenericCandidateInternalLaunchSettlementService::stale);
            case PROJECT_CONVENTION_V1 -> lifecycle.mutateWithoutTransition(
                    () -> mapper.attachGenericConventionOwner(
                            launch.id(), launch.version(), launch.preparedOwnerVersion(),
                            launch.externalSessionId(), at),
                    GenericCandidateInternalLaunchSettlementService::stale);
            case JUDGE_DECISION_V1 -> {
                JudgeRunRow judge = mapper.findJudgeRun(launch.ownerId())
                        .orElseThrow(GenericCandidateInternalLaunchSettlementService::stale);
                lifecycle.transition(new LifecycleTransitionService.Subject(
                                LifecycleMachineType.JUDGE_RUN, judge.id(),
                                LifecycleScopeType.TASK, judge.taskId()),
                        judge.state(), JudgeRunState.RUNNING.name(), LifecycleEvent.START,
                        "GENERIC_CANDIDATE_JUDGE_ATTACHED", Map.of("launchId", launch.id()),
                        () -> mapper.attachGenericJudgeOwner(
                                launch.id(), launch.version(), launch.preparedOwnerVersion(),
                                launch.externalSessionId()),
                        GenericCandidateInternalLaunchSettlementService::stale);
            }
            default -> throw stale();
        }
    }

    private Settlement certified(GenericCandidateInternalLaunchRow launch,
            MachineCandidateSubmission.RunSnapshot run) {
        GenericCandidateInternalLaunchSettlementCertificateRow certificate = mapper
                .findGenericCandidateInternalLaunchSettlementCertificate(launch.id())
                .orElseThrow(GenericCandidateInternalLaunchSettlementService::stale);
        GenericCandidateInternalLaunchRunRequirementRow requirement = mapper
                .findGenericCandidateInternalLaunchRunRequirement(launch.id())
                .orElseThrow(GenericCandidateInternalLaunchSettlementService::stale);
        if (!certificate.candidateRunId().equals(run.runId())
                || !requirement.candidateRunId().equals(run.runId())
                || !certificate.launchId().equals(launch.id())
                || !requirement.launchId().equals(launch.id())
                || certificate.settledOwnerVersion() != run.ownerVersion()
                || !certificate.settledAt().equals(launch.settledAt())) throw stale();
        return new Settlement(launch, run, certificate, requirement);
    }

    private void requireExact(GenericCandidateInternalLaunchRow launch,
            MachineCandidateSubmission.RunSnapshot run, Long ownerVersion) {
        if (run == null || ownerVersion == null || !launch.candidateRunId().equals(run.runId())
                || !scope(launch).equals(run.scope()) || !owner(launch).equals(run.owner())
                || run.candidateKind() != MachineCandidateKind.valueOf(launch.candidateKind())
                || !launch.workflowStep().equals(run.workflowStep())
                || run.sourceRevision() != launch.sourceRevision()
                || run.ownerVersion() != ownerVersion
                || run.submissionChannel() != MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP
                || !launch.contractVersion().equals(run.contractVersion())
                || !launch.runtimeGenerationId().equals(run.runtimeGenerationId())
                || !launch.externalSessionId().equals(run.externalSessionId())
                || run.maxAttempts() != launch.maxAttempts()) throw stale();
    }

    private GenericCandidateInternalLaunchRow requireLaunch(String launchId) {
        return mapper.findGenericCandidateInternalLaunch(launchId)
                .orElseThrow(GenericCandidateInternalLaunchSettlementService::stale);
    }

    static MachineCandidateSubmission.CandidateScope scope(GenericCandidateInternalLaunchRow launch) {
        if (launch.designerSessionId() != null) {
            return MachineCandidateSubmission.CandidateScope.designerSession(launch.designerSessionId());
        }
        if (launch.taskId() != null) return MachineCandidateSubmission.CandidateScope.task(launch.taskId());
        if (launch.projectId() != null) return MachineCandidateSubmission.CandidateScope.project(launch.projectId());
        throw stale();
    }

    static MachineCandidateSubmission.CandidateOwnerRef owner(GenericCandidateInternalLaunchRow launch) {
        return switch (launch.ownerType()) {
            case "ANALYSIS_REPORT" -> MachineCandidateSubmission.CandidateOwnerRef
                    .analysisReport(launch.ownerId());
            case "PROJECT_CONVENTION_DRAFT" -> MachineCandidateSubmission.CandidateOwnerRef
                    .projectConventionDraft(launch.ownerId());
            case "JUDGE_RUN" -> MachineCandidateSubmission.CandidateOwnerRef.judgeRun(launch.ownerId());
            default -> throw stale();
        };
    }

    private static LifecycleTransitionService.Subject launchSubject(GenericCandidateInternalLaunchRow launch) {
        LifecycleScopeType type = launch.designerSessionId() != null ? LifecycleScopeType.DESIGNER
                : launch.taskId() != null ? LifecycleScopeType.TASK : LifecycleScopeType.PROJECT;
        String id = launch.designerSessionId() != null ? launch.designerSessionId()
                : launch.taskId() != null ? launch.taskId() : launch.projectId();
        return new LifecycleTransitionService.Subject(
                LifecycleMachineType.GENERIC_CANDIDATE_INTERNAL_LAUNCH, launch.id(), type, id);
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static ConflictException stale() {
        return new ConflictException("GENERIC_CANDIDATE_INTERNAL_SETTLEMENT_STALE",
                "通用候选 internal launch、owner、run 或结算证书已变化");
    }

    record Settlement(
            GenericCandidateInternalLaunchRow launch,
            MachineCandidateSubmission.RunSnapshot run,
            GenericCandidateInternalLaunchSettlementCertificateRow certificate,
            GenericCandidateInternalLaunchRunRequirementRow requirement) { }
}
