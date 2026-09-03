package io.opencode.loopper.service;

import io.opencode.loopper.domain.GenericCandidateInternalLaunchState;
import io.opencode.loopper.domain.GenericCandidateInternalTerminationIntentState;
import io.opencode.loopper.domain.JudgeRunState;
import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.domain.MachineCandidateRunState;
import io.opencode.loopper.domain.SessionFailure;
import io.opencode.loopper.persistence.GenericCandidateInternalLaunchRow;
import io.opencode.loopper.persistence.GenericCandidateInternalTerminationIntentRow;
import io.opencode.loopper.persistence.JudgeCandidateSourceSnapshotRow;
import io.opencode.loopper.persistence.JudgeReviewBatchRow;
import io.opencode.loopper.persistence.JudgeRunRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/** Drives one durable JUDGE_DECISION_V1 launch without reading model final text. */
@Component
final class JudgeDecisionCandidateWorkflow {
    static final String RESPONSE_MODE = "INTERNAL_MCP";
    private static final String CAPABILITY_UNAVAILABLE = "JUDGE_CANDIDATE_CAPABILITY_UNAVAILABLE";

    private final LoopperMapper mapper;
    private final GenericCandidateInternalLaunchPreparer preparer;
    private final GenericCandidateInternalLaunchCoordinator launches;
    private final GenericCandidateInternalTerminationPreparer terminationPreparer;
    private final GenericCandidateInternalTerminationCoordinator terminations;
    private final GenericCandidateInternalTerminationIntentStore intents;
    private final MachineCandidateSubmission submissions;
    private final CandidatePromptDispatchService promptDispatches;
    private final JudgeDecisionCandidateSourceSnapshotStore snapshots;
    private final JudgeDecisionCandidateSettlementService settlements;
    private final JudgeDecisionCandidateCodec codec;
    private final OpenCodeClient openCode;
    private final DesignerAttachmentContext attachments;
    private final CandidatePromptDispatchService.PromptIo promptIo;
    private final JudgeDecisionCandidatePromptFactory promptFactory =
            new JudgeDecisionCandidatePromptFactory();

    JudgeDecisionCandidateWorkflow(
            LoopperMapper mapper, GenericCandidateInternalLaunchPreparer preparer,
            GenericCandidateInternalLaunchCoordinator launches,
            GenericCandidateInternalTerminationPreparer terminationPreparer,
            GenericCandidateInternalTerminationCoordinator terminations,
            GenericCandidateInternalTerminationIntentStore intents,
            MachineCandidateSubmission submissions, CandidatePromptDispatchService promptDispatches,
            JudgeDecisionCandidateSourceSnapshotStore snapshots,
            JudgeDecisionCandidateSettlementService settlements,
            JudgeDecisionCandidateCodec codec, OpenCodeClient openCode,
            DesignerAttachmentContext attachments) {
        this.mapper = mapper;
        this.preparer = preparer;
        this.launches = launches;
        this.terminationPreparer = terminationPreparer;
        this.terminations = terminations;
        this.intents = intents;
        this.submissions = submissions;
        this.promptDispatches = promptDispatches;
        this.snapshots = snapshots;
        this.settlements = settlements;
        this.codec = codec;
        this.openCode = openCode;
        this.attachments = attachments;
        this.promptIo = new CandidatePromptDispatchService.PromptIo() {
            @Override public OpenCodeClient.MessageLookup lookup(
                    OpenCodeClient.OpenCodeSession remote,
                    OpenCodeClient.PromptRequest request, String sha256) {
                OpenCodeClient.MessageLookup lookup = openCode.findPromptMessage(remote, request, sha256);
                if (!lookup.supported()) throw new SessionFailure(
                        "OPENCODE_PROMPT_LOOKUP_UNAVAILABLE",
                        "OpenCode cannot recover a deterministic Judge prompt acknowledgement");
                if (lookup.exists() && !sha256.equals(lookup.verifiedRequestSha256())) {
                    throw new SessionFailure("OPENCODE_PROMPT_REQUEST_STALE",
                            "Judge prompt acknowledgement does not match the frozen request");
                }
                return lookup;
            }
            @Override public void dispatch(OpenCodeClient.OpenCodeSession remote,
                                           OpenCodeClient.PromptRequest request) {
                openCode.promptAsync(remote, request);
            }
        };
    }

