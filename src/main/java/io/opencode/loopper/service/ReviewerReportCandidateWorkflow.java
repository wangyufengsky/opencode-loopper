package io.opencode.loopper.service;

import io.opencode.loopper.domain.GenericCandidateInternalLaunchState;
import io.opencode.loopper.domain.GenericCandidateInternalTerminationIntentState;
import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.domain.MachineCandidateRunState;
import io.opencode.loopper.domain.ModelResponseMode;
import io.opencode.loopper.persistence.AnalysisReportRow;
import io.opencode.loopper.persistence.GenericCandidateInternalLaunchRow;
import io.opencode.loopper.persistence.GenericCandidateInternalTerminationIntentRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.file.Path;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** Drives one durable REVIEWER_REPORT_V1 generic launch without reading model final text. */
@Component
final class ReviewerReportCandidateWorkflow {
    static final String RESPONSE_MODE = "INTERNAL_MCP";
    private final LoopperMapper mapper;
    private final GenericCandidateInternalLaunchPreparer preparer;
    private final GenericCandidateInternalLaunchCoordinator launches;
    private final GenericCandidateInternalTerminationPreparer terminationPreparer;
    private final GenericCandidateInternalTerminationCoordinator terminations;
    private final GenericCandidateInternalTerminationIntentStore intentStore;
    private final MachineCandidateSubmission submissions;
    private final CandidatePromptDispatchService promptDispatches;
    private final CandidatePromptDispatchService.PromptIo promptIo;
    private final DesignerModelPromptTransport modelPrompts;
    private final ReviewerReportSourceManifestCapture sourceCapture;
    private final ReviewerReportSourceSnapshotStore sourceSnapshots;
    private final ReviewerReportCandidateSettlementService settlements;
    private final OpenCodeClient openCode;
    private final ReviewerReportCandidatePromptFactory promptFactory =
            new ReviewerReportCandidatePromptFactory();

    ReviewerReportCandidateWorkflow(
            LoopperMapper mapper,
            GenericCandidateInternalLaunchPreparer preparer,
            GenericCandidateInternalLaunchCoordinator launches,
            GenericCandidateInternalTerminationPreparer terminationPreparer,
            GenericCandidateInternalTerminationCoordinator terminations,
            GenericCandidateInternalTerminationIntentStore intentStore,
            MachineCandidateSubmission submissions,
            CandidatePromptDispatchService promptDispatches,
            DesignerAttachmentContext attachments,
            tools.jackson.databind.ObjectMapper json,
            ReviewerReportSourceManifestCapture sourceCapture,
            ReviewerReportSourceSnapshotStore sourceSnapshots,
            ReviewerReportCandidateSettlementService settlements,
            OpenCodeClient openCode) {
        this.mapper = mapper;
        this.preparer = preparer;
        this.launches = launches;
        this.terminationPreparer = terminationPreparer;
        this.terminations = terminations;
        this.intentStore = intentStore;
        this.submissions = submissions;
        this.promptDispatches = promptDispatches;
        this.modelPrompts = new DesignerModelPromptTransport(openCode, attachments, json);
        this.promptIo = new CandidatePromptTransportIo(modelPrompts);
        this.sourceCapture = sourceCapture;
        this.sourceSnapshots = sourceSnapshots;
        this.settlements = settlements;
        this.openCode = openCode;
    }

    boolean owns(AnalysisReportRow report) {
        if (report == null) return false;
        if (RESPONSE_MODE.equals(report.responseMode())) return true;
        GenericCandidateInternalLaunchRow launch = mapper
                .findGenericCandidateInternalLaunchForAnalysisReport(report.id()).orElse(null);
        if (launch == null) return false;
        GenericCandidateInternalTerminationIntentRow intent =
                intentStore.findForLaunch(launch.id()).orElse(null);
        return !legacyReplacementComplete(launch, intent);
    }

