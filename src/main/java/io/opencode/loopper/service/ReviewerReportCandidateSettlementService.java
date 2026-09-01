package io.opencode.loopper.service;

import io.opencode.loopper.domain.GenericCandidateInternalLaunchState;
import io.opencode.loopper.domain.GenericCandidateInternalTerminationIntentState;
import io.opencode.loopper.domain.MachineCandidateRunState;
import io.opencode.loopper.persistence.AnalysisReportRow;
import io.opencode.loopper.persistence.GenericCandidateInternalLaunchRow;
import io.opencode.loopper.persistence.GenericCandidateInternalTerminationIntentRow;
import io.opencode.loopper.persistence.LoopperMapper;
import java.time.Instant;
import org.springframework.stereotype.Service;

/** Atomically settles a proven Reviewer terminal run into its authoritative report owner. */
@Service
final class ReviewerReportCandidateSettlementService {
    private final LoopperMapper mapper;
    private final MachineCandidateSubmission submissions;
    private final CandidatePromptDispatchService prompts;
    private final ReviewerReportAcceptedResultStore acceptedResults;
    private final ReviewerReportCandidateCodec codec;
    private final GenericCandidateInternalTerminationIntentStore intents;

    ReviewerReportCandidateSettlementService(
            LoopperMapper mapper, MachineCandidateSubmission submissions,
            CandidatePromptDispatchService prompts,
            ReviewerReportAcceptedResultStore acceptedResults,
            ReviewerReportCandidateCodec codec,
            GenericCandidateInternalTerminationIntentStore intents) {
        this.mapper = mapper;
        this.submissions = submissions;
        this.prompts = prompts;
        this.acceptedResults = acceptedResults;
        this.codec = codec;
        this.intents = intents;
    }

    boolean settle(AnalysisReportRow input, GenericCandidateInternalLaunchRow launch,
                   GenericCandidateInternalTerminationIntentRow intent,
                   String failureCode, String failureDetail) {
        AnalysisReportRow report = requireReport(input.designerSessionId(), input.id());
        if (!"RUNNING".equals(report.state()) && !"VALIDATING".equals(report.state())) {
            completeIntent(intent);
            return true;
        }
        requireTerminal(launch, intent, report);
        MachineCandidateSubmission.RunSnapshot run = submissions.find(launch.candidateRunId()).orElse(null);
        if (run != null && run.state() == MachineCandidateRunState.OPEN) throw stale();
        String proof = launch.externalSessionId() == null ? null : launch.terminationProof();
        if (launch.externalSessionId() != null && !CandidateSessionTerminationProof.persisted(proof)) throw stale();

        if (run != null && run.state() == MachineCandidateRunState.ACCEPTED
                && !intent.ownerCancelRequested()
                && GenericCandidateInternalTerminationPreparer.IntentKind.RUN_COMPLETED.name()
                        .equals(intent.intentKind())) {
            return settleAccepted(report, launch, intent, run, proof);
        }
        String code = failureCode == null || failureCode.isBlank()
                ? terminalCode(run) : failureCode;
        String detail = failureDetail == null || failureDetail.isBlank()
                ? terminalDetail(run) : failureDetail;
        return prompts.settleForRun(launch.candidateRunId(), proof,
                () -> settleFailedInTransaction(report, launch, intent, code, detail, proof));
    }

    private boolean settleAccepted(
            AnalysisReportRow report, GenericCandidateInternalLaunchRow launch,
            GenericCandidateInternalTerminationIntentRow intent,
            MachineCandidateSubmission.RunSnapshot run, String proof) {
        ReviewerReportAcceptedResultStore.Accepted accepted = acceptedResults.find(run.runId())
                .orElseThrow(() -> new ConflictException("REVIEWER_ACCEPTED_RESULT_MISSING",
                        "Reviewer accepted result is missing"));
        return prompts.settleForRun(run.runId(), proof, () -> {
            AnalysisReportRow current = requireReport(report.designerSessionId(), report.id());
            if ("READY".equals(current.state())) {
                completeIntent(intent);
                return;
            }
            if (!"RUNNING".equals(current.state()) || current.version() != run.ownerVersion()
                    || !run.externalSessionId().equals(current.externalSessionId())) throw stale();
            ReviewerReportCompilation.Candidate candidate = codec.requireCandidate(
                    accepted.row().canonicalCandidateJson());
            String now = Instant.now().toString();
            AnalysisReportRow ready = new AnalysisReportRow(
                    current.id(), current.designerSessionId(), current.taskProfileId(), "READY",
                    candidate.title(), accepted.row().markdown(), accepted.row().evidenceJson(),
                    accepted.row().contentSha256(), accepted.row().sourceSnapshotSha256(), null, null,
                    current.createdAt(), now, current.version(), current.externalSessionId(), proof,
                    current.sourceRequirement(), current.rolePackId(), current.rolePackVersion(),
                    current.reviewerContractVersion(), current.responseMode(),
                    accepted.row().canonicalFindingsJson(), current.deadlineAt(),
                    current.sourceRequirementRevision());
            if (mapper.updateAnalysisReport(ready) != 1) throw stale();
            acceptedResults.settle(run.runId(), accepted.row().version(), current.id(), now);
            completeIntent(intent);
        });
    }