    boolean owns(JudgeRunRow judge) {
        return judge != null && (RESPONSE_MODE.equals(judge.responseMode())
                || mapper.findGenericCandidateInternalLaunchForJudgeRun(judge.id()).isPresent());
    }

    Result advance(Context context) {
        JudgeRunRow judge = current(context.judge());
        if (JudgeRunState.valueOf(judge.state()).terminal()) return resultFor(judge);
        GenericCandidateInternalLaunchRow launch = mapper
                .findGenericCandidateInternalLaunchForJudgeRun(judge.id()).orElse(null);
        if (launch == null) {
            GenericCandidateInternalLaunchPreparer.PrepareCommand command = command(context, judge);
            try {
                String plannedRunId = GenericCandidateInternalLaunchPreparer.candidateRunId(command);
                if (mapper.findJudgeCandidateSourceSnapshot(plannedRunId).isEmpty()) {
                    freeze(context, judge, plannedRunId);
                }
                launch = preparer.prepare(command).row();
            } catch (RuntimeException unavailable) {
                boolean replacement = preDispatchCapabilityMissing(unavailable);
                var settled = settlements.failBeforeRemote(judge,
                        code(unavailable, "JUDGE_CANDIDATE_PREPARE_FAILED"),
                        safe(unavailable.getMessage()), replacement);
                return replacement ? Result.legacy(settled.judge()) : resultFor(settled.judge());
            }
        } else {
            try { requireIdentity(context, judge, launch); }
            catch (RuntimeException drift) {
                return terminate(launch, GenericCandidateInternalTerminationPreparer.IntentKind.PROTOCOL_FAILURE,
                        code(drift, "JUDGE_CANDIDATE_IDENTITY_STALE"), safe(drift.getMessage()), false);
            }
        }

        Result pendingIntent = advanceIntent(launch, false);
        if (pendingIntent != null) return pendingIntent;
        launch = requireLaunch(launch.id());
        if (state(launch).terminal()) return Result.disconnected(
                "JUDGE_CANDIDATE_TERMINATION_INTENT_MISSING",
                "Terminal Judge launch has no recoverable termination intent");

        if (mapper.findJudgeCandidateSourceSnapshot(launch.candidateRunId()).isEmpty()) {
            if (state(launch) != GenericCandidateInternalLaunchState.PREPARED
                    || launch.createDispatchAttempted() || launch.externalSessionId() != null) {
                return terminate(launch, GenericCandidateInternalTerminationPreparer.IntentKind.PROTOCOL_FAILURE,
                        "JUDGE_SOURCE_SNAPSHOT_MISSING",
                        "Frozen Judge evidence is missing after the remote boundary", false);
            }
            try { freeze(context, judge, launch.candidateRunId()); }
            catch (RuntimeException invalid) {
                return terminate(requireLaunch(launch.id()),
                        GenericCandidateInternalTerminationPreparer.IntentKind.PROTOCOL_FAILURE,
                        code(invalid, "JUDGE_SOURCE_SNAPSHOT_FAILED"), safe(invalid.getMessage()), false);
            }
        }

        launch = requireLaunch(launch.id());
        if (state(launch) != GenericCandidateInternalLaunchState.SETTLED) {
            GenericCandidateInternalLaunchCoordinator.Result advanced = launches.advance(launch.id());
            if (preDispatchFallback(advanced)) {
                return terminate(advanced.launch(),
                        GenericCandidateInternalTerminationPreparer.IntentKind.OWNER_REPLACEMENT,
                        CAPABILITY_UNAVAILABLE, safe(advanced.detail()), true);
            }
            if (advanced.status() != GenericCandidateInternalLaunchCoordinator.Status.SETTLED) {
                return advanced.status() == GenericCandidateInternalLaunchCoordinator.Status.CLEANUP_PENDING
                        || advanced.launch() != null
                        && state(advanced.launch()) == GenericCandidateInternalLaunchState.DISCONNECTED
                        ? Result.disconnected(advanced.code(), safe(advanced.detail())) : Result.running();
            }
            launch = advanced.launch();
        }

        MachineCandidateSubmission.RunSnapshot run = submissions.find(launch.candidateRunId())
                .orElseThrow(() -> stale("Judge candidate run is missing after settlement"));
        if (run.state().terminal()) return terminateTerminal(launch, run);
        if (run.attemptsUsed() == 0) {
            CandidatePromptDispatchService.Result dispatch;
            try { dispatch = dispatchInitial(launch, run); }
            catch (RuntimeException failure) {
                try { run = close(run, MachineCandidateSubmission.CandidateCloseReason.REMOTE_FAILED); }
                catch (RuntimeException uncertain) {
                    return Result.disconnected(code(uncertain, "JUDGE_CANDIDATE_PROMPT_CLOSE_UNCONFIRMED"),
                            safe(uncertain.getMessage()));
                }
                return terminate(launch, GenericCandidateInternalTerminationPreparer.IntentKind.PROTOCOL_FAILURE,
                        code(failure, "JUDGE_CANDIDATE_PROMPT_FAILED"), safe(failure.getMessage()), false);
            }
            if (dispatch.status() == CandidatePromptDispatchService.Status.RESULT_UNKNOWN
                    || dispatch.status() == CandidatePromptDispatchService.Status.PENDING) {
                return Result.disconnected("OPENCODE_PROMPT_RESULT_UNKNOWN",
                        "Judge initial prompt acknowledgement is pending recovery");
            }
            if (dispatch.status() != CandidatePromptDispatchService.Status.ACKNOWLEDGED) {
                return terminateTerminal(launch, close(run,
                        MachineCandidateSubmission.CandidateCloseReason.REMOTE_FAILED));
            }
        }
        return poll(context, launch, submissions.find(run.runId()).orElseThrow());
    }

