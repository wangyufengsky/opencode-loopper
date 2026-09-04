package io.opencode.loopper.service;

import io.opencode.loopper.domain.GenericCandidateInternalLaunchState;
import io.opencode.loopper.domain.GenericCandidateInternalTerminationIntentState;
import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.domain.LifecycleMachineType;
import io.opencode.loopper.domain.LifecycleScopeType;
import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.domain.MachineCandidateRunState;
import io.opencode.loopper.domain.ProjectConventionState;
import io.opencode.loopper.domain.SessionFailure;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.GenericCandidateInternalLaunchRow;
import io.opencode.loopper.persistence.GenericCandidateInternalTerminationIntentRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.ProjectConventionDraftRow;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Drives one durable PROJECT_CONVENTION_V1 launch without reading model final text. */
@Component
final class ProjectConventionCandidateWorkflow {
    static final String RESPONSE_MODE = "INTERNAL_MCP";
    static final long SOURCE_REVISION = 1;
    private static final String CAPABILITY_UNAVAILABLE =
            "PROJECT_CONVENTION_CANDIDATE_CAPABILITY_UNAVAILABLE";

    private final LoopperMapper mapper;
    private final LifecycleTransitionService lifecycle;
    private final GenericCandidateInternalLaunchPreparer preparer;
    private final GenericCandidateInternalLaunchCoordinator launches;
    private final GenericCandidateInternalTerminationPreparer terminationPreparer;
    private final GenericCandidateInternalTerminationCoordinator terminations;
    private final GenericCandidateInternalTerminationIntentStore intentStore;
    private final MachineCandidateSubmission submissions;
    private final CandidatePromptDispatchService promptDispatches;
    private final ProjectConventionEvidenceCatalogCapture evidenceCapture;
    private final ProjectConventionCandidateSourceSnapshotStore sourceSnapshots;
    private final ProjectConventionCompilationInputLoader inputs;
    private final ProjectConventionCandidateSettlementService settlements;
    private final OpenCodeClient openCode;
    private final ProjectConventionCandidatePromptFactory promptFactory =
            new ProjectConventionCandidatePromptFactory();
    private final CandidatePromptDispatchService.PromptIo promptIo;

    ProjectConventionCandidateWorkflow(
            LoopperMapper mapper, LifecycleTransitionService lifecycle,
            GenericCandidateInternalLaunchPreparer preparer,
            GenericCandidateInternalLaunchCoordinator launches,
            GenericCandidateInternalTerminationPreparer terminationPreparer,
            GenericCandidateInternalTerminationCoordinator terminations,
            GenericCandidateInternalTerminationIntentStore intentStore,
            MachineCandidateSubmission submissions,
            CandidatePromptDispatchService promptDispatches,
            ProjectConventionEvidenceCatalogCapture evidenceCapture,
            ProjectConventionCandidateSourceSnapshotStore sourceSnapshots,
            ProjectConventionCompilationInputLoader inputs,
            ProjectConventionCandidateSettlementService settlements,
            OpenCodeClient openCode) {
        this.mapper = mapper;
        this.lifecycle = lifecycle;
        this.preparer = preparer;
        this.launches = launches;
        this.terminationPreparer = terminationPreparer;
        this.terminations = terminations;
        this.intentStore = intentStore;
        this.submissions = submissions;
        this.promptDispatches = promptDispatches;
        this.evidenceCapture = evidenceCapture;
        this.sourceSnapshots = sourceSnapshots;
        this.inputs = inputs;
        this.settlements = settlements;
        this.openCode = openCode;
        this.promptIo = new CandidatePromptDispatchService.PromptIo() {
            @Override public OpenCodeClient.MessageLookup lookup(
                    OpenCodeClient.OpenCodeSession remote,
                    OpenCodeClient.PromptRequest request, String sha256) {
                OpenCodeClient.MessageLookup lookup = openCode.findPromptMessage(remote, request, sha256);
                if (!lookup.supported()) throw new SessionFailure(
                        "OPENCODE_PROMPT_LOOKUP_UNAVAILABLE",
                        "OpenCode cannot recover a deterministic Convention prompt acknowledgement");
                if (lookup.exists() && !sha256.equals(lookup.verifiedRequestSha256())) {
                    throw new SessionFailure("OPENCODE_PROMPT_REQUEST_STALE",
                            "Convention prompt acknowledgement does not match the frozen request");
                }
                return lookup;
            }
            @Override public void dispatch(OpenCodeClient.OpenCodeSession remote,
                                           OpenCodeClient.PromptRequest request) {
                openCode.promptAsync(remote, request);
            }
        };
    }

