package io.opencode.loopper.service;

import io.opencode.loopper.domain.GenericCandidateInternalLaunchState;
import io.opencode.loopper.domain.GenericCandidateInternalTerminationIntentState;
import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.domain.LifecycleMachineType;
import io.opencode.loopper.domain.LifecycleScopeType;
import io.opencode.loopper.domain.MachineCandidateRunState;
import io.opencode.loopper.domain.ProjectConventionState;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.GenericCandidateInternalLaunchRow;
import io.opencode.loopper.persistence.GenericCandidateInternalTerminationIntentRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.ProjectConventionDraftRow;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Settles one proven Convention candidate terminal into its authoritative draft. */
@Service
final class ProjectConventionCandidateSettlementService {
    private final LoopperMapper mapper;
    private final LifecycleTransitionService lifecycle;
    private final MachineCandidateSubmission submissions;
    private final CandidatePromptDispatchService prompts;
    private final ProjectConventionAcceptedResultStore acceptedResults;
    private final GenericCandidateInternalTerminationIntentStore intents;

    ProjectConventionCandidateSettlementService(
            LoopperMapper mapper, LifecycleTransitionService lifecycle,
            MachineCandidateSubmission submissions, CandidatePromptDispatchService prompts,
            ProjectConventionAcceptedResultStore acceptedResults,
            GenericCandidateInternalTerminationIntentStore intents) {
        this.mapper = mapper;
        this.lifecycle = lifecycle;
        this.submissions = submissions;
        this.prompts = prompts;
        this.acceptedResults = acceptedResults;
        this.intents = intents;
    }

    boolean settle(ProjectConventionDraftRow input, GenericCandidateInternalLaunchRow launch,
                   GenericCandidateInternalTerminationIntentRow intent,
                   String failureCode, String failureDetail) {
        ProjectConventionDraftRow draft = requireDraft(input.id());
        if (terminal(draft)) {
            completeIntent(intent);
            return true;
        }
        requireTerminal(launch, intent, draft);
        MachineCandidateSubmission.RunSnapshot run = submissions.find(launch.candidateRunId()).orElse(null);
        if (run != null && run.state() == MachineCandidateRunState.OPEN) throw stale();
        String proof = launch.externalSessionId() == null ? null : launch.terminationProof();
        if (launch.externalSessionId() != null && !CandidateSessionTerminationProof.persisted(proof)) throw stale();

        if (run != null && run.state() == MachineCandidateRunState.ACCEPTED
                && !intent.ownerCancelRequested()
                && GenericCandidateInternalTerminationPreparer.IntentKind.RUN_COMPLETED.name()
                        .equals(intent.intentKind())) {
            return settleAccepted(draft, launch, intent, run, proof);
        }
        String code = blank(failureCode) ? terminalCode(run) : failureCode;
        String detail = blank(failureDetail) ? terminalDetail(run) : failureDetail;
        return prompts.settleForRun(launch.candidateRunId(), proof,
                () -> settleFailure(draft, launch, intent, run, code, detail, proof));
    }

    private boolean settleAccepted(ProjectConventionDraftRow draft,
                                   GenericCandidateInternalLaunchRow launch,
                                   GenericCandidateInternalTerminationIntentRow intent,
                                   MachineCandidateSubmission.RunSnapshot run, String proof) {
        ProjectConventionAcceptedResultStore.Accepted accepted = acceptedResults.find(run.runId())
                .orElseThrow(() -> new ConflictException("PROJECT_CONVENTION_ACCEPTED_RESULT_MISSING",
                        "Project convention accepted result is missing"));
        return prompts.settleForRun(run.runId(), proof, () -> {
            ProjectConventionDraftRow current = requireDraft(draft.id());
            if (ProjectConventionState.READY.name().equals(current.state())) {
                completeIntent(intent);
                return;
            }
            requireRunningOwner(current, launch, run);
            ProjectConventionDraftRow ready = projection(current, ProjectConventionState.READY,
                    proof, accepted.row().proposedContent(), null);
            transition(current, ready, LifecycleEvent.COMPLETE,
                    "PROJECT_CONVENTION_CANDIDATE_ACCEPTED");
            acceptedResults.settle(run.runId(), accepted.row().version(), current.id(), now());
            completeIntent(intent);
        });
    }

    private void settleFailure(ProjectConventionDraftRow draft,
                               GenericCandidateInternalLaunchRow launch,
                               GenericCandidateInternalTerminationIntentRow intent,
                               MachineCandidateSubmission.RunSnapshot run,
                               String code, String detail, String proof) {
        ProjectConventionDraftRow current = requireDraft(draft.id());
        if (terminal(current)) {
            completeIntent(intent);
            return;
        }
        if (!ProjectConventionState.RUNNING.name().equals(current.state())) throw stale();
        if (run != null) requireRunningOwner(current, launch, run);
        String externalState = proof == null ? launch.state() : proof;
        if (intent.ownerCancelRequested()) {
            ProjectConventionDraftRow stopping = projection(current, ProjectConventionState.STOPPING,
                    externalState, null, safe(detail));
            transition(current, stopping, LifecycleEvent.CANCEL,
                    "PROJECT_CONVENTION_CANDIDATE_CANCEL_REQUESTED");
            ProjectConventionDraftRow stopped = requireDraft(current.id());
            ProjectConventionDraftRow cancelled = projection(stopped, ProjectConventionState.CANCELLED,
                    externalState, null, safe(detail));
            transition(stopped, cancelled, LifecycleEvent.COMPLETE,
                    "PROJECT_CONVENTION_CANDIDATE_CANCELLED");
        } else {
            ProjectConventionDraftRow failed = projection(current, ProjectConventionState.FAILED,
                    externalState, null, safe(code + ": " + detail));
            transition(current, failed, LifecycleEvent.FAIL,
                    blank(code) ? "PROJECT_CONVENTION_CANDIDATE_FAILED" : code);
        }
        completeIntent(intent);
    }