    Result cancel(JudgeRunRow judge) {
        GenericCandidateInternalLaunchRow launch = mapper
                .findGenericCandidateInternalLaunchForJudgeRun(judge.id()).orElse(null);
        if (launch == null) {
            var outcome = settlements.failBeforeRemote(judge, "JUDGE_CANCELLED",
                    "Task requested Judge cancellation", true);
            return Result.aborted(outcome.judge());
        }
        MachineCandidateSubmission.RunSnapshot run = submissions.find(launch.candidateRunId()).orElse(null);
        if (run != null && run.state() == MachineCandidateRunState.OPEN) {
            close(run, MachineCandidateSubmission.CandidateCloseReason.OWNER_REQUESTED);
        }
        return terminate(launch, GenericCandidateInternalTerminationPreparer.IntentKind.OWNER_CANCEL,
                "JUDGE_CANCELLED", "Task requested Judge cancellation", false);
    }

    private Result poll(Context context, GenericCandidateInternalLaunchRow launch,
                        MachineCandidateSubmission.RunSnapshot run) {
        OpenCodeClient.OpenCodeSession remote = remote(launch);
        try {
            List<OpenCodeClient.PendingQuestion> questions = openCode.pendingQuestions(remote);
            if (!questions.isEmpty()) {
                questions.forEach(question -> reject(remote, question.id()));
                close(run, MachineCandidateSubmission.CandidateCloseReason.INTERACTION_FORBIDDEN);
                return terminate(launch,
                        GenericCandidateInternalTerminationPreparer.IntentKind.PROTOCOL_FAILURE,
                        "JUDGE_CANDIDATE_INTERACTION_FORBIDDEN",
                        "Judge candidate must not request interactive input", false);
            }
            if (StoryAccountingClock.sessionNow(mapper, remote.id(), launch.createdAt()).isAfter(context.deadline())) {
                close(run, MachineCandidateSubmission.CandidateCloseReason.TIMEOUT);
                return terminate(launch,
                        GenericCandidateInternalTerminationPreparer.IntentKind.PROTOCOL_FAILURE,
                        "JUDGE_TIMEOUT", "Judge exceeded its configured session timeout", false);
            }
            OpenCodeClient.SessionStatus status = openCode.sessionStatus(remote);
            if (status.retrying() || !status.completed() && !status.failed()) return Result.running();
            if (status.failed()) {
                close(run, MachineCandidateSubmission.CandidateCloseReason.REMOTE_FAILED);
                return terminate(launch,
                        GenericCandidateInternalTerminationPreparer.IntentKind.PROTOCOL_FAILURE,
                        "JUDGE_SESSION_FAILED", safe(status.detail()), false);
            }
            MachineCandidateSubmission.RunSnapshot current = submissions.find(run.runId()).orElseThrow();
            if (current.state() == MachineCandidateRunState.OPEN) {
                current = close(current,
                        MachineCandidateSubmission.CandidateCloseReason.NORMAL_COMPLETION_ZERO_SUBMISSION);
            }
            return terminateTerminal(launch, current);
        } catch (RuntimeException uncertain) {
            return Result.disconnected(code(uncertain, "JUDGE_CANDIDATE_STATUS_UNCONFIRMED"),
                    safe(uncertain.getMessage()));
        }
    }