    Result advance(Context context) {
        AnalysisReportRow report = current(context.report());
        GenericCandidateInternalLaunchRow launch = mapper
                .findGenericCandidateInternalLaunchForAnalysisReport(report.id()).orElse(null);
        if (launch == null) {
            GenericCandidateInternalLaunchPreparer.PrepareCommand command = command(context, report);
            try {
                launch = preparer.prepare(command).row();
            } catch (RuntimeException unavailable) {
                if (preDispatchCapabilityMissing(unavailable)) return Result.legacy();
                return failBeforeRemote(report, code(unavailable, "REVIEWER_CANDIDATE_PREPARE_FAILED"),
                        safe(unavailable.getMessage()));
            }
        } else {
            try {
                requirePersistedIdentity(context, report, launch);
            } catch (RuntimeException drift) {
                return terminate(context, report, launch,
                        GenericCandidateInternalTerminationPreparer.IntentKind.PROTOCOL_FAILURE,
                        code(drift, "REVIEWER_CANDIDATE_IDENTITY_STALE"), safe(drift.getMessage()), false);
            }
        }
        Result terminal = advanceIntentIfPresent(context, report, launch);
        if (terminal != null) return terminal;
        launch = requireLaunch(launch.id());
        GenericCandidateInternalLaunchState launchState = state(launch);
        if (launchState.terminal()) {
            return Result.disconnected("REVIEWER_CANDIDATE_TERMINATION_INTENT_MISSING",
                    "Terminal Reviewer launch has no recoverable termination intent");
        }
        if (context.ownerStopping()) {
            return terminate(context, report, launch,
                    GenericCandidateInternalTerminationPreparer.IntentKind.OWNER_CANCEL,
                    "DESIGNER_CANCELLED", "Designer session was cancelled", false);
        }
        if (mapper.findReviewerReportSourceSnapshot(launch.candidateRunId()).isEmpty()) {
            if (launchState != GenericCandidateInternalLaunchState.PREPARED
                    || launch.createDispatchAttempted() || launch.externalSessionId() != null) {
                return terminate(context, report, launch,
                        GenericCandidateInternalTerminationPreparer.IntentKind.PROTOCOL_FAILURE,
                        "REVIEWER_SOURCE_SNAPSHOT_MISSING",
                        "Frozen Reviewer source manifest is missing after the remote boundary", false);
            }
            try {
                freezeSource(context, launch);
            } catch (RuntimeException invalidSource) {
                return terminate(context, report, requireLaunch(launch.id()),
                        GenericCandidateInternalTerminationPreparer.IntentKind.PROTOCOL_FAILURE,
                        code(invalidSource, "REVIEWER_SOURCE_SNAPSHOT_FAILED"),
                        invalidSource.getMessage(), false);
            }
        }
        launch = requireLaunch(launch.id());
        if (state(launch) != GenericCandidateInternalLaunchState.SETTLED) {
            GenericCandidateInternalLaunchCoordinator.Result advanced = launches.advance(launch.id());
            if (preDispatchFallback(advanced)) {
                return terminate(context, report, advanced.launch(),
                        GenericCandidateInternalTerminationPreparer.IntentKind.OWNER_REPLACEMENT,
                        "REVIEWER_CANDIDATE_CAPABILITY_UNAVAILABLE",
                        advanced.detail(), true);
            }
            if (advanced.status() != GenericCandidateInternalLaunchCoordinator.Status.SETTLED) {
                return advanced.status() == GenericCandidateInternalLaunchCoordinator.Status.CLEANUP_PENDING
                        || advanced.launch() != null
                        && state(advanced.launch()) == GenericCandidateInternalLaunchState.DISCONNECTED
                        ? Result.disconnected(advanced.code(), advanced.detail())
                        : Result.running();
            }
            launch = advanced.launch();
        }
        MachineCandidateSubmission.RunSnapshot run = submissions.find(launch.candidateRunId())
                .orElseThrow(() -> stale("Reviewer candidate run is missing after launch settlement"));
        if (run.state().terminal()) return terminateTerminalRun(context, report, launch, run);
        if (run.attemptsUsed() == 0) {
            CandidatePromptDispatchService.Result dispatch = dispatchInitial(context, launch, run);
            if (dispatch.status() == CandidatePromptDispatchService.Status.RESULT_UNKNOWN
                    || dispatch.status() == CandidatePromptDispatchService.Status.PENDING) {
                return Result.disconnected("OPENCODE_PROMPT_RESULT_UNKNOWN",
                        "Reviewer initial prompt acknowledgement is pending recovery");
            }
            if (dispatch.status() != CandidatePromptDispatchService.Status.ACKNOWLEDGED) {
                MachineCandidateSubmission.RunSnapshot closed = close(run,
                        MachineCandidateSubmission.CandidateCloseReason.REMOTE_FAILED);
                return terminate(context, report, launch,
                        GenericCandidateInternalTerminationPreparer.IntentKind.PROTOCOL_FAILURE,
                        "REVIEWER_CANDIDATE_INITIAL_PROMPT_" + dispatch.status().name(),
                        "Reviewer initial prompt could not be safely dispatched", false);
            }
        }
        return pollRemote(context, report, launch,
                submissions.find(run.runId()).orElseThrow());
    }