    boolean owns(ProjectConventionDraftRow draft) {
        if (draft == null) return false;
        GenericCandidateInternalLaunchRow launch = mapper
                .findGenericCandidateInternalLaunchForProjectConventionDraft(draft.id()).orElse(null);
        if (launch == null) {
            return RESPONSE_MODE.equals(draft.responseMode()) && draft.externalSessionId() == null;
        }
        GenericCandidateInternalTerminationIntentRow intent =
                intentStore.findForLaunch(launch.id()).orElse(null);
        return !legacyReplacementComplete(launch, intent);
    }

    Result advance(Context context) {
        ProjectConventionDraftRow draft = current(context.draft());
        GenericCandidateInternalLaunchRow launch = mapper
                .findGenericCandidateInternalLaunchForProjectConventionDraft(draft.id()).orElse(null);
        if (launch == null) {
            try {
                launch = preparer.prepare(command(context, draft)).row();
            } catch (RuntimeException unavailable) {
                if (preDispatchCapabilityMissing(unavailable)) return Result.legacy();
                return failBeforeRemote(draft, code(unavailable,
                        "PROJECT_CONVENTION_CANDIDATE_PREPARE_FAILED"), safe(unavailable.getMessage()));
            }
        } else {
            try {
                requirePersistedIdentity(context, draft, launch);
            } catch (RuntimeException drift) {
                return terminate(draft, launch,
                        GenericCandidateInternalTerminationPreparer.IntentKind.PROTOCOL_FAILURE,
                        code(drift, "PROJECT_CONVENTION_CANDIDATE_IDENTITY_STALE"),
                        safe(drift.getMessage()), false);
            }
        }

        Result terminal = advanceIntentIfPresent(draft, launch);
        if (terminal != null) return terminal;
        launch = requireLaunch(launch.id());
        if (state(launch).terminal()) {
            return Result.disconnected("PROJECT_CONVENTION_CANDIDATE_TERMINATION_INTENT_MISSING",
                    "Terminal Convention launch has no recoverable termination intent");
        }
        if (context.ownerStopping()) {
            MachineCandidateSubmission.RunSnapshot run = submissions.find(launch.candidateRunId()).orElse(null);
            if (run != null && run.state() == MachineCandidateRunState.OPEN) {
                close(run, MachineCandidateSubmission.CandidateCloseReason.OWNER_REQUESTED);
            }
            return terminate(draft, launch,
                    GenericCandidateInternalTerminationPreparer.IntentKind.OWNER_CANCEL,
                    "PROJECT_CONVENTION_CANCELLED", "User cancelled project convention generation", false);
        }

        if (mapper.findProjectConventionCandidateSourceSnapshot(launch.candidateRunId()).isEmpty()) {
            if (state(launch) != GenericCandidateInternalLaunchState.PREPARED
                    || launch.createDispatchAttempted() || launch.externalSessionId() != null) {
                return terminate(draft, launch,
                        GenericCandidateInternalTerminationPreparer.IntentKind.PROTOCOL_FAILURE,
                        "PROJECT_CONVENTION_SOURCE_SNAPSHOT_MISSING",
                        "Frozen Convention source and evidence are missing after the remote boundary", false);
            }
            try {
                freezeSource(context, draft, launch);
            } catch (RuntimeException invalidSource) {
                return terminate(draft, requireLaunch(launch.id()),
                        GenericCandidateInternalTerminationPreparer.IntentKind.PROTOCOL_FAILURE,
                        code(invalidSource, "PROJECT_CONVENTION_SOURCE_SNAPSHOT_FAILED"),
                        safe(invalidSource.getMessage()), false);
            }
        }

        launch = requireLaunch(launch.id());
        if (state(launch) != GenericCandidateInternalLaunchState.SETTLED) {
            GenericCandidateInternalLaunchCoordinator.Result advanced = launches.advance(launch.id());
            if (preDispatchFallback(advanced)) {
                return terminate(draft, advanced.launch(),
                        GenericCandidateInternalTerminationPreparer.IntentKind.OWNER_REPLACEMENT,
                        CAPABILITY_UNAVAILABLE, safe(advanced.detail()), true);
            }
            if (advanced.status() != GenericCandidateInternalLaunchCoordinator.Status.SETTLED) {
                return advanced.status() == GenericCandidateInternalLaunchCoordinator.Status.CLEANUP_PENDING
                        || advanced.launch() != null
                        && state(advanced.launch()) == GenericCandidateInternalLaunchState.DISCONNECTED
                        ? Result.disconnected(advanced.code(), safe(advanced.detail()))
                        : Result.running();
            }
            launch = advanced.launch();
        }

        MachineCandidateSubmission.RunSnapshot run = submissions.find(launch.candidateRunId())
                .orElseThrow(() -> stale("Convention candidate run is missing after settlement"));
        if (run.state().terminal()) return terminateTerminalRun(draft, launch, run);
        if (run.attemptsUsed() == 0) {
            CandidatePromptDispatchService.Result dispatch = dispatchInitial(launch, run);
            if (dispatch.status() == CandidatePromptDispatchService.Status.RESULT_UNKNOWN
                    || dispatch.status() == CandidatePromptDispatchService.Status.PENDING) {
                return Result.disconnected("OPENCODE_PROMPT_RESULT_UNKNOWN",
                        "Convention initial prompt acknowledgement is pending recovery");
            }
            if (dispatch.status() != CandidatePromptDispatchService.Status.ACKNOWLEDGED) {
                MachineCandidateSubmission.RunSnapshot closed = close(run,
                        MachineCandidateSubmission.CandidateCloseReason.REMOTE_FAILED);
                return terminateTerminalRun(draft, launch, closed);
            }
        }
        return pollRemote(draft, launch,
                submissions.find(run.runId()).orElseThrow());
    }