    private Result terminateTerminal(GenericCandidateInternalLaunchRow launch,
                                     MachineCandidateSubmission.RunSnapshot run) {
        String reason = switch (run.state()) {
            case ACCEPTED -> "JUDGE_CANDIDATE_ACCEPTED";
            case WAITING_INPUT -> "JUDGE_CANDIDATE_WAITING_INPUT";
            case FALLBACK_REQUIRED -> "JUDGE_CANDIDATE_FALLBACK_FORBIDDEN";
            case CLOSED -> run.closeReason() == null ? "JUDGE_SESSION_FAILED"
                    : "JUDGE_CANDIDATE_" + run.closeReason().name();
            case OPEN -> throw stale("Judge candidate run is not terminal");
        };
        var kind = switch (run.state()) {
            case ACCEPTED -> GenericCandidateInternalTerminationPreparer.IntentKind.RUN_COMPLETED;
            case CLOSED -> run.closeReason() == MachineCandidateSubmission.CandidateCloseReason.OWNER_REQUESTED
                    ? GenericCandidateInternalTerminationPreparer.IntentKind.OWNER_CANCEL
                    : GenericCandidateInternalTerminationPreparer.IntentKind.PROTOCOL_FAILURE;
            case WAITING_INPUT, FALLBACK_REQUIRED ->
                    GenericCandidateInternalTerminationPreparer.IntentKind.PROTOCOL_FAILURE;
            case OPEN -> throw stale("Judge candidate run is not terminal");
        };
        return terminate(launch, kind, reason, "Judge candidate ended in " + run.state(), false);
    }

    private Result advanceIntent(GenericCandidateInternalLaunchRow launch, boolean legacy) {
        GenericCandidateInternalTerminationIntentRow intent = intents.findForLaunch(launch.id()).orElse(null);
        if (intent == null) return null;
        GenericCandidateInternalTerminationCoordinator.Result advanced = terminations.advance(intent.id());
        if (advanced.status() == GenericCandidateInternalTerminationCoordinator.Status.DISCONNECTED) {
            return Result.disconnected(advanced.code(), safe(intents.require(intent.id()).lastErrorDetail()));
        }
        if (advanced.status() != GenericCandidateInternalTerminationCoordinator.Status.READY) return Result.running();
        intent = intents.require(intent.id());
        var outcome = settlements.settle(requireLaunch(launch.id()), intent,
                intent.reasonCode(), intent.lastErrorDetail());
        boolean replacement = legacy || CAPABILITY_UNAVAILABLE.equals(intent.reasonCode());
        return replacement ? Result.legacy(outcome.judge()) : resultFor(outcome.judge());
    }

    private Result terminate(GenericCandidateInternalLaunchRow launch,
                             GenericCandidateInternalTerminationPreparer.IntentKind kind,
                             String code, String detail, boolean legacy) {
        GenericCandidateInternalTerminationIntentRow intent = intents.findForLaunch(launch.id())
                .orElseGet(() -> terminationPreparer.prepare(
                        new GenericCandidateInternalTerminationPreparer.PrepareCommand(
                                launch.id(), kind, code)));
        GenericCandidateInternalTerminationCoordinator.Result stopped = terminations.advance(intent.id());
        if (stopped.status() == GenericCandidateInternalTerminationCoordinator.Status.DISCONNECTED) {
            return Result.disconnected(stopped.code(), safe(detail));
        }
        if (stopped.status() != GenericCandidateInternalTerminationCoordinator.Status.READY) return Result.running();
        var outcome = settlements.settle(requireLaunch(launch.id()), intents.require(intent.id()), code, detail);
        return legacy ? Result.legacy(outcome.judge()) : resultFor(outcome.judge());
    }