    private Result pollRemote(Context context, AnalysisReportRow report,
                              GenericCandidateInternalLaunchRow launch,
                              MachineCandidateSubmission.RunSnapshot run) {
        OpenCodeClient.OpenCodeSession remote = remote(launch);
        try {
            var questions = openCode.pendingQuestions(remote);
            if (!questions.isEmpty()) {
                questions.forEach(question -> reject(remote, question.id()));
                close(run, MachineCandidateSubmission.CandidateCloseReason.INTERACTION_FORBIDDEN);
                return terminate(context, report, launch,
                        GenericCandidateInternalTerminationPreparer.IntentKind.PROTOCOL_FAILURE,
                        "REVIEWER_CANDIDATE_INTERACTION_FORBIDDEN",
                        "Reviewer candidate must not request interactive input", false);
            }
            if (report.deadlineAt() != null && StoryAccountingClock.sessionNow(mapper, launch.externalSessionId(), report.createdAt()).isAfter(Instant.parse(report.deadlineAt()))) {
                close(run, MachineCandidateSubmission.CandidateCloseReason.TIMEOUT);
                return terminate(context, report, launch,
                        GenericCandidateInternalTerminationPreparer.IntentKind.PROTOCOL_FAILURE,
                        "REVIEWER_TIMEOUT", "Independent Reviewer exceeded its 120 second boundary", false);
            }
            OpenCodeClient.SessionStatus status = openCode.sessionStatus(remote);
            if (status.retrying() || !status.completed() && !status.failed()) return Result.running();
            if (status.failed()) {
                close(run, MachineCandidateSubmission.CandidateCloseReason.REMOTE_FAILED);
            return terminate(context, report, launch,
                    GenericCandidateInternalTerminationPreparer.IntentKind.PROTOCOL_FAILURE,
                        "REVIEWER_SESSION_FAILED", safe(status.detail()), false);
            }
            MachineCandidateSubmission.RunSnapshot current = submissions.find(run.runId()).orElseThrow();
            if (current.state() == MachineCandidateRunState.OPEN) {
                current = close(current,
                        MachineCandidateSubmission.CandidateCloseReason.NORMAL_COMPLETION_ZERO_SUBMISSION);
            }
            return terminateTerminalRun(context, report, launch, current);
        } catch (RuntimeException uncertain) {
            return Result.disconnected(code(uncertain, "REVIEWER_CANDIDATE_STATUS_UNCONFIRMED"),
                    safe(uncertain.getMessage()));
        }
    }

    private Result terminateTerminalRun(Context context, AnalysisReportRow report,
                                        GenericCandidateInternalLaunchRow launch,
                                        MachineCandidateSubmission.RunSnapshot run) {
        String reason = switch (run.state()) {
            case ACCEPTED -> "REVIEWER_ACCEPTED";
            case WAITING_INPUT -> "REVIEWER_CANDIDATE_WAITING_INPUT";
            case FALLBACK_REQUIRED -> "REVIEWER_CANDIDATE_FALLBACK_FORBIDDEN";
            case CLOSED -> run.closeReason() == null ? "REVIEWER_SESSION_FAILED"
                    : switch (run.closeReason()) {
                        case TIMEOUT -> "REVIEWER_TIMEOUT";
                        case INTERACTION_FORBIDDEN -> "REVIEWER_CANDIDATE_INTERACTION_FORBIDDEN";
                        case NORMAL_COMPLETION_ZERO_SUBMISSION -> "REVIEWER_CANDIDATE_ZERO_SUBMISSION";
                        case REMOTE_FAILED -> "REVIEWER_SESSION_FAILED";
                        case OWNER_REQUESTED -> "DESIGNER_CANCELLED";
                    };
            case OPEN -> throw stale("Reviewer run is not terminal");
        };
        GenericCandidateInternalTerminationPreparer.IntentKind intentKind = switch (run.state()) {
            case ACCEPTED -> normalCompletionIntent();
            case CLOSED -> run.closeReason()
                    == MachineCandidateSubmission.CandidateCloseReason.OWNER_REQUESTED
                    ? GenericCandidateInternalTerminationPreparer.IntentKind.OWNER_CANCEL
                    : GenericCandidateInternalTerminationPreparer.IntentKind.PROTOCOL_FAILURE;
            case WAITING_INPUT, FALLBACK_REQUIRED ->
                    GenericCandidateInternalTerminationPreparer.IntentKind.PROTOCOL_FAILURE;
            case OPEN -> throw stale("Reviewer run is not terminal");
        };
        return terminate(context, report, launch, intentKind, reason,
                "Reviewer candidate ended in " + run.state().name(), false);
    }