    private Result pollRemote(ProjectConventionDraftRow draft,
                              GenericCandidateInternalLaunchRow launch,
                              MachineCandidateSubmission.RunSnapshot run) {
        OpenCodeClient.OpenCodeSession remote = remote(launch);
        try {
            List<OpenCodeClient.PendingQuestion> questions = openCode.pendingQuestions(remote);
            if (!questions.isEmpty()) {
                questions.forEach(question -> reject(remote, question.id()));
                close(run, MachineCandidateSubmission.CandidateCloseReason.INTERACTION_FORBIDDEN);
                return terminate(draft, launch,
                        GenericCandidateInternalTerminationPreparer.IntentKind.PROTOCOL_FAILURE,
                        "PROJECT_CONVENTION_CANDIDATE_INTERACTION_FORBIDDEN",
                        "Convention candidate must not request interactive input", false);
            }
            OpenCodeClient.SessionStatus status = openCode.sessionStatus(remote);
            if (status.retrying() || !status.completed() && !status.failed()) return Result.running();
            if (status.failed()) {
                close(run, MachineCandidateSubmission.CandidateCloseReason.REMOTE_FAILED);
                return terminate(draft, launch,
                        GenericCandidateInternalTerminationPreparer.IntentKind.PROTOCOL_FAILURE,
                        "PROJECT_CONVENTION_SESSION_FAILED", safe(status.detail()), false);
            }
            MachineCandidateSubmission.RunSnapshot current = submissions.find(run.runId()).orElseThrow();
            if (current.state() == MachineCandidateRunState.OPEN) {
                current = close(current,
                        MachineCandidateSubmission.CandidateCloseReason.NORMAL_COMPLETION_ZERO_SUBMISSION);
            }
            return terminateTerminalRun(draft, launch, current);
        } catch (RuntimeException uncertain) {
            return Result.disconnected(code(uncertain,
                    "PROJECT_CONVENTION_CANDIDATE_STATUS_UNCONFIRMED"), safe(uncertain.getMessage()));
        }
    }