    private void freeze(Context context, JudgeRunRow judge, String candidateRunId) {
        promptFactory.preflight(context.source().prompt(), context.source().evidenceCatalog(), codec);
        CandidatePolicy.Context planned = new CandidatePolicy.Context(
                candidateRunId, MachineCandidateSubmission.CandidateScope.task(judge.taskId()),
                MachineCandidateSubmission.CandidateOwnerRef.judgeRun(judge.id()),
                MachineCandidateKind.JUDGE_DECISION_V1, JudgeDecisionCandidatePolicy.WORKFLOW_STEP,
                requireSourceRevision(judge), judge.version() + 1,
                JudgeDecisionCandidatePolicy.CONTRACT_VERSION,
                JudgeDecisionCandidatePolicy.MAX_ATTEMPTS, 0);
        snapshots.freeze(planned, judge, context.batch(), context.source());
    }

    private CandidatePromptDispatchService.Result dispatchInitial(
            GenericCandidateInternalLaunchRow launch, MachineCandidateSubmission.RunSnapshot run) {
        JudgeCandidateSourceSnapshotRow snapshot = mapper.findJudgeCandidateSourceSnapshot(run.runId())
                .orElseThrow(() -> stale("Frozen Judge source snapshot is missing"));
        var evidence = codec.requireEvidence(snapshot.canonicalEvidenceJson(), snapshot.evidenceSha256());
        String tool = launch.internalMcpServer().replaceAll("[^a-zA-Z0-9_-]", "_")
                + "_submit_candidate";
        String text = promptFactory.internal(run, snapshot.role(), snapshot.sourcePrompt(), evidence, tool, codec);
        OpenCodeClient.PromptRequest request = new OpenCodeClient.PromptRequest(
                text, null, null, new OpenCodeClient.ResponseFormat.Text(),
                CandidatePromptDispatchService.initialMessageId(run.runId()), List.of());
        request = attachments.withContext(
                DesignerAttachmentContext.ContextUse.taskAllPackages(launch.taskId()), request);
        return promptDispatches.advanceInitial(run, CandidateLaunchRef.genericV1(launch.id()),
                remote(launch), request, () -> true, promptIo,
                "judge-initial:" + run.owner().id(), Instant.now());
    }

    private GenericCandidateInternalLaunchPreparer.PrepareCommand command(Context context, JudgeRunRow judge) {
        return new GenericCandidateInternalLaunchPreparer.PrepareCommand(
                MachineCandidateKind.JUDGE_DECISION_V1,
                MachineCandidateSubmission.CandidateScope.task(judge.taskId()),
                MachineCandidateSubmission.CandidateOwnerRef.judgeRun(judge.id()),
                requireSourceRevision(judge), judge.version(), context.projectRoot(), context.model(),
                OpenCodeClient.SessionProfile.JUDGE_CANDIDATE_READ_ONLY);
    }

    private static void requireIdentity(Context context, JudgeRunRow judge,
                                        GenericCandidateInternalLaunchRow launch) {
        if (!MachineCandidateKind.JUDGE_DECISION_V1.name().equals(launch.candidateKind())
                || !judge.id().equals(launch.ownerId()) || !judge.id().equals(launch.judgeRunId())
                || !judge.taskId().equals(launch.taskId())
                || launch.sourceRevision() != requireSourceRevision(judge)
                || !Path.of(launch.canonicalDirectory()).equals(context.projectRoot())
                || !OpenCodeClient.SessionProfile.JUDGE_CANDIDATE_READ_ONLY.name().equals(launch.profile())) {
            throw stale("Persisted Judge launch no longer matches its frozen owner");
        }
    }

