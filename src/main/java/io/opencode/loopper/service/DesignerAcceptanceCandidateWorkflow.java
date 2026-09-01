package io.opencode.loopper.service;
import io.opencode.loopper.domain.DesignWorkflowPhase;
import io.opencode.loopper.domain.DesignerActor;
import io.opencode.loopper.domain.DesignerSessionState;
import io.opencode.loopper.domain.LoopSpecCompilationState;
import io.opencode.loopper.domain.ModelResponseMode;
import io.opencode.loopper.domain.SessionFailure;
import io.opencode.loopper.persistence.DesignAcceptancePlanningRow;
import io.opencode.loopper.persistence.DesignRequirementRevisionRow;
import io.opencode.loopper.persistence.DesignWorkPackageRow;
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.LoopSpecCompilationRow;
import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
final class DesignerAcceptanceCandidateWorkflow {
    static final String CORRECTION_WAITING_INPUT_PENDING = "ACCEPTANCE_CORRECTION_WAITING_INPUT_PENDING";
    static final String CORRECTION_ABORT_DISPATCHED = AcceptanceCandidateOwnerCheckpoint.CORRECTION_ABORT_DISPATCHED;
    private final DesignerAcceptanceWorkflow acceptanceWorkflow;
    private final DesignerAcceptanceCandidateOrchestrator acceptanceCandidates;
    private final AcceptanceCandidateProofService acceptanceCandidateProofs;
    private final AcceptanceCandidateLegacyHandoffCoordinator acceptanceLegacyHandoffs;
    private final ProjectService projects;
    private final DesignerModelPromptTransport modelPrompts;
    private final CandidatePromptDispatchService candidatePromptDispatches;
    private final AcceptanceCandidateInternalLaunchPreparer internalLaunchPreparer;
    private final AcceptanceCandidateInternalLaunchCoordinator internalLaunches;
    private final DesignerAcceptanceInitialPromptFailureRecovery initialPromptFailures;
    DesignerAcceptanceCandidateWorkflow(
            DesignerAcceptanceWorkflow acceptanceWorkflow,
            DesignerAcceptanceCandidateOrchestrator acceptanceCandidates,
            AcceptanceCandidateProofService acceptanceCandidateProofs,
            AcceptanceCandidateLegacyHandoffCoordinator acceptanceLegacyHandoffs,
            ProjectService projects, DesignerModelPromptTransport modelPrompts,
            CandidatePromptDispatchService candidatePromptDispatches,
            AcceptanceCandidateInternalLaunchPreparer internalLaunchPreparer,
            AcceptanceCandidateInternalLaunchCoordinator internalLaunches,
            DesignerAcceptanceInitialPromptFailureRecovery initialPromptFailures) {
        this.acceptanceWorkflow = acceptanceWorkflow;
        this.acceptanceCandidates = acceptanceCandidates;
        this.acceptanceCandidateProofs = acceptanceCandidateProofs;
        this.acceptanceLegacyHandoffs = acceptanceLegacyHandoffs;
        this.projects = projects;
        this.modelPrompts = modelPrompts;
        this.candidatePromptDispatches = candidatePromptDispatches;
        this.internalLaunchPreparer = internalLaunchPreparer;
        this.internalLaunches = internalLaunches;
        this.initialPromptFailures = initialPromptFailures;
    }
    boolean poll(Port host, LoopSpecCompilationRow compilation, DesignerSessionRow session,
            OpenCodeClient.OpenCodeModel model, boolean timedOut) {
        DesignAcceptancePlanningRow planning = acceptanceWorkflow.find(compilation.id()).orElse(null);
        if (planning == null || !DesignerAcceptancePlanning.CONTRACT_VERSION_V7.equals(
                planning.contractVersion())) return false;
        DesignerAcceptanceWorkflow.RoutingResult routing = acceptanceWorkflow.frozenRoute(compilation.id());
        ProjectRow project = projects.get(session.projectId());
        boolean internal = internalLaunchPreparer != null && internalLaunches != null;
        DesignRequirementRevisionRow revision = internal ? host.currentRequirement().apply(session.id()) : null;
        DesignWorkPackageRow workPackage = internal ? host.requireCurrentPackage()
                .apply(session, compilation.workPackageId()) : null;
        InternalLaunchProgress launch = internal ? advanceInternalLaunch(host, compilation, session, revision,
                workPackage, planning, routing, model) : InternalLaunchProgress.none();
        if (launch.handled()) return true;
        if (launch.launchId() != null) compilation = host.getCompilation().apply(compilation.id());
        if (pendingCorrectionStop(compilation)) {
            if (workPackage == null) workPackage = host.requireCurrentPackage().apply(session, compilation.workPackageId());
            DesignerAcceptanceCandidateOrchestrator.CorrectionStopTarget target = acceptanceCandidates
                    .correctionStopTarget(compilation, Path.of(project.rootPath()));
            recoverRejectedCorrection(host, compilation, session, workPackage, planning, routing, target);
            return true;
        }
        DesignerAcceptanceCandidateOrchestrator.Poll polled = acceptanceCandidates.poll(compilation, planning,
                routing, Path.of(project.rootPath()), timedOut);
        if (polled.action() == DesignerAcceptanceCandidateOrchestrator.Action.NONE) return launch.launchId() != null;
        if (revision == null) revision = host.currentRequirement().apply(session.id());
        if (workPackage == null) workPackage = host.requireCurrentPackage().apply(session, compilation.workPackageId());
        switch (polled.action()) {
            case RUNNING -> updateRunning(host, compilation, session, revision, workPackage, polled);
            case ACCEPTED -> {
                if (!candidatePromptDispatches.completeForRun(polled.run().runId(), polled.state())) return true;
                host.completeAccepted().apply(compilation, session, workPackage,
                        polled.remote(), polled.run(), polled.state());
            }
            case WAITING_INPUT -> {
                if (!candidatePromptDispatches.completeForRun(polled.run().runId(), polled.state())) return true;
                host.waitForInput().apply(compilation, session, workPackage,
                        "ACCEPTANCE_CANDIDATE_WAITING_INPUT", polled.problemSummary(),
                        polled.submission().problems(), polled.run(), polled.state());
            }
            case START_LEGACY -> {
                if (!candidatePromptDispatches.completeForRun(polled.run().runId(), polled.state())) return true;
                LoopSpecCompilationRow stopped = acceptanceCandidateProofs.persistIfOwned(
                        polled.run(), polled.state()).orElse(null);
                if (stopped == null) return true;
                host.dispatchLegacy().apply(stopped, session, revision, workPackage, planning, routing, null, polled.state());
            }
            case START_LEGACY_HANDOFF -> host.dispatchLegacy().apply(compilation, session, revision,
                    workPackage, planning, routing, null, polled.state());
            case REJECTED -> dispatchCorrection(host, compilation, session, revision, workPackage, polled,
                    internalLaunchId(polled.run(), launch.launchId()));
            case FAILED -> {
                LoopSpecCompilationRow failed = host.getCompilation().apply(compilation.id());
                if (CandidateSessionTerminationProof.persisted(polled.state())) {
                    if (polled.run() != null && !candidatePromptDispatches.completeForRun(
                            polled.run().runId(), polled.state())) return true;
                    failed = acceptanceCandidateProofs.persistIfOwned(polled.run(), polled.state()).orElse(null);
                    if (failed == null) return true;
                }
                host.failPackageCompilation().apply(failed, session, polled.code(), polled.detail(), false);
            }
            case NONE -> { }
        }
        return true;
    }
    private InternalLaunchProgress advanceInternalLaunch(Port host, LoopSpecCompilationRow compilation,
            DesignerSessionRow session, DesignRequirementRevisionRow revision, DesignWorkPackageRow workPackage,
            DesignAcceptancePlanningRow planning, DesignerAcceptanceWorkflow.RoutingResult routing,
            OpenCodeClient.OpenCodeModel model) {
        if (internalLaunchPreparer == null || internalLaunches == null) return InternalLaunchProgress.none();
        // A durable legacy handoff is the authority once it exists. Re-running internal
        // preflight here would repeatedly redispatch the same handoff on unmanaged runtimes
        // and would starve polling of the already-open legacy candidate run.
        if (acceptanceLegacyHandoffs.exists(compilation.id())) return InternalLaunchProgress.none();
        var decision = acceptanceCandidates.decide(planning, routing);
        boolean create = decision != null && decision.action()
                == AcceptanceClosedChoiceCandidateCoordinator.Action.OPEN_INTERNAL_MCP;
        Optional<AcceptanceCandidateInternalLaunchPreparer.Prepared> prepared;
        try {
            prepared = internalLaunchPreparer.prepareFrozen(
                    compilation, session, workPackage, planning, routing, model, create);
        } catch (SessionFailure unavailable) {
            if (!"CANDIDATE_MANAGED_RUNTIME_REQUIRED".equals(unavailable.code())
                    || !LoopSpecCompilationState.PENDING_HANDOFF.name().equals(compilation.state())
                    || compilation.externalSessionId() != null) throw unavailable;
            host.dispatchLegacy().apply(compilation, session, revision,
                    workPackage, planning, routing, null, null);
            return InternalLaunchProgress.handled(null);
        }
        if (prepared.isEmpty()) return InternalLaunchProgress.none();
        var frozen = prepared.orElseThrow().row();
        if ("STALE".equals(frozen.state())) {
            LoopSpecCompilationRow owner = host.getCompilation().apply(compilation.id());
            if ("OPENCODE_EXACT_LOOKUP_UNSUPPORTED".equals(frozen.lastErrorCode())
                    && "PENDING_HANDOFF".equals(owner.state()) && owner.externalSessionId() == null) {
                host.dispatchLegacy().apply(owner, session, revision,
                        workPackage, planning, routing, null, null);
                return InternalLaunchProgress.handled(frozen.id());
            }
            return "RUNNING".equals(owner.state()) ? InternalLaunchProgress.none()
                    : InternalLaunchProgress.handled(frozen.id());
        }
        AcceptanceCandidateInternalLaunchCoordinator.Result result = internalLaunches.advance(compilation.id());
        if (result.status() == AcceptanceCandidateInternalLaunchCoordinator.Status.LEGACY_FALLBACK) {
            if (!"STALE".equals(result.launch().state())) throw staleInternalLaunch();
            LoopSpecCompilationRow owner = host.getCompilation().apply(compilation.id());
            if (!"PENDING_HANDOFF".equals(owner.state()) || owner.externalSessionId() != null) {
                throw staleInternalLaunch();
            }
            host.dispatchLegacy().apply(owner, session, revision, workPackage, planning, routing, null, null);
            return InternalLaunchProgress.handled(result.launch().id());
        }
        if (result.status() == AcceptanceCandidateInternalLaunchCoordinator.Status.FAILED_STOPPED) {
            if (initialPromptFailures != null && initialPromptFailures.recover(
                    host, compilation, session, revision, workPackage, planning, routing)) {
                return InternalLaunchProgress.handled(result.launch().id());
            }
            host.failPackageCompilation().apply(host.getCompilation().apply(compilation.id()), session,
                    result.code(), result.detail(), false);
            return InternalLaunchProgress.handled(result.launch().id());
        }
        if (result.status() != AcceptanceCandidateInternalLaunchCoordinator.Status.SETTLED) {
            return InternalLaunchProgress.handled(result.launch().id());
        }
        DesignerAcceptanceCandidateOrchestrator.Start start = acceptanceCandidates
                .settledInternal(result, planning, routing);
        LoopSpecCompilationRow current = host.getCompilation().apply(compilation.id());
        if (!"CANDIDATE_PROMPT_PENDING".equals(current.externalSessionState())) {
            return InternalLaunchProgress.ready(start.internalLaunchId());
        }
        DesignerSessionRow owner = host.getSession().apply(session.id());
        if (!"CANDIDATE_PROMPT_PENDING".equals(owner.externalSessionState())) {
            host.updateDesignerProjection().apply(owner, DesignerSessionState.RUNNING,
                    DesignWorkflowPhase.COMPILING, start.remote().id(), "CANDIDATE_PROMPT_PENDING",
                    owner.designRevision(), owner.redesignCount(), revision.revision(), workPackage.packageId());
        }
        if (start.run().attemptsUsed() == 0) {
            String messageId = CandidatePromptDispatchService.initialMessageId(start.run().runId());
            var prompt = modelPrompts.prepare(start.prompt(), ModelResponseMode.TEXT_MARKER.name(), null,
                    session.id(), workPackage.packageId(), messageId);
            CandidatePromptDispatchService.Result dispatched = candidatePromptDispatches.advanceInitial(
                    start.run(), start.internalLaunchId(), start.remote(), prompt.request(),
                    () -> reserveModelCall(host, revision), promptIo(),
                    "acceptance-initial:" + compilation.id(), Instant.now());
            if (dispatched.status() != CandidatePromptDispatchService.Status.ACKNOWLEDGED) {
                if (initialPromptFailures != null) initialPromptFailures.request(host, compilation, session,
                        revision, workPackage, planning, routing, dispatched.status());
                return InternalLaunchProgress.handled(start.internalLaunchId());
            }
        }
        host.markCandidateRunning().apply(current, start.internalLaunchId(), start.run());
        DesignerSessionRow projected = host.updateDesignerProjection().apply(host.getSession().apply(session.id()),
                DesignerSessionState.RUNNING, DesignWorkflowPhase.COMPILING, start.remote().id(),
                "CANDIDATE_RUNNING", session.designRevision(), session.redesignCount(),
                revision.revision(), workPackage.packageId());
        host.publish().apply(projected, "STATUS", DesignerActor.COMPILER, true, "",
                workPackage.packageId() + " 正在同一无工具 Session 中提交 v7 验收闭集候选");
        return InternalLaunchProgress.ready(start.internalLaunchId());
    }
    private void dispatchCorrection(Port host, LoopSpecCompilationRow compilation, DesignerSessionRow session,
            DesignRequirementRevisionRow revision, DesignWorkPackageRow workPackage,
            DesignerAcceptanceCandidateOrchestrator.Poll polled, String internalLaunchId) {
        String messageId = CandidatePromptDispatchService.messageId(
                polled.run().runId(), polled.submission().attemptOrdinal());
        DesignerModelPromptTransport.PreparedPrompt prepared = modelPrompts.prepare(polled.prompt(),
                ModelResponseMode.TEXT_MARKER.name(), null, session.id(), workPackage.packageId(), messageId);
        CandidatePromptDispatchService.Result result = candidatePromptDispatches.advance(
                polled.run(), polled.submission(), internalLaunchId, polled.remote(), prepared.request(),
                () -> reserveModelCall(host, revision), promptIo(),
                "acceptance-correction:" + compilation.id(), Instant.now());
        if (result.status() == CandidatePromptDispatchService.Status.ACKNOWLEDGED) {
            host.publish().apply(session, "STATUS", DesignerActor.COMPILER, true, "",
                    workPackage.packageId() + " 正在同一兼容 Session 中机械修正闭集选择");
        } else if (result.status() == CandidatePromptDispatchService.Status.RESULT_UNKNOWN) {
            disconnectHandoff(host, compilation, session, polled.remote(),
                    "OPENCODE_PROMPT_RESULT_UNKNOWN",
                    "候选修正提示已越过远端 POST 边界，但尚无可恢复的精确确认；禁止重发或回退");
        } else if (result.status() == CandidatePromptDispatchService.Status.BUDGET_EXHAUSTED
                || result.status() == CandidatePromptDispatchService.Status.LOOKUP_UNSUPPORTED) {
            stopRejectedCorrection(host, compilation, session, workPackage, polled, result.status().name());
        }
    }
    private void stopRejectedCorrection(Port host, LoopSpecCompilationRow compilation, DesignerSessionRow session,
            DesignWorkPackageRow workPackage, DesignerAcceptanceCandidateOrchestrator.Poll polled, String reason) {
        AcceptanceCandidateCorrectionStopReason stopReason = AcceptanceCandidateCorrectionStopReason.parse(reason);
        LoopSpecCompilationRow marker = checkpointCorrectionStop(host, compilation, session, stopReason);
        recoverRejectedCorrection(host, marker, session, workPackage,
                acceptanceWorkflow.find(compilation.id()).orElseThrow(),
                acceptanceWorkflow.frozenRoute(compilation.id()),
                new DesignerAcceptanceCandidateOrchestrator.CorrectionStopTarget(polled.remote(), polled.run()));
    }
    private LoopSpecCompilationRow checkpointCorrectionStop(Port host, LoopSpecCompilationRow input,
            DesignerSessionRow session, AcceptanceCandidateCorrectionStopReason reason) {
        LoopSpecCompilationRow current = host.getCompilation().apply(input.id());
        if (pendingCorrectionStop(current)) {
            AcceptanceCandidateCorrectionStopReason.parse(current.lastErrorDetail());
            return current;
        }
        if (!LoopSpecCompilationState.RUNNING.name().equals(current.state())) {
            throw new ConflictException("ACCEPTANCE_CORRECTION_MARKER_OWNER_STALE",
                    "验收候选修正停止意图的 compilation owner 已变化");
        }
        return host.updateCompilation().apply(current, LoopSpecCompilationState.RUNNING,
                current.externalSessionId(), AcceptanceCandidateOwnerCheckpoint.CORRECTION_STOP_REQUESTED,
                current.repairCount(),
                CORRECTION_WAITING_INPUT_PENDING, reason.name(), session.projectId());
    }
    private void recoverRejectedCorrection(Port host, LoopSpecCompilationRow input,
            DesignerSessionRow session, DesignWorkPackageRow workPackage,
            DesignAcceptancePlanningRow planning,
            DesignerAcceptanceWorkflow.RoutingResult routing,
            DesignerAcceptanceCandidateOrchestrator.CorrectionStopTarget target) {
        LoopSpecCompilationRow marker = host.getCompilation().apply(input.id());
        if (!pendingCorrectionStop(marker)) {
            throw new ConflictException("ACCEPTANCE_CORRECTION_MARKER_MISSING",
                    "验收候选修正停止意图未持久化");
        }
        AcceptanceCandidateCorrectionStopReason reason =
                AcceptanceCandidateCorrectionStopReason.parse(marker.lastErrorDetail());
        MachineCandidateSubmission.RunSnapshot run = target.run();
        if (!candidatePromptDispatches.prepareRunTermination(run.runId(), Instant.now())) return;
        String proof = CandidateSessionTerminationProof.persisted(marker.externalSessionState())
                ? marker.externalSessionState() : null;
        DesignerAcceptanceCandidateOrchestrator.StopResult stopped = proof == null ? null
                : terminalRace(run)
                ? DesignerAcceptanceCandidateOrchestrator.StopResult.terminalRace(run, proof)
                : ownerClosed(run)
                ? DesignerAcceptanceCandidateOrchestrator.StopResult.ownerStopped(proof) : null;
        if (proof == null) {
            if (AcceptanceCandidateOwnerCheckpoint.CORRECTION_STOP_REQUESTED.equals(
                    marker.externalSessionState())) {
                LoopSpecCompilationRow current = host.getCompilation().apply(marker.id());
                marker = host.updateCompilation().apply(current, LoopSpecCompilationState.RUNNING,
                        target.remote().id(), CORRECTION_ABORT_DISPATCHED, current.repairCount(),
                        CORRECTION_WAITING_INPUT_PENDING, reason.name(), session.projectId());
                if (run.state() == io.opencode.loopper.domain.MachineCandidateRunState.OPEN) {
                    stopped = acceptanceCandidates.stopOpened(target.remote(), run);
                } else if (run.state().terminal()) {
                    stopped = acceptanceCandidates.observeStopped(target.remote(), run);
                } else {
                    throw staleCorrectionStopTarget();
                }
            } else if (CORRECTION_ABORT_DISPATCHED.equals(marker.externalSessionState())
                    && (run.state() == io.opencode.loopper.domain.MachineCandidateRunState.OPEN
                    || run.state().terminal())) {
                stopped = acceptanceCandidates.observeStopped(target.remote(), run);
            } else {
                throw staleCorrectionStopTarget();
            }
            if (stopped.outcome() == DesignerAcceptanceCandidateOrchestrator.StopOutcome.UNCONFIRMED) return;
            proof = stopped.proof();
            if (stopped.outcome() == DesignerAcceptanceCandidateOrchestrator.StopOutcome.TERMINAL_RACE) {
                settleTerminalRace(host, marker, session, workPackage, planning, routing,
                        target.remote(), stopped);
                return;
            }
            LoopSpecCompilationRow current = host.getCompilation().apply(marker.id());
            if (!pendingCorrectionStop(current)) return;
            AcceptanceCandidateCorrectionStopReason.parse(current.lastErrorDetail());
            marker = host.updateCompilation().apply(current, LoopSpecCompilationState.RUNNING,
                    target.remote().id(), proof, current.repairCount(),
                    CORRECTION_WAITING_INPUT_PENDING, reason.name(), session.projectId());
        }
        if (stopped != null
                && stopped.outcome() == DesignerAcceptanceCandidateOrchestrator.StopOutcome.TERMINAL_RACE) {
            settleTerminalRace(host, marker, session, workPackage, planning, routing,
                    target.remote(), stopped);
            return;
        }
        if (stopped == null) throw staleCorrectionStopTarget();
        List<MachineCandidateSubmission.Problem> problems =
                candidatePromptDispatches.rejectedProblems(run.runId());
        LoopSpecCompilationRow settledMarker = marker;
        String settledProof = proof;
        candidatePromptDispatches.settleForRun(run.runId(), proof,
                () -> host.waitForInput().apply(settledMarker, session, workPackage, reason.finalCode(),
                        "候选修正提示无法安全派发：" + reason.name(), problems, run, settledProof));
    }
    private void settleTerminalRace(Port host, LoopSpecCompilationRow marker,
            DesignerSessionRow session, DesignWorkPackageRow workPackage,
            DesignAcceptancePlanningRow planning, DesignerAcceptanceWorkflow.RoutingResult routing,
            OpenCodeClient.OpenCodeSession remote,
            DesignerAcceptanceCandidateOrchestrator.StopResult stopped) {
        DesignerAcceptanceCandidateOrchestrator.Poll terminal =
                acceptanceCandidates.routeTerminalAfterStop(marker.id(), remote, stopped);
        candidatePromptDispatches.settleForRun(terminal.run().runId(), stopped.proof(), () -> {
            LoopSpecCompilationRow proofed = acceptanceCandidateProofs.persist(
                    terminal.run(), stopped.proof());
            settleTerminalAction(host, proofed, session, workPackage, planning, routing, terminal);
        });
    }
    private void settleTerminalAction(Port host, LoopSpecCompilationRow proofed,
            DesignerSessionRow session, DesignWorkPackageRow workPackage,
            DesignAcceptancePlanningRow planning, DesignerAcceptanceWorkflow.RoutingResult routing,
            DesignerAcceptanceCandidateOrchestrator.Poll terminal) {
        switch (terminal.action()) {
            case ACCEPTED -> host.completeAccepted().apply(
                    proofed, session, workPackage, terminal.remote(), terminal.run(), terminal.state());
            case WAITING_INPUT -> host.waitForInput().apply(
                    proofed, session, workPackage, "ACCEPTANCE_CANDIDATE_WAITING_INPUT",
                    terminal.problemSummary(), terminal.submission().problems(), terminal.run(), terminal.state());
            case START_LEGACY -> host.dispatchLegacy().apply(
                    proofed, session, host.currentRequirement().apply(session.id()), workPackage,
                    planning, routing, null, terminal.state());
            case FAILED -> host.failPackageCompilation().apply(
                    proofed, session, terminal.code(), terminal.detail(), false);
            default -> throw new ConflictException("ACCEPTANCE_CANDIDATE_TERMINAL_RACE_STALE",
                    "验收闭集候选终态竞态未能进入既有权威终态路由");
        }
    }
    private static boolean pendingCorrectionStop(LoopSpecCompilationRow compilation) {
        return compilation != null
                && CORRECTION_WAITING_INPUT_PENDING.equals(compilation.lastErrorCode());
    }
    private static boolean ownerClosed(MachineCandidateSubmission.RunSnapshot run) {
        return run.state() == io.opencode.loopper.domain.MachineCandidateRunState.CLOSED
                && run.closeReason() == MachineCandidateSubmission.CandidateCloseReason.OWNER_REQUESTED;
    }
    private static boolean terminalRace(MachineCandidateSubmission.RunSnapshot run) {
        return run.state().terminal() && !ownerClosed(run);
    }
    private static ConflictException staleCorrectionStopTarget() {
        return new ConflictException("ACCEPTANCE_CORRECTION_STOP_TARGET_STALE",
                "验收候选修正停止目标不再处于可恢复状态");
    }
    boolean resumeLegacyHandoffIfRequired(Port host, LoopSpecCompilationRow compilation,
            DesignerSessionRow session, DesignRequirementRevisionRow revision,
            DesignWorkPackageRow workPackage, DesignAcceptancePlanningRow planning,
            DesignerAcceptanceWorkflow.RoutingResult routing, OpenCodeClient.OpenCodeModel model,
            String unopenedProof) {
        if (unopenedProof == null && !acceptanceLegacyHandoffs.exists(compilation.id())) return false;
        startLegacyHandoff(host, compilation, session, revision, workPackage, planning, routing,
                model, null, unopenedProof);
        return true;
    }
    void dispatchLegacy(Port host, ProjectRow project, LoopSpecCompilationRow compilation,
            DesignerSessionRow session, DesignRequirementRevisionRow revision,
            DesignWorkPackageRow workPackage, DesignAcceptancePlanningRow planning,
            DesignerAcceptanceWorkflow.RoutingResult routing, OpenCodeClient.OpenCodeModel model,
            MachineCandidateSubmission.SubmissionResult rejected, String unopenedProof) {
        if (resumeLegacyHandoffIfRequired(host, compilation, session, revision, workPackage,
                planning, routing, model, unopenedProof)) return;
        LoopSpecCompilationRow durableOwner = "RUNNING".equals(compilation.state()) ? compilation
                : host.updateCompilation().apply(compilation, LoopSpecCompilationState.RUNNING,
                null, "LEGACY_PREPARING", compilation.repairCount(), null, null, session.projectId());
        startLegacyHandoff(host, durableOwner, session, revision, workPackage, planning, routing,
                model, null, null);
    }
    private boolean reserveModelCall(Port host, DesignRequirementRevisionRow input) {
        DesignRequirementRevisionRow current = host.getRequirement().apply(input.id());
        if (current.modelCallsUsed() >= current.maxModelCalls()) return false;
        try {
            host.updateRequirementUsage().apply(current, current.modelCallsUsed() + 1);
            return true;
        } catch (ConflictException concurrent) {
            DesignRequirementRevisionRow latest = host.getRequirement().apply(input.id());
            if (latest.modelCallsUsed() >= latest.maxModelCalls()) return false;
            throw concurrent;
        }
    }
    private CandidatePromptDispatchService.PromptIo promptIo() {
        return new CandidatePromptDispatchService.PromptIo() {
            @Override public OpenCodeClient.MessageLookup lookup(OpenCodeClient.OpenCodeSession remote,
                    OpenCodeClient.PromptRequest request, String sha256) {
                return modelPrompts.lookupPrompt(remote,
                        new DesignerModelPromptTransport.PreparedPrompt(request, sha256));
            }
            @Override public void dispatch(OpenCodeClient.OpenCodeSession remote,
                    OpenCodeClient.PromptRequest request) {
                modelPrompts.dispatchPrompt(remote, new DesignerModelPromptTransport.PreparedPrompt(
                        request, OpenCodeClient.promptRequestSha256(request)));
            }
        };
    }
    private static String internalLaunchId(MachineCandidateSubmission.RunSnapshot run, String launchId) {
        if (run.submissionChannel() != MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP) return null;
        if (launchId == null || launchId.isBlank()) throw staleInternalLaunch();
        return launchId;
    }
    private static ConflictException staleInternalLaunch() {
        return new ConflictException("ACCEPTANCE_INTERNAL_LAUNCH_STALE",
                "验收候选 internal launch 与候选运行不再精确匹配");
    }
    boolean advanceLegacyHandoffIfRequired(Port host, LoopSpecCompilationRow compilation,
            DesignerSessionRow session, OpenCodeClient.OpenCodeModel model) {
        AcceptanceCandidateLegacyHandoffCoordinator.Terminal terminal =
                acceptanceLegacyHandoffs.terminal(compilation.id()).orElse(null);
        if (terminal != null) {
            if ("STALE".equals(terminal.state())) return false;
            if ("FAILED_STOPPED".equals(terminal.state())) {
                host.failPackageCompilation().apply(host.getCompilation().apply(compilation.id()), session,
                        terminal.code(), terminal.detail(), false);
            }
            return true;
        }
        if (!acceptanceLegacyHandoffs.requiresAdvance(compilation.id())) return false;
        DesignWorkPackageRow workPackage = host.requireCurrentPackage().apply(session, compilation.workPackageId());
        DesignAcceptancePlanningRow planning = acceptanceWorkflow.find(compilation.id()).orElseThrow();
        startLegacyHandoff(host, compilation, session, host.currentRequirement().apply(session.id()), workPackage,
                planning, acceptanceWorkflow.frozenRoute(compilation.id()), model, null, null);
        return true;
    }
    void startLegacyHandoff(Port host, LoopSpecCompilationRow compilation,
            DesignerSessionRow session, DesignRequirementRevisionRow revision,
            DesignWorkPackageRow workPackage, DesignAcceptancePlanningRow planning,
            DesignerAcceptanceWorkflow.RoutingResult routing, OpenCodeClient.OpenCodeModel model,
            OpenCodeClient.OpenCodeSession oldRemote, String recoveredProof) {
        AcceptanceCandidateLegacyHandoffCoordinator.Command command =
                new AcceptanceCandidateLegacyHandoffCoordinator.Command(
                        host.getCompilation().apply(compilation.id()), host.getSession().apply(session.id()),
                        host.getRequirement().apply(revision.id()), workPackage.packageId(), planning, routing, model);
        AcceptanceCandidateLegacyHandoffCoordinator.Result result = oldRemote != null
                ? acceptanceLegacyHandoffs.stopOldAndAdvance(command, oldRemote)
                : recoveredProof != null
                ? acceptanceLegacyHandoffs.recoverProofAndAdvance(command, recoveredProof)
                : acceptanceLegacyHandoffs.advance(command);
        DesignerSessionRow current = host.getSession().apply(session.id());
        if (!result.handedOff()) {
            LoopSpecCompilationRow latest = host.getCompilation().apply(compilation.id());
            if ("DISCONNECTED".equals(latest.externalSessionState())
                    && !DesignerSessionState.STOPPING.name().equals(current.state())
                    && !DesignerSessionState.CANCELLED.name().equals(current.state())) {
                host.updateDesignerProjection().apply(current, DesignerSessionState.RUNNING,
                        DesignWorkflowPhase.COMPILING, latest.externalSessionId(), "DISCONNECTED",
                        current.designRevision(), current.redesignCount(), revision.revision(), workPackage.packageId());
            }
            return;
        }
        DesignerSessionRow projected = host.updateDesignerProjection().apply(current, DesignerSessionState.RUNNING,
                DesignWorkflowPhase.COMPILING, result.remote().id(), "CANDIDATE_LEGACY_RUNNING",
                current.designRevision(), current.redesignCount(), revision.revision(), workPackage.packageId());
        host.publish().apply(projected, "STATUS", DesignerActor.COMPILER, true, "",
                workPackage.packageId() + " 正在全新无工具 Session 中使用进程内兼容候选通道");
    }
    void disconnectHandoff(Port host, LoopSpecCompilationRow running, DesignerSessionRow session,
            OpenCodeClient.OpenCodeSession remote, String code, String detail) {
        LoopSpecCompilationRow current = host.getCompilation().apply(running.id());
        if (!same(current.externalSessionId(), remote.id())
                || !same(current.externalSessionState(), "DISCONNECTED")
                || !same(current.lastErrorCode(), code)) {
            host.updateCompilation().apply(current, LoopSpecCompilationState.RUNNING, remote.id(), "DISCONNECTED",
                    current.repairCount(), code, safeMessage(detail), session.projectId());
        }
        DesignerSessionRow owner = host.getSession().apply(session.id());
        if (DesignerSessionState.STOPPING.name().equals(owner.state())
                || DesignerSessionState.CANCELLED.name().equals(owner.state())) return;
        if (!same(owner.externalSessionId(), remote.id())
                || !same(owner.externalSessionState(), "DISCONNECTED")) {
            host.updateDesignerProjection().apply(owner, DesignerSessionState.RUNNING, DesignWorkflowPhase.COMPILING,
                    remote.id(), "DISCONNECTED", owner.designRevision(), owner.redesignCount(),
                    owner.currentRequirementRevision(), owner.activeWorkPackageId());
        }
    }
    private void updateRunning(
            Port host, LoopSpecCompilationRow compilation,
            DesignerSessionRow session, DesignRequirementRevisionRow revision,
            DesignWorkPackageRow workPackage, DesignerAcceptanceCandidateOrchestrator.Poll polled) {
        DesignerSessionRow current = host.getSession().apply(session.id());
        LoopSpecCompilationRow latest = host.getCompilation().apply(compilation.id());
        if (polled.code() != null && !same(latest.externalSessionState(), polled.state())) {
            host.updateCompilation().apply(latest, LoopSpecCompilationState.RUNNING,
                    polled.remote().id(), polled.state(), latest.repairCount(),
                    polled.code(), safeMessage(polled.detail()), session.projectId());
        }
        if (!same(current.externalSessionState(), polled.state())) {
            host.updateDesignerProjection().apply(current, DesignerSessionState.RUNNING,
                    DesignWorkflowPhase.COMPILING, polled.remote().id(), polled.state(),
                    current.designRevision(), current.redesignCount(), revision.revision(),
                    workPackage.packageId());
        }
    }
    private static boolean same(String left, String right) {
        return left == null ? right == null : left.equalsIgnoreCase(right);
    }
    private static String safeMessage(String message) {
        if (message == null || message.isBlank()) return "OpenCode read-only workflow failed";
        String normalized = message.replaceAll("[\\r\\n]+", " ").trim();
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500);
    }
    record Port(
            Function<String, DesignRequirementRevisionRow> currentRequirement,
            BiFunction<DesignerSessionRow, String, DesignWorkPackageRow> requireCurrentPackage,
            CompleteAccepted completeAccepted, WaitForInput waitForInput, DispatchLegacy dispatchLegacy,
            ConsumeModelCall consumeModelCall, UpdateRequirementUsage updateRequirementUsage, Publish publish,
            Function<String, DesignRequirementRevisionRow> getRequirement, Function<String, DesignerSessionRow> getSession,
            Function<String, LoopSpecCompilationRow> getCompilation, UpdateCompilation updateCompilation,
            UpdateDesignerProjection updateDesignerProjection, FailPackageCompilation failPackageCompilation,
            FailStoppedInitial failStoppedInitial, MarkCandidateRunning markCandidateRunning) {
        Port(Function<String, DesignRequirementRevisionRow> currentRequirement,
                BiFunction<DesignerSessionRow, String, DesignWorkPackageRow> requireCurrentPackage,
                CompleteAccepted completeAccepted, WaitForInput waitForInput, DispatchLegacy dispatchLegacy,
                ConsumeModelCall consumeModelCall, UpdateRequirementUsage updateRequirementUsage, Publish publish,
                Function<String, DesignRequirementRevisionRow> getRequirement, Function<String, DesignerSessionRow> getSession,
                Function<String, LoopSpecCompilationRow> getCompilation, UpdateCompilation updateCompilation,
                UpdateDesignerProjection updateDesignerProjection, FailPackageCompilation failPackageCompilation) {
            this(currentRequirement, requireCurrentPackage, completeAccepted, waitForInput, dispatchLegacy,
                    consumeModelCall, updateRequirementUsage, publish, getRequirement, getSession, getCompilation,
                    updateCompilation, updateDesignerProjection, failPackageCompilation, null, null);
        }
    }
    @FunctionalInterface interface CompleteAccepted {
        void apply(LoopSpecCompilationRow compilation, DesignerSessionRow session, DesignWorkPackageRow workPackage, OpenCodeClient.OpenCodeSession remote, MachineCandidateSubmission.RunSnapshot run, String proof);
    }
    @FunctionalInterface interface WaitForInput {
        void apply(LoopSpecCompilationRow compilation, DesignerSessionRow session, DesignWorkPackageRow workPackage, String code,
                   String detail, List<MachineCandidateSubmission.Problem> problems,
                   MachineCandidateSubmission.RunSnapshot run, String proof);
    }
    @FunctionalInterface interface DispatchLegacy {
        void apply(LoopSpecCompilationRow compilation, DesignerSessionRow session, DesignRequirementRevisionRow revision,
                   DesignWorkPackageRow workPackage, DesignAcceptancePlanningRow planning, DesignerAcceptanceWorkflow.RoutingResult routing,
                   MachineCandidateSubmission.SubmissionResult rejected, String unopenedProof);
    }
    @FunctionalInterface interface ConsumeModelCall {
        boolean apply(DesignerSessionRow session, DesignRequirementRevisionRow revision, String code);
    }
    @FunctionalInterface interface UpdateRequirementUsage {
        DesignRequirementRevisionRow apply(DesignRequirementRevisionRow revision, int modelCallsUsed);
    }
    @FunctionalInterface interface Publish {
        void apply(DesignerSessionRow session, String type, DesignerActor actor, boolean model, String content, String detail);
    }
    @FunctionalInterface interface UpdateCompilation {
        LoopSpecCompilationRow apply(LoopSpecCompilationRow row, LoopSpecCompilationState state,
                                     String remoteId, String remoteState, int repairCount, String code, String detail, String projectId);
    }
    @FunctionalInterface interface UpdateDesignerProjection {
        DesignerSessionRow apply(DesignerSessionRow row, DesignerSessionState state, DesignWorkflowPhase phase, String remoteId,
                                 String remoteState, int designRevision, int redesignCount,
                                 Integer requirementRevision, String packageId);
    }
    @FunctionalInterface interface FailPackageCompilation {
        void apply(LoopSpecCompilationRow row, DesignerSessionRow session, String code, String detail, boolean stopRemote);
    }
    @FunctionalInterface interface FailStoppedInitial {
        void apply(LoopSpecCompilationRow row, DesignerSessionRow session, DesignWorkPackageRow workPackage, String code, String detail, String proof);
    }
    @FunctionalInterface interface MarkCandidateRunning {
        LoopSpecCompilationRow apply(LoopSpecCompilationRow row, String launchId, MachineCandidateSubmission.RunSnapshot run);
    }
    private record InternalLaunchProgress(boolean handled, String launchId) {
        static InternalLaunchProgress none() { return new InternalLaunchProgress(false, null); }
        static InternalLaunchProgress ready(String id) { return new InternalLaunchProgress(false, id); }
        static InternalLaunchProgress handled(String id) { return new InternalLaunchProgress(true, id); }
    }
}