    private Result terminateTerminalRun(ProjectConventionDraftRow draft,
                                        GenericCandidateInternalLaunchRow launch,
                                        MachineCandidateSubmission.RunSnapshot run) {
        String reason = switch (run.state()) {
            case ACCEPTED -> "PROJECT_CONVENTION_ACCEPTED";
            case WAITING_INPUT -> "PROJECT_CONVENTION_CANDIDATE_WAITING_INPUT";
            case FALLBACK_REQUIRED -> "PROJECT_CONVENTION_CANDIDATE_FALLBACK_FORBIDDEN";
            case CLOSED -> run.closeReason() == null ? "PROJECT_CONVENTION_SESSION_FAILED"
                    : switch (run.closeReason()) {
                        case INTERACTION_FORBIDDEN ->
                                "PROJECT_CONVENTION_CANDIDATE_INTERACTION_FORBIDDEN";
                        case NORMAL_COMPLETION_ZERO_SUBMISSION ->
                                "PROJECT_CONVENTION_CANDIDATE_ZERO_SUBMISSION";
                        case OWNER_REQUESTED -> "PROJECT_CONVENTION_CANCELLED";
                        case REMOTE_FAILED, TIMEOUT -> "PROJECT_CONVENTION_SESSION_FAILED";
                    };
            case OPEN -> throw stale("Convention candidate run is not terminal");
        };
        GenericCandidateInternalTerminationPreparer.IntentKind kind = switch (run.state()) {
            case ACCEPTED -> GenericCandidateInternalTerminationPreparer.IntentKind.RUN_COMPLETED;
            case CLOSED -> run.closeReason() == MachineCandidateSubmission.CandidateCloseReason.OWNER_REQUESTED
                    ? GenericCandidateInternalTerminationPreparer.IntentKind.OWNER_CANCEL
                    : GenericCandidateInternalTerminationPreparer.IntentKind.PROTOCOL_FAILURE;
            case WAITING_INPUT, FALLBACK_REQUIRED ->
                    GenericCandidateInternalTerminationPreparer.IntentKind.PROTOCOL_FAILURE;
            case OPEN -> throw stale("Convention candidate run is not terminal");
        };
        return terminate(draft, launch, kind, reason,
                "Convention candidate ended in " + run.state().name(), false);
    }

    private Result advanceIntentIfPresent(ProjectConventionDraftRow draft,
                                          GenericCandidateInternalLaunchRow launch) {
        GenericCandidateInternalTerminationIntentRow intent =
                intentStore.findForLaunch(launch.id()).orElse(null);
        if (intent == null) return null;
        GenericCandidateInternalTerminationCoordinator.Result result = terminations.advance(intent.id());
        if (result.status() == GenericCandidateInternalTerminationCoordinator.Status.DISCONNECTED) {
            GenericCandidateInternalTerminationIntentRow current = intentStore.require(intent.id());
            return Result.disconnected(result.code(), safe(current.lastErrorDetail()));
        }
        if (result.status() != GenericCandidateInternalTerminationCoordinator.Status.READY) {
            return Result.running();
        }
        intent = intentStore.require(intent.id());
        launch = requireLaunch(launch.id());
        if (CAPABILITY_UNAVAILABLE.equals(intent.reasonCode())) {
            intentStore.complete(intent);
            return Result.legacy();
        }
        boolean settled = settlements.settle(current(draft), launch, intent,
                intent.reasonCode(), intent.lastErrorDetail());
        if (!settled) return Result.running();
        return resultFor(current(draft));
    }

    private Result terminate(ProjectConventionDraftRow draft,
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
        boolean settled = settlements.settle(current(draft), requireLaunch(launch.id()), intent,
                reasonCode, detail);
        return settled ? resultFor(current(draft)) : Result.running();
    }

    private void freezeSource(Context context, ProjectConventionDraftRow draft,
                              GenericCandidateInternalLaunchRow launch) {
        CandidatePolicy.Context planned = new CandidatePolicy.Context(
                launch.candidateRunId(), MachineCandidateSubmission.CandidateScope.project(draft.projectId()),
                MachineCandidateSubmission.CandidateOwnerRef.projectConventionDraft(draft.id()),
                MachineCandidateKind.PROJECT_CONVENTION_V1,
                ProjectConventionCandidatePolicy.WORKFLOW_STEP, requireSourceRevision(draft),
                launch.preparedOwnerVersion() + 1,
                ProjectConventionCandidatePolicy.CONTRACT_VERSION,
                ProjectConventionCandidatePolicy.MAX_ATTEMPTS, 0);
        sourceSnapshots.freeze(planned, draft.sourceExists() == 1, draft.sourceSha256(),
                draft.sourceContent(), draft.projectStackProfileId(), draft.stackFingerprint(),
                evidenceCapture.capture(context.projectRoot(), context.stackSnapshot()));
    }