    private MachineCandidateSubmission.RunSnapshot close(
            MachineCandidateSubmission.RunSnapshot run,
            MachineCandidateSubmission.CandidateCloseReason reason) {
        return run.state() == MachineCandidateRunState.OPEN
                ? submissions.close(new MachineCandidateSubmission.CloseCommand(run.runId(), run.version(), reason))
                : run;
    }
    private JudgeRunRow current(JudgeRunRow row) {
        return mapper.findJudgeRun(row.id()).orElseThrow(() -> stale("Judge owner is missing"));
    }
    private GenericCandidateInternalLaunchRow requireLaunch(String id) {
        return mapper.findGenericCandidateInternalLaunch(id)
                .orElseThrow(() -> stale("Judge candidate launch is missing"));
    }
    private void reject(OpenCodeClient.OpenCodeSession remote, String id) {
        try { openCode.rejectQuestion(remote, id); } catch (RuntimeException ignored) { }
    }
    private static OpenCodeClient.OpenCodeSession remote(GenericCandidateInternalLaunchRow launch) {
        return new OpenCodeClient.OpenCodeSession(launch.externalSessionId(),
                Path.of(launch.canonicalDirectory()), launch.runtimeGenerationId(), launch.internalMcpServer());
    }
    private static long requireSourceRevision(JudgeRunRow judge) {
        if (judge.sourceRevision() == null || judge.sourceRevision() < 0) {
            throw stale("Judge source revision is missing");
        }
        return judge.sourceRevision();
    }
    private static boolean preDispatchCapabilityMissing(RuntimeException failure) {
        return failure instanceof SessionFailure session
                && "CANDIDATE_MANAGED_RUNTIME_REQUIRED".equals(session.code());
    }
    private static boolean preDispatchFallback(GenericCandidateInternalLaunchCoordinator.Result result) {
        if (result == null || result.launch() == null || result.launch().createDispatchAttempted()
                || result.launch().externalSessionId() != null
                || state(result.launch()) != GenericCandidateInternalLaunchState.PREPARED) return false;
        return "CANDIDATE_MANAGED_RUNTIME_REQUIRED".equals(result.code())
                || "OPENCODE_EXACT_LOOKUP_UNSUPPORTED".equals(result.code());
    }
    private static GenericCandidateInternalLaunchState state(GenericCandidateInternalLaunchRow row) {
        return GenericCandidateInternalLaunchState.valueOf(row.state());
    }
    private static Result resultFor(JudgeRunRow judge) {
        return switch (JudgeRunState.valueOf(judge.state())) {
            case COMPLETED -> Result.completed(judge);
            case SESSION_ERROR -> Result.failed(judge);
            case ABORTED -> judge.reason() != null
                    && judge.reason().startsWith("JUDGE_CANDIDATE_WAITING_INPUT:")
                    ? Result.waitingInput(judge) : Result.aborted(judge);
            default -> Result.running();
        };
    }
    private static String code(RuntimeException failure, String fallback) {
        if (failure instanceof ConflictException conflict) return conflict.code();
        if (failure instanceof SessionFailure session) return session.code();
        return fallback;
    }
    private static String safe(String value) {
        String normalized = value == null || value.isBlank() ? "Judge candidate failed"
                : value.replaceAll("[\r\n]+", " ").trim();
        return normalized.substring(0, Math.min(normalized.length(), 2_000));
    }
    private static ConflictException stale(String detail) {
        return new ConflictException("JUDGE_CANDIDATE_WORKFLOW_STALE", detail);
    }

    record Context(JudgeRunRow judge, JudgeReviewBatchRow batch, Path projectRoot,
                   OpenCodeClient.OpenCodeModel model, TaskEvidenceService.JudgeCandidateSource source,
                   Instant deadline) { }
    enum Action { RUNNING, LEGACY_FALLBACK, COMPLETED, SESSION_ERROR, WAITING_INPUT, ABORTED, DISCONNECTED }
    record Result(Action action, JudgeRunRow judge, String code, String detail) {
        static Result running() { return new Result(Action.RUNNING, null, null, null); }
        static Result legacy(JudgeRunRow row) { return new Result(Action.LEGACY_FALLBACK, row, null, null); }
        static Result completed(JudgeRunRow row) { return new Result(Action.COMPLETED, row, null, null); }
        static Result failed(JudgeRunRow row) { return new Result(Action.SESSION_ERROR, row, null, row.reason()); }
        static Result waitingInput(JudgeRunRow row) {
            return new Result(Action.WAITING_INPUT, row, "JUDGE_CANDIDATE_WAITING_INPUT", row.reason());
        }
        static Result aborted(JudgeRunRow row) { return new Result(Action.ABORTED, row, null, row.reason()); }
        static Result disconnected(String code, String detail) {
            return new Result(Action.DISCONNECTED, null, code, detail);
        }
    }
}
