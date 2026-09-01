package io.opencode.loopper.service;

import io.opencode.loopper.domain.GenericCandidateInternalLaunchCleanupState;
import io.opencode.loopper.domain.GenericCandidateInternalLaunchState;
import io.opencode.loopper.domain.GenericCandidateInternalTerminationIntentState;
import io.opencode.loopper.domain.MachineCandidateRunState;
import io.opencode.loopper.persistence.AnalysisReportRow;
import io.opencode.loopper.persistence.GenericCandidateInternalLaunchRow;
import io.opencode.loopper.persistence.GenericCandidateInternalTerminationIntentRow;
import io.opencode.loopper.persistence.LoopperMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Gives Designer cancellation priority while preserving one Generic termination evidence chain. */
@Component
final class GenericCandidateDesignerTerminationWorkflow {
    private final LoopperMapper mapper;
    private final GenericCandidateInternalTerminationPreparer preparer;
    private final GenericCandidateInternalTerminationCoordinator coordinator;
    private final GenericCandidateInternalTerminationIntentStore intents;
    private final GenericCandidateInternalLaunchCleanupLedger cleanup;
    private final MachineCandidateSubmission submissions;
    private final ReviewerReportCandidateSettlementService reviewerSettlement;

    GenericCandidateDesignerTerminationWorkflow(
            LoopperMapper mapper,
            GenericCandidateInternalTerminationPreparer preparer,
            GenericCandidateInternalTerminationCoordinator coordinator,
            GenericCandidateInternalTerminationIntentStore intents,
            GenericCandidateInternalLaunchCleanupLedger cleanup,
            MachineCandidateSubmission submissions,
            ReviewerReportCandidateSettlementService reviewerSettlement) {
        this.mapper = mapper;
        this.preparer = preparer;
        this.coordinator = coordinator;
        this.intents = intents;
        this.cleanup = cleanup;
        this.submissions = submissions;
        this.reviewerSettlement = reviewerSettlement;
    }

    Batch requestDesignerCancellation(String designerSessionId, boolean archiveWhenComplete) {
        Map<String, String> proofs = new LinkedHashMap<>();
        int stopped = 0;
        int failed = 0;
        for (GenericCandidateInternalLaunchRow original
                : mapper.listGenericCandidateInternalLaunchesForDesigner(designerSessionId)) {
            try {
                GenericCandidateInternalLaunchRow launch = requireLaunch(original.id());
                GenericCandidateInternalTerminationIntentRow intent = intents.findForLaunch(launch.id()).orElse(null);
                if (!state(launch).terminal()) {
                    if (intent == null) {
                        intent = preparer.prepare(new GenericCandidateInternalTerminationPreparer.PrepareCommand(
                                launch.id(), GenericCandidateInternalTerminationPreparer.IntentKind.OWNER_CANCEL,
                                "DESIGNER_CANCELLED"));
                    } else {
                        intent = intents.requestOwnerCancel(intent, archiveWhenComplete);
                    }
                    GenericCandidateInternalTerminationCoordinator.Result advanced = coordinator.advance(intent.id());
                    if (advanced.status() != GenericCandidateInternalTerminationCoordinator.Status.READY) {
                        failed++;
                        continue;
                    }
                    launch = requireLaunch(launch.id());
                    intent = intents.require(intent.id());
                    stopped += launch.externalSessionId() == null ? 0 : 1;
                } else if (intent != null && !intent.ownerCancelRequested()
                        && !GenericCandidateInternalTerminationIntentState.COMPLETED.name().equals(intent.state())) {
                    intent = intents.requestOwnerCancel(intent, archiveWhenComplete);
                }
                closeOpenRun(launch);
                if (intent != null && !GenericCandidateInternalTerminationIntentState.COMPLETED.name()
                        .equals(intent.state())) {
                    AnalysisReportRow report = mapper.findAnalysisReport(
                            launch.designerSessionId(), launch.analysisReportId()).orElseThrow(
                            () -> stale("Reviewer owner is missing during Designer cancellation"));
                    if (!reviewerSettlement.settle(report, launch, intents.require(intent.id()),
                            "DESIGNER_CANCELLED", "Designer session was cancelled")) {
                        failed++;
                        continue;
                    }
                }
                launch = requireLaunch(launch.id());
                if (!state(launch).terminal() || hasUnstoppedCleanup(launch.id())) {
                    failed++;
                    continue;
                }
                if (launch.externalSessionId() != null) {
                    if (!CandidateSessionTerminationProof.persisted(launch.terminationProof())) {
                        failed++;
                        continue;
                    }
                    proofs.put(launch.externalSessionId(), launch.terminationProof());
                }
            } catch (RuntimeException unsafe) {
                failed++;
            }
        }
        return new Batch(Map.copyOf(proofs), stopped, failed);
    }

    boolean hasActiveWriters(String designerSessionId) {
        return mapper.listGenericCandidateInternalLaunchesForDesigner(designerSessionId).stream()
                .anyMatch(launch -> !state(launch).terminal()
                        || hasUnstoppedCleanup(launch.id())
                        || intents.findForLaunch(launch.id()).stream().anyMatch(intent ->
                        !GenericCandidateInternalTerminationIntentState.COMPLETED.name().equals(intent.state())));
    }

    boolean ownsExternalSession(String externalSessionId) {
        return externalSessionId != null && intents.ownsExternalSession(externalSessionId);
    }

    boolean archiveRequested(String designerSessionId) {
        return mapper.listGenericCandidateInternalLaunchesForDesigner(designerSessionId).stream()
                .map(launch -> intents.findForLaunch(launch.id()).orElse(null))
                .anyMatch(intent -> intent != null && intent.ownerCancelRequested()
                        && intent.archiveWhenComplete());
    }

    private void closeOpenRun(GenericCandidateInternalLaunchRow launch) {
        MachineCandidateSubmission.RunSnapshot run = submissions.find(launch.candidateRunId()).orElse(null);
        if (run != null && run.state() == MachineCandidateRunState.OPEN) {
            submissions.close(new MachineCandidateSubmission.CloseCommand(
                    run.runId(), run.version(), MachineCandidateSubmission.CandidateCloseReason.OWNER_REQUESTED));
        }
    }

    private boolean hasUnstoppedCleanup(String launchId) {
        return cleanup.list(launchId).stream().anyMatch(row ->
                !GenericCandidateInternalLaunchCleanupState.STOPPED.name().equals(row.state()));
    }

    private GenericCandidateInternalLaunchRow requireLaunch(String launchId) {
        return mapper.findGenericCandidateInternalLaunch(launchId)
                .orElseThrow(() -> stale("Generic launch is missing during Designer cancellation"));
    }

    private static GenericCandidateInternalLaunchState state(GenericCandidateInternalLaunchRow launch) {
        return GenericCandidateInternalLaunchState.valueOf(launch.state());
    }

    private static ConflictException stale(String detail) {
        return new ConflictException("GENERIC_CANDIDATE_DESIGNER_CANCEL_STALE", detail);
    }

    record Batch(Map<String, String> proofs, int stoppedSessions, int failedSessions) {
        boolean ready() { return failedSessions == 0; }
    }
}