    private CandidatePromptDispatchService.Result dispatchInitial(
            GenericCandidateInternalLaunchRow launch,
            MachineCandidateSubmission.RunSnapshot run) {
        ProjectConventionCompilation.Input input = inputs.load(new CandidatePolicy.Context(
                run.runId(), run.scope(), run.owner(), run.candidateKind(), run.workflowStep(),
                run.sourceRevision(), run.ownerVersion(), run.contractVersion(), run.maxAttempts(),
                run.attemptsUsed()));
        String tool = launches.actualToolName(launch);
        String text = promptFactory.internal(run, input.evidenceCatalog(), tool);
        String messageId = CandidatePromptDispatchService.initialMessageId(run.runId());
        OpenCodeClient.PromptRequest request = new OpenCodeClient.PromptRequest(
                text, null, null, new OpenCodeClient.ResponseFormat.Text(), messageId, List.of());
        return promptDispatches.advanceInitial(run, CandidateLaunchRef.genericV1(launch.id()),
                remote(launch), request, () -> true, promptIo,
                "convention-initial:" + run.owner().id(), Instant.now());
    }

    private GenericCandidateInternalLaunchPreparer.PrepareCommand command(
            Context context, ProjectConventionDraftRow draft) {
        return new GenericCandidateInternalLaunchPreparer.PrepareCommand(
                MachineCandidateKind.PROJECT_CONVENTION_V1,
                MachineCandidateSubmission.CandidateScope.project(draft.projectId()),
                MachineCandidateSubmission.CandidateOwnerRef.projectConventionDraft(draft.id()),
                requireSourceRevision(draft), draft.version(), context.projectRoot(), context.model(),
                OpenCodeClient.SessionProfile.PROJECT_CONVENTION_CANDIDATE_READ_ONLY);
    }

    private static void requirePersistedIdentity(
            Context context, ProjectConventionDraftRow draft,
            GenericCandidateInternalLaunchRow launch) {
        if (!MachineCandidateKind.PROJECT_CONVENTION_V1.name().equals(launch.candidateKind())
                || !ProjectConventionCandidatePolicy.WORKFLOW_STEP.equals(launch.workflowStep())
                || !ProjectConventionCandidatePolicy.CONTRACT_VERSION.equals(launch.contractVersion())
                || !"PROJECT_CONVENTION_DRAFT".equals(launch.ownerType())
                || !draft.id().equals(launch.ownerId())
                || !draft.id().equals(launch.projectConventionDraftId())
                || !draft.projectId().equals(launch.projectId())
                || launch.sourceRevision() != requireSourceRevision(draft)
                || !Path.of(launch.canonicalDirectory()).equals(context.projectRoot())
                || !OpenCodeClient.SessionProfile.PROJECT_CONVENTION_CANDIDATE_READ_ONLY.name()
                        .equals(launch.profile())) {
            throw stale("Persisted Convention launch no longer matches its frozen owner");
        }
    }

    private MachineCandidateSubmission.RunSnapshot close(
            MachineCandidateSubmission.RunSnapshot run,
            MachineCandidateSubmission.CandidateCloseReason reason) {
        return run.state() == MachineCandidateRunState.OPEN
                ? submissions.close(new MachineCandidateSubmission.CloseCommand(
                        run.runId(), run.version(), reason)) : run;
    }