    private void settleFailedInTransaction(
            AnalysisReportRow input, GenericCandidateInternalLaunchRow launch,
            GenericCandidateInternalTerminationIntentRow intent,
            String code, String detail, String proof) {
        AnalysisReportRow current = requireReport(input.designerSessionId(), input.id());
        if ("FAILED".equals(current.state())) {
            completeIntent(intent);
            return;
        }
        if (!"RUNNING".equals(current.state()) && !"VALIDATING".equals(current.state())) throw stale();
        String now = Instant.now().toString();
        AnalysisReportRow failed = new AnalysisReportRow(
                current.id(), current.designerSessionId(), current.taskProfileId(), "FAILED",
                current.title(), current.markdown(), current.evidenceJson(), current.contentSha256(),
                current.sourceSnapshotSha256(), safe(code), safe(detail), current.createdAt(), now,
                current.version(), current.externalSessionId(), proof == null ? launch.state() : proof,
                current.sourceRequirement(), current.rolePackId(), current.rolePackVersion(),
                current.reviewerContractVersion(), current.responseMode(), current.findingsJson(),
                current.deadlineAt(), current.sourceRequirementRevision());
        if (mapper.updateAnalysisReport(failed) != 1) throw stale();
        completeIntent(intent);
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

    private void requireTerminal(GenericCandidateInternalLaunchRow launch,
                                 GenericCandidateInternalTerminationIntentRow intent,
                                 AnalysisReportRow report) {
        if (launch == null || intent == null || !launch.id().equals(intent.launchId())
                || !launch.candidateRunId().equals(intent.candidateRunId())
                || !report.id().equals(launch.analysisReportId())
                || !GenericCandidateInternalTerminationIntentState.READY.name().equals(intent.state())
                || !GenericCandidateInternalLaunchState.valueOf(launch.state()).terminal()) throw stale();
    }

    private AnalysisReportRow requireReport(String sessionId, String reportId) {
        return mapper.findAnalysisReport(sessionId, reportId)
                .orElseThrow(ReviewerReportCandidateSettlementService::stale);
    }

    private static String terminalCode(MachineCandidateSubmission.RunSnapshot run) {
        if (run == null) return "REVIEWER_CANDIDATE_START_FAILED";
        return switch (run.state()) {
            case WAITING_INPUT -> "REVIEWER_CANDIDATE_WAITING_INPUT";
            case FALLBACK_REQUIRED -> "REVIEWER_CANDIDATE_FALLBACK_FORBIDDEN";
            case CLOSED -> switch (run.closeReason()) {
                case TIMEOUT -> "REVIEWER_TIMEOUT";
                case INTERACTION_FORBIDDEN -> "REVIEWER_CANDIDATE_INTERACTION_FORBIDDEN";
                case NORMAL_COMPLETION_ZERO_SUBMISSION -> "REVIEWER_CANDIDATE_ZERO_SUBMISSION";
                case REMOTE_FAILED, OWNER_REQUESTED -> "REVIEWER_SESSION_FAILED";
                case null -> "REVIEWER_SESSION_FAILED";
            };
            case OPEN, ACCEPTED -> "REVIEWER_CANDIDATE_TERMINAL_INVALID";
        };
    }

    private static String terminalDetail(MachineCandidateSubmission.RunSnapshot run) {
        return run == null ? "Reviewer candidate failed before opening its run"
                : "Reviewer candidate ended in " + run.state().name()
                + (run.closeReason() == null ? "" : " (" + run.closeReason().name() + ")");
    }

    private static String safe(String value) {
        String normalized = value == null ? "Reviewer candidate failed" : value.replaceAll("[\\r\\n]+", " ").trim();
        return normalized.length() <= 2_000 ? normalized : normalized.substring(0, 2_000);
    }

    private static ConflictException stale() {
        return new ConflictException("REVIEWER_CANDIDATE_SETTLEMENT_STALE",
                "Reviewer candidate termination proof, run, accepted result, or report owner changed");
    }
}
