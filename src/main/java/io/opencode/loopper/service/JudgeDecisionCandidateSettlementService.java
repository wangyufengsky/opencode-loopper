package io.opencode.loopper.service;

import io.opencode.loopper.domain.GenericCandidateInternalLaunchState;
import io.opencode.loopper.domain.GenericCandidateInternalTerminationIntentState;
import io.opencode.loopper.domain.JudgeRunState;
import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.domain.LifecycleMachineType;
import io.opencode.loopper.domain.LifecycleScopeType;
import io.opencode.loopper.domain.MachineCandidateRunState;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.GenericCandidateInternalLaunchRow;
import io.opencode.loopper.persistence.GenericCandidateInternalTerminationIntentRow;
import io.opencode.loopper.persistence.JudgeRunRow;
import io.opencode.loopper.persistence.LoopperMapper;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Atomically settles one proven terminal candidate run into its authoritative Judge owner. */
@Service
class JudgeDecisionCandidateSettlementService {
    private final LoopperMapper mapper;
    private final LifecycleTransitionService lifecycle;
    private final MachineCandidateSubmission submissions;
    private final JudgeDecisionAcceptedResultStore acceptedResults;
    private final GenericCandidateInternalTerminationIntentStore intents;

    JudgeDecisionCandidateSettlementService(
            LoopperMapper mapper, LifecycleTransitionService lifecycle,
            MachineCandidateSubmission submissions, JudgeDecisionAcceptedResultStore acceptedResults,
            GenericCandidateInternalTerminationIntentStore intents) {
        this.mapper = mapper;
        this.lifecycle = lifecycle;
        this.submissions = submissions;
        this.acceptedResults = acceptedResults;
        this.intents = intents;
    }

    @Transactional
    Outcome failBeforeRemote(JudgeRunRow input, String code, String detail, boolean replacement) {
        JudgeRunRow judge = requireJudge(input.id());
        if (terminal(judge)) return outcome(judge);
        if (!JudgeRunState.CREATING.name().equals(judge.state()) || judge.externalSessionId() != null) {
            throw stale();
        }
        JudgeRunState target = replacement ? JudgeRunState.ABORTED : JudgeRunState.SESSION_ERROR;
        JudgeRunRow settled = copy(judge, target, null, safe(code + ": " + detail), null);
        transition(judge, settled, replacement ? LifecycleEvent.ABORT : LifecycleEvent.SESSION_FAIL, code);
        return outcome(settled);
    }

    @Transactional
    Outcome settle(GenericCandidateInternalLaunchRow launch,
                   GenericCandidateInternalTerminationIntentRow inputIntent,
                   String failureCode, String failureDetail) {
        GenericCandidateInternalTerminationIntentRow intent = intents.require(inputIntent.id());
        JudgeRunRow judge = requireJudge(launch.judgeRunId());
        if (terminal(judge)) {
            completeIntent(intent);
            return outcome(judge);
        }
        requireTerminal(launch, intent, judge);
        MachineCandidateSubmission.RunSnapshot run = submissions.find(launch.candidateRunId()).orElse(null);
        if (run != null && run.state() == MachineCandidateRunState.OPEN) throw stale();
        if (run != null && run.state() == MachineCandidateRunState.ACCEPTED
                && !intent.ownerCancelRequested()
                && GenericCandidateInternalTerminationPreparer.IntentKind.RUN_COMPLETED.name()
                        .equals(intent.intentKind())) {
            return settleAccepted(judge, launch, intent, run);
        }
        boolean candidateWaitingInput = run != null && run.state() == MachineCandidateRunState.WAITING_INPUT;
        JudgeRunState target = intent.ownerCancelRequested()
                || GenericCandidateInternalTerminationPreparer.IntentKind.OWNER_REPLACEMENT.name()
                        .equals(intent.intentKind())
                || candidateWaitingInput ? JudgeRunState.ABORTED : JudgeRunState.SESSION_ERROR;
        String code = failureCode == null || failureCode.isBlank() ? terminalCode(run) : failureCode;
        String detail = failureDetail == null || failureDetail.isBlank()
                ? "Judge candidate ended without an accepted decision" : failureDetail;
        JudgeRunRow settled = copy(judge, target, null, safe(code + ": " + detail), null);
        transition(judge, settled, target == JudgeRunState.ABORTED
                ? LifecycleEvent.ABORT : LifecycleEvent.SESSION_FAIL, code);
        completeIntent(intent);
        return outcome(settled);
    }