    private Result failBeforeRemote(ProjectConventionDraftRow draft, String code, String detail) {
        ProjectConventionDraftRow current = current(draft);
        if (!ProjectConventionState.RUNNING.name().equals(current.state())) return resultFor(current);
        ProjectConventionDraftRow failed = new ProjectConventionDraftRow(
                current.id(), current.projectId(), ProjectConventionState.FAILED.name(), null,
                "NO_REMOTE_CREATED", current.sourceExists(), current.sourceSha256(), current.sourceContent(),
                null, current.normalizationNotice(), safe(code + ": " + detail), current.createdAt(),
                Instant.now().toString(), current.version(), current.projectStackProfileId(),
                current.stackFingerprint(), current.responseMode(), current.sourceRevision());
        lifecycle.transition(subject(failed), current.state(), failed.state(), LifecycleEvent.FAIL,
                code, Map.of(), () -> mapper.updateProjectConventionDraft(failed),
                () -> stale("Convention pre-remote failure raced"));
        return Result.failed(code, detail);
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

    private static boolean legacyReplacementComplete(
            GenericCandidateInternalLaunchRow launch,
            GenericCandidateInternalTerminationIntentRow intent) {
        return intent != null
                && GenericCandidateInternalLaunchState.STALE.name().equals(launch.state())
                && GenericCandidateInternalTerminationIntentState.COMPLETED.name().equals(intent.state())
                && GenericCandidateInternalTerminationPreparer.IntentKind.OWNER_REPLACEMENT.name()
                        .equals(intent.intentKind())
                && CAPABILITY_UNAVAILABLE.equals(intent.reasonCode());
    }

    private ProjectConventionDraftRow current(ProjectConventionDraftRow draft) {
        return mapper.findProjectConventionDraft(draft.id())
                .orElseThrow(() -> stale("Convention draft owner is missing"));
    }
    private GenericCandidateInternalLaunchRow requireLaunch(String id) {
        return mapper.findGenericCandidateInternalLaunch(id)
                .orElseThrow(() -> stale("Convention candidate launch is missing"));
    }
    private static long requireSourceRevision(ProjectConventionDraftRow draft) {
        if (draft.sourceRevision() == null || draft.sourceRevision() < 0) {
            throw stale("Convention source revision is missing");
        }
        return draft.sourceRevision();
    }
    private static OpenCodeClient.OpenCodeSession remote(GenericCandidateInternalLaunchRow launch) {
        return new OpenCodeClient.OpenCodeSession(launch.externalSessionId(),
                Path.of(launch.canonicalDirectory()), launch.runtimeGenerationId(),
                launch.internalMcpServer());
    }
    private void reject(OpenCodeClient.OpenCodeSession remote, String id) {
        try { openCode.rejectQuestion(remote, id); }
        catch (RuntimeException ignored) { }
    }
    private static GenericCandidateInternalLaunchState state(GenericCandidateInternalLaunchRow row) {
        return GenericCandidateInternalLaunchState.valueOf(row.state());
    }
    private static Result resultFor(ProjectConventionDraftRow draft) {
        if (ProjectConventionState.READY.name().equals(draft.state())) return Result.ready();
        if (ProjectConventionState.CANCELLED.name().equals(draft.state())) return Result.cancelled();
        if (ProjectConventionState.FAILED.name().equals(draft.state())) {
            return Result.failed("PROJECT_CONVENTION_CANDIDATE_FAILED", draft.errorMessage());
        }
        return Result.running();
    }
    private static LifecycleTransitionService.Subject subject(ProjectConventionDraftRow row) {
        return new LifecycleTransitionService.Subject(LifecycleMachineType.PROJECT_CONVENTION,
                row.id(), LifecycleScopeType.PROJECT, row.projectId());
    }
    private static String code(RuntimeException failure, String fallback) {
        if (failure instanceof ConflictException conflict) return conflict.code();
        if (failure instanceof SessionFailure session) return session.code();
        return fallback;
    }
    private static String safe(String value) {
        String normalized = value == null || value.isBlank() ? "Project convention candidate failed"
                : value.replaceAll("[\\r\\n]+", " ").trim();
        return normalized.substring(0, Math.min(2_000, normalized.length()));
    }
    private static ConflictException stale(String detail) {
        return new ConflictException("PROJECT_CONVENTION_CANDIDATE_WORKFLOW_STALE", detail);
    }

    record Context(ProjectConventionDraftRow draft, Path projectRoot,
                   ProjectStackSnapshot stackSnapshot, OpenCodeClient.OpenCodeModel model,
                   boolean ownerStopping) { }
    enum Action { RUNNING, LEGACY_FALLBACK, READY, FAILED, CANCELLED, DISCONNECTED }
    record Result(Action action, String code, String detail) {
        static Result running() { return new Result(Action.RUNNING, null, null); }
        static Result legacy() { return new Result(Action.LEGACY_FALLBACK, null, null); }
        static Result ready() { return new Result(Action.READY, null, null); }
        static Result failed(String code, String detail) { return new Result(Action.FAILED, code, detail); }
        static Result cancelled() { return new Result(Action.CANCELLED, null, null); }
        static Result disconnected(String code, String detail) {
            return new Result(Action.DISCONNECTED, code, detail);
        }
    }
}