    private Result advanceIntentIfPresent(Context context, AnalysisReportRow report,
                                          GenericCandidateInternalLaunchRow launch) {
        GenericCandidateInternalTerminationIntentRow intent = intentStore.findForLaunch(launch.id()).orElse(null);
        if (intent == null) return null;
        GenericCandidateInternalTerminationCoordinator.Result result = terminations.advance(intent.id());
        if (result.status() == GenericCandidateInternalTerminationCoordinator.Status.DISCONNECTED) {
            return Result.disconnected(result.code(), intentStore.require(intent.id()).lastErrorDetail());
        }
        if (result.status() != GenericCandidateInternalTerminationCoordinator.Status.READY) return Result.running();
        intent = intentStore.require(intent.id());
        launch = requireLaunch(launch.id());
        if ("REVIEWER_CANDIDATE_CAPABILITY_UNAVAILABLE".equals(intent.reasonCode())) {
            intentStore.complete(intent);
            return Result.legacy();
        }
        boolean settled = settlements.settle(current(report), launch, intent,
                intent.reasonCode(), intent.lastErrorDetail());
        if (!settled) return Result.running();
        AnalysisReportRow owner = current(report);
        return "READY".equals(owner.state()) ? Result.ready()
                : "FAILED".equals(owner.state()) ? Result.failed(owner.errorCode(), owner.errorDetail())
                : Result.running();
    }

    private Result terminate(Context context, AnalysisReportRow report,
                             GenericCandidateInternalLaunchRow launch,
                             GenericCandidateInternalTerminationPreparer.IntentKind kind,
                             String reasonCode, String detail, boolean legacyAfterStop) {
        GenericCandidateInternalTerminationIntentRow intent = intentStore.findForLaunch(launch.id())
                .orElseGet(() -> terminationPreparer.prepare(
                        new GenericCandidateInternalTerminationPreparer.PrepareCommand(
                                launch.id(), kind, reasonCode)));
        GenericCandidateInternalTerminationCoordinator.Result stopped = terminations.advance(intent.id());
        if (stopped.status() == GenericCandidateInternalTerminationCoordinator.Status.DISCONNECTED) {
            return Result.disconnected(stopped.code(), safe(detail));
        }
        if (stopped.status() != GenericCandidateInternalTerminationCoordinator.Status.READY) {
            return Result.running();
        }
        intent = intentStore.require(intent.id());
        if (legacyAfterStop) {
            intentStore.complete(intent);
            return Result.legacy();
        }
        boolean settled = settlements.settle(current(report), requireLaunch(launch.id()), intent,
                reasonCode, detail);
        if (!settled) return Result.running();
        AnalysisReportRow owner = current(report);
        return "READY".equals(owner.state()) ? Result.ready()
                : Result.failed(owner.errorCode(), owner.errorDetail());
    }

    private void freezeSource(Context context, GenericCandidateInternalLaunchRow launch) {
        CandidatePolicy.Context planned = new CandidatePolicy.Context(
                launch.candidateRunId(), MachineCandidateSubmission.CandidateScope
                        .designerSession(context.report().designerSessionId()),
                MachineCandidateSubmission.CandidateOwnerRef.analysisReport(context.report().id()),
                MachineCandidateKind.REVIEWER_REPORT_V1,
                ReviewerReportCandidatePolicy.WORKFLOW_STEP, context.sourceRevision(),
                launch.preparedOwnerVersion() + 1, ReviewerReportCandidatePolicy.CONTRACT_VERSION,
                ReviewerReportCandidatePolicy.MAX_ATTEMPTS, 0);
        sourceSnapshots.freeze(planned, sourceCapture.capture(context.projectRoot()));
    }