    private Outcome settleAccepted(
            JudgeRunRow judge, GenericCandidateInternalLaunchRow launch,
            GenericCandidateInternalTerminationIntentRow intent,
            MachineCandidateSubmission.RunSnapshot run) {
        if (!CandidateSessionTerminationProof.persisted(launch.terminationProof())) throw stale();
        JudgeDecisionAcceptedResultStore.Accepted accepted = acceptedResults.require(run.runId());
        if (!judge.id().equals(accepted.row().judgeRunId())
                || !judge.reviewBatchId().equals(accepted.row().reviewBatchId())
                || !judge.role().equals(accepted.row().role())
                || judge.sourceRevision() == null
                || judge.sourceRevision() != accepted.row().sourceRevision()
                || judge.version() != run.ownerVersion()) throw stale();
        JudgeRunRow completed = copy(judge, JudgeRunState.COMPLETED,
                accepted.row().verdict(), accepted.row().reason(), accepted.row().canonicalDecisionJson());
        transition(judge, completed, LifecycleEvent.COMPLETE, "JUDGE_CANDIDATE_ACCEPTED");
        if (mapper.settleJudgeCandidateAcceptedResult(
                run.runId(), accepted.row().version(), judge.id(), Instant.now().toString()) != 1) throw stale();
        completeIntent(intent);
        return Outcome.completed(completed);
    }

    private void transition(JudgeRunRow current, JudgeRunRow next, LifecycleEvent event, String code) {
        lifecycle.transition(new LifecycleTransitionService.Subject(
                        LifecycleMachineType.JUDGE_RUN, current.id(),
                        LifecycleScopeType.TASK, current.taskId()),
                current.state(), next.state(), event, code, Map.of("role", current.role()),
                () -> mapper.updateJudgeRun(next), JudgeDecisionCandidateSettlementService::stale);
    }

    private JudgeRunRow copy(JudgeRunRow row, JudgeRunState state,
                             String verdict, String reason, String rawOutput) {
        return new JudgeRunRow(row.id(), row.taskId(), row.attemptId(), row.role(), row.ordinal(),
                row.externalSessionId(), state.name(), verdict, reason, rawOutput,
                row.createdAt(), Instant.now().toString(), row.version(), row.responseMode(),
                row.responseSchemaId(), row.reviewBatchId(), row.sourceRevision());
    }

    private void requireTerminal(GenericCandidateInternalLaunchRow launch,
                                 GenericCandidateInternalTerminationIntentRow intent,
                                 JudgeRunRow judge) {
        if (launch == null || intent == null || !launch.id().equals(intent.launchId())
                || !launch.candidateRunId().equals(intent.candidateRunId())
                || !judge.id().equals(launch.judgeRunId())
                || !GenericCandidateInternalTerminationIntentState.READY.name().equals(intent.state())
                || !GenericCandidateInternalLaunchState.valueOf(launch.state()).terminal()) throw stale();
    }

    private void completeIntent(GenericCandidateInternalTerminationIntentRow input) {
        GenericCandidateInternalTerminationIntentRow current = intents.require(input.id());
        if (GenericCandidateInternalTerminationIntentState.COMPLETED.name().equals(current.state())) return;
        if (!GenericCandidateInternalTerminationIntentState.READY.name().equals(current.state())) throw stale();
        intents.complete(current);
    }

    private JudgeRunRow requireJudge(String id) {
        return mapper.findJudgeRun(id).orElseThrow(JudgeDecisionCandidateSettlementService::stale);
    }
    private static boolean terminal(JudgeRunRow row) {
        return JudgeRunState.valueOf(row.state()).terminal();
    }
    private static Outcome outcome(JudgeRunRow row) {
        return switch (JudgeRunState.valueOf(row.state())) {
            case COMPLETED -> Outcome.completed(row);
            case SESSION_ERROR -> Outcome.failed(row);
            case ABORTED -> Outcome.aborted(row);
            default -> Outcome.running(row);
        };
    }
    private static String terminalCode(MachineCandidateSubmission.RunSnapshot run) {
        if (run == null) return "JUDGE_CANDIDATE_START_FAILED";
        return switch (run.state()) {
            case WAITING_INPUT -> "JUDGE_CANDIDATE_WAITING_INPUT";
            case FALLBACK_REQUIRED -> "JUDGE_CANDIDATE_FALLBACK_FORBIDDEN";
            case CLOSED -> run.closeReason() == null ? "JUDGE_CANDIDATE_FAILED"
                    : "JUDGE_CANDIDATE_" + run.closeReason().name();
            case OPEN, ACCEPTED -> "JUDGE_CANDIDATE_TERMINAL_INVALID";
        };
    }
    private static String safe(String value) {
        String normalized = value == null ? "Judge candidate failed" : value.replaceAll("[\r\n]+", " ").trim();
        return normalized.substring(0, Math.min(normalized.length(), 4_000));
    }
    private static ConflictException stale() {
        return new ConflictException("JUDGE_CANDIDATE_SETTLEMENT_STALE",
                "Judge candidate termination proof, run, accepted result, or owner changed");
    }

    enum Status { RUNNING, COMPLETED, SESSION_ERROR, ABORTED }
    record Outcome(Status status, JudgeRunRow judge) {
        static Outcome running(JudgeRunRow row) { return new Outcome(Status.RUNNING, row); }
        static Outcome completed(JudgeRunRow row) { return new Outcome(Status.COMPLETED, row); }
        static Outcome failed(JudgeRunRow row) { return new Outcome(Status.SESSION_ERROR, row); }
        static Outcome aborted(JudgeRunRow row) { return new Outcome(Status.ABORTED, row); }
    }
}