    private void requireRunningOwner(ProjectConventionDraftRow current,
                                     GenericCandidateInternalLaunchRow launch,
                                     MachineCandidateSubmission.RunSnapshot run) {
        if (!ProjectConventionState.RUNNING.name().equals(current.state())
                || current.version() != run.ownerVersion()
                || !run.externalSessionId().equals(current.externalSessionId())
                || !run.owner().id().equals(current.id())
                || !run.scope().id().equals(current.projectId())
                || current.sourceRevision() == null
                || current.sourceRevision() != run.sourceRevision()
                || !launch.candidateRunId().equals(run.runId())) throw stale();
    }

    private void transition(ProjectConventionDraftRow before, ProjectConventionDraftRow after,
                            LifecycleEvent event, String reason) {
        lifecycle.transition(subject(after), before.state(), after.state(), event, reason, Map.of(),
                () -> mapper.updateProjectConventionDraft(after),
                ProjectConventionCandidateSettlementService::stale);
    }

    private static ProjectConventionDraftRow projection(ProjectConventionDraftRow row,
                                                         ProjectConventionState state,
                                                         String externalState,
                                                         String proposedContent,
                                                         String errorMessage) {
        return new ProjectConventionDraftRow(row.id(), row.projectId(), state.name(),
                row.externalSessionId(), externalState, row.sourceExists(), row.sourceSha256(),
                row.sourceContent(), proposedContent, row.normalizationNotice(), errorMessage,
                row.createdAt(), now(), row.version(), row.projectStackProfileId(),
                row.stackFingerprint(), row.responseMode(), row.sourceRevision());
    }

    private void completeIntent(GenericCandidateInternalTerminationIntentRow input) {
        if (input == null) throw stale();
        GenericCandidateInternalTerminationIntentRow current = intents.require(input.id());
        GenericCandidateInternalTerminationIntentState state =
                GenericCandidateInternalTerminationIntentState.valueOf(current.state());
        if (state == GenericCandidateInternalTerminationIntentState.COMPLETED) return;
        if (state != GenericCandidateInternalTerminationIntentState.READY) throw stale();
        intents.complete(current);
    }

    private static void requireTerminal(GenericCandidateInternalLaunchRow launch,
                                        GenericCandidateInternalTerminationIntentRow intent,
                                        ProjectConventionDraftRow draft) {
        if (launch == null || intent == null || !launch.id().equals(intent.launchId())
                || !launch.candidateRunId().equals(intent.candidateRunId())
                || !draft.id().equals(launch.projectConventionDraftId())
                || !draft.projectId().equals(launch.projectId())
                || !GenericCandidateInternalTerminationIntentState.READY.name().equals(intent.state())
                || !GenericCandidateInternalLaunchState.valueOf(launch.state()).terminal()) throw stale();
    }

    private ProjectConventionDraftRow requireDraft(String id) {
        return mapper.findProjectConventionDraft(id)
                .orElseThrow(ProjectConventionCandidateSettlementService::stale);
    }

    private static LifecycleTransitionService.Subject subject(ProjectConventionDraftRow row) {
        return new LifecycleTransitionService.Subject(LifecycleMachineType.PROJECT_CONVENTION,
                row.id(), LifecycleScopeType.PROJECT, row.projectId());
    }

    private static boolean terminal(ProjectConventionDraftRow row) {
        return ProjectConventionState.READY.name().equals(row.state())
                || ProjectConventionState.FAILED.name().equals(row.state())
                || ProjectConventionState.CANCELLED.name().equals(row.state());
    }

    private static String terminalCode(MachineCandidateSubmission.RunSnapshot run) {
        if (run == null) return "PROJECT_CONVENTION_CANDIDATE_START_FAILED";
        return switch (run.state()) {
            case WAITING_INPUT -> "PROJECT_CONVENTION_CANDIDATE_WAITING_INPUT";
            case FALLBACK_REQUIRED -> "PROJECT_CONVENTION_CANDIDATE_FALLBACK_FORBIDDEN";
            case CLOSED -> switch (run.closeReason()) {
                case INTERACTION_FORBIDDEN -> "PROJECT_CONVENTION_CANDIDATE_INTERACTION_FORBIDDEN";
                case NORMAL_COMPLETION_ZERO_SUBMISSION -> "PROJECT_CONVENTION_CANDIDATE_ZERO_SUBMISSION";
                case REMOTE_FAILED, TIMEOUT, OWNER_REQUESTED -> "PROJECT_CONVENTION_SESSION_FAILED";
                case null -> "PROJECT_CONVENTION_SESSION_FAILED";
            };
            case OPEN, ACCEPTED -> "PROJECT_CONVENTION_CANDIDATE_TERMINAL_INVALID";
        };
    }

    private static String terminalDetail(MachineCandidateSubmission.RunSnapshot run) {
        return run == null ? "Convention candidate failed before opening its run"
                : "Convention candidate ended in " + run.state().name()
                + (run.closeReason() == null ? "" : " (" + run.closeReason().name() + ")");
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String safe(String value) {
        String normalized = blank(value) ? "Project convention candidate failed"
                : value.replaceAll("[\\r\\n]+", " ").trim();
        return normalized.substring(0, Math.min(2_000, normalized.length()));
    }
    private static String now() { return Instant.now().toString(); }
    private static ConflictException stale() {
        return new ConflictException("PROJECT_CONVENTION_CANDIDATE_SETTLEMENT_STALE",
                "Convention candidate termination proof, run, accepted result, or draft owner changed");
    }
}