    private CandidatePromptDispatchService.Result dispatchInitial(
            Context context, GenericCandidateInternalLaunchRow launch,
            MachineCandidateSubmission.RunSnapshot run) {
        String tool = launch.internalMcpServer().replaceAll("[^a-zA-Z0-9_-]", "_")
                + "_submit_candidate";
        String prompt = promptFactory.internal(context.roleInstructions(),
                context.projectRoot().toString(), context.requirement(), run, tool);
        String messageId = CandidatePromptDispatchService.initialMessageId(run.runId());
        DesignerModelPromptTransport.PreparedPrompt prepared = modelPrompts.prepare(
                prompt, ModelResponseMode.TEXT_MARKER.name(), null,
                context.report().designerSessionId(), null, messageId);
        return promptDispatches.advanceInitial(run, CandidateLaunchRef.genericV1(launch.id()),
                remote(launch), prepared.request(), () -> true, promptIo,
                "reviewer-initial:" + context.report().id(), Instant.now());
    }

    private GenericCandidateInternalLaunchPreparer.PrepareCommand command(
            Context context, AnalysisReportRow report) {
        return new GenericCandidateInternalLaunchPreparer.PrepareCommand(
                MachineCandidateKind.REVIEWER_REPORT_V1,
                MachineCandidateSubmission.CandidateScope.designerSession(report.designerSessionId()),
                MachineCandidateSubmission.CandidateOwnerRef.analysisReport(report.id()),
                context.sourceRevision(), report.version(), context.projectRoot(), context.model(),
                OpenCodeClient.SessionProfile.REVIEWER_CANDIDATE_READ_ONLY);
    }

    private static void requirePersistedIdentity(
            Context context, AnalysisReportRow report, GenericCandidateInternalLaunchRow launch) {
        if (!MachineCandidateKind.REVIEWER_REPORT_V1.name().equals(launch.candidateKind())
                || !ReviewerReportCandidatePolicy.WORKFLOW_STEP.equals(launch.workflowStep())
                || !ReviewerReportCandidatePolicy.CONTRACT_VERSION.equals(launch.contractVersion())
                || !"ANALYSIS_REPORT".equals(launch.ownerType())
                || !report.id().equals(launch.ownerId())
                || !report.id().equals(launch.analysisReportId())
                || !report.designerSessionId().equals(launch.designerSessionId())
                || launch.sourceRevision() != context.sourceRevision()
                || report.sourceRequirementRevision() == null
                || launch.sourceRevision() != report.sourceRequirementRevision()
                || !OpenCodeClient.SessionProfile.REVIEWER_CANDIDATE_READ_ONLY.name()
                        .equals(launch.profile())) {
            throw stale("Persisted Reviewer launch identity no longer matches its frozen owner");
        }
    }

    private MachineCandidateSubmission.RunSnapshot close(
            MachineCandidateSubmission.RunSnapshot run,
            MachineCandidateSubmission.CandidateCloseReason reason) {
        return run.state() == MachineCandidateRunState.OPEN
                ? submissions.close(new MachineCandidateSubmission.CloseCommand(
                        run.runId(), run.version(), reason)) : run;
    }

    private static boolean preDispatchCapabilityMissing(RuntimeException failure) {
        return failure instanceof io.opencode.loopper.domain.SessionFailure session
                && "CANDIDATE_MANAGED_RUNTIME_REQUIRED".equals(session.code());
    }

    private static boolean preDispatchFallback(GenericCandidateInternalLaunchCoordinator.Result result) {
        if (result == null || result.launch() == null || result.launch().createDispatchAttempted()
                || result.launch().externalSessionId() != null
                || state(result.launch()) != GenericCandidateInternalLaunchState.PREPARED) return false;
        return "CANDIDATE_MANAGED_RUNTIME_REQUIRED".equals(result.code())
                || "OPENCODE_EXACT_LOOKUP_UNSUPPORTED".equals(result.code());
    }

    private static GenericCandidateInternalTerminationPreparer.IntentKind normalCompletionIntent() {
        return GenericCandidateInternalTerminationPreparer.IntentKind.valueOf("RUN_COMPLETED");
    }

    private Result failBeforeRemote(AnalysisReportRow report, String code, String detail) {
        if (!"RUNNING".equals(report.state())) return Result.failed(code, detail);
        String now = Instant.now().toString();
        AnalysisReportRow failed = new AnalysisReportRow(
                report.id(), report.designerSessionId(), report.taskProfileId(), "FAILED",
                report.title(), report.markdown(), report.evidenceJson(), report.contentSha256(),
                report.sourceSnapshotSha256(), safe(code), safe(detail), report.createdAt(), now,
                report.version(), null, "NO_REMOTE_CREATED", report.sourceRequirement(),
                report.rolePackId(), report.rolePackVersion(), report.reviewerContractVersion(),
                report.responseMode(), report.findingsJson(), report.deadlineAt(),
                report.sourceRequirementRevision());
        if (mapper.updateAnalysisReport(failed) != 1) throw stale("Reviewer pre-remote failure raced");
        return Result.failed(code, detail);
    }

    private static boolean legacyReplacementComplete(
            GenericCandidateInternalLaunchRow launch,
            GenericCandidateInternalTerminationIntentRow intent) {
        return intent != null
                && GenericCandidateInternalLaunchState.STALE.name().equals(launch.state())
                && GenericCandidateInternalTerminationIntentState.COMPLETED.name().equals(intent.state())
                && GenericCandidateInternalTerminationPreparer.IntentKind.OWNER_REPLACEMENT.name()
                        .equals(intent.intentKind())
                && "REVIEWER_CANDIDATE_CAPABILITY_UNAVAILABLE".equals(intent.reasonCode());
    }

    private GenericCandidateInternalLaunchRow requireLaunch(String id) {
        return mapper.findGenericCandidateInternalLaunch(id)
                .orElseThrow(() -> stale("Reviewer candidate launch is missing"));
    }

    private AnalysisReportRow current(AnalysisReportRow row) {
        return mapper.findAnalysisReport(row.designerSessionId(), row.id())
                .orElseThrow(() -> stale("Reviewer report owner is missing"));
    }

    private static OpenCodeClient.OpenCodeSession remote(GenericCandidateInternalLaunchRow launch) {
        return new OpenCodeClient.OpenCodeSession(launch.externalSessionId(),
                Path.of(launch.canonicalDirectory()), launch.runtimeGenerationId(), launch.internalMcpServer());
    }

    private void reject(OpenCodeClient.OpenCodeSession remote, String requestId) {
        try { openCode.rejectQuestion(remote, requestId); }
        catch (RuntimeException ignored) { }
    }

    private static GenericCandidateInternalLaunchState state(GenericCandidateInternalLaunchRow launch) {
        return GenericCandidateInternalLaunchState.valueOf(launch.state());
    }

    private static String code(RuntimeException failure, String fallback) {
        if (failure instanceof ConflictException conflict) return conflict.code();
        if (failure instanceof io.opencode.loopper.domain.SessionFailure session) return session.code();
        return fallback;
    }

    private static String safe(String value) {
        String normalized = value == null ? "Reviewer candidate failed" : value.replaceAll("[\\r\\n]+", " ").trim();
        return normalized.length() <= 2_000 ? normalized : normalized.substring(0, 2_000);
    }

    private static ConflictException stale(String detail) {
        return new ConflictException("REVIEWER_CANDIDATE_WORKFLOW_STALE", detail);
    }

    record Context(AnalysisReportRow report, Path projectRoot, long sourceRevision,
                   OpenCodeClient.OpenCodeModel model, String roleInstructions,
                   String requirement, boolean ownerStopping) { }

    enum Action { RUNNING, LEGACY_FALLBACK, READY, FAILED, DISCONNECTED }
    record Result(Action action, String code, String detail) {
        static Result running() { return new Result(Action.RUNNING, null, null); }
        static Result legacy() { return new Result(Action.LEGACY_FALLBACK, null, null); }
        static Result ready() { return new Result(Action.READY, null, null); }
        static Result failed(String code, String detail) { return new Result(Action.FAILED, code, detail); }
        static Result disconnected(String code, String detail) {
            return new Result(Action.DISCONNECTED, code, detail);
        }
    }
}
