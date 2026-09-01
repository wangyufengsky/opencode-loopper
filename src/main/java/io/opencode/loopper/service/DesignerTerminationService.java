package io.opencode.loopper.service;

import io.opencode.loopper.domain.DesignWorkPackageState;
import io.opencode.loopper.domain.DesignerAutoModeState;
import io.opencode.loopper.domain.DesignerSessionState;
import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.domain.LifecycleMachineType;
import io.opencode.loopper.domain.LifecycleScopeType;
import io.opencode.loopper.domain.LoopSpecCompilationState;
import io.opencode.loopper.domain.TaskDecompositionState;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.AnalysisReportRow;
import io.opencode.loopper.persistence.DesignWorkPackageRow;
import io.opencode.loopper.persistence.DesignerAutoModeRow;
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.DesignerTaskProfileRow;
import io.opencode.loopper.persistence.LoopSpecCompilationRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.TaskDecompositionRow;
import io.opencode.loopper.persistence.TaskProfileRouterRunRow;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Reusable remote-stop and optimistic local-finalization protocol for Designer-owned runs. */
@Service
public final class DesignerTerminationService {
    private final LoopperMapper mapper;
    private final DesignerSessionRuntimeControl runtimeControl;
    private final LifecycleTransitionService lifecycle;
    private final TransactionTemplate transactions;
    private final AcceptanceCandidateLegacyHandoffService acceptanceHandoffs;
    private final AcceptanceCandidateLegacyHandoffCoordinator acceptanceHandoffRecovery;
    private final CandidatePromptDispatchService candidatePromptDispatches;
    private final MachineCandidateSubmission candidateSubmissions;
    private final AcceptanceCandidateInternalTerminationWorkflow internalTerminations;

    public DesignerTerminationService(LoopperMapper mapper, DesignerSessionRuntimeControl runtimeControl,
                                      LifecycleTransitionService lifecycle,
                                      PlatformTransactionManager transactionManager,
                                      AcceptanceCandidateLegacyHandoffService acceptanceHandoffs,
                                      AcceptanceCandidateLegacyHandoffCoordinator acceptanceHandoffRecovery,
                                      CandidatePromptDispatchService candidatePromptDispatches,
                                      MachineCandidateSubmission candidateSubmissions,
                                      AcceptanceCandidateInternalTerminationWorkflow internalTerminations) {
        this.mapper = mapper;
        this.runtimeControl = runtimeControl;
        this.lifecycle = lifecycle;
        this.transactions = new TransactionTemplate(transactionManager);
        this.acceptanceHandoffs = acceptanceHandoffs;
        this.acceptanceHandoffRecovery = acceptanceHandoffRecovery;
        this.candidatePromptDispatches = candidatePromptDispatches;
        this.candidateSubmissions = candidateSubmissions;
        this.internalTerminations = internalTerminations;
    }

    public Result stop(String sessionId, boolean archiveWhenComplete) {
        Result remote = stopRemote(sessionId, archiveWhenComplete);
        if (remote.failedSessions() > 0 || DesignerSessionState.CANCELLED.name().equals(remote.stopStatus())) {
            return remote;
        }
        int stopped = remote.stoppedSessions();
        String stoppingSessionId = sessionId;
        try {
            transactions.executeWithoutResult(ignored -> finalizeLocal(stoppingSessionId, archiveWhenComplete));
        } catch (ConflictException conflict) {
            return new Result(DesignerSessionState.STOPPING.name(), false, stopped, 0, 1);
        }
        DesignerSessionRow completed = session(stoppingSessionId);
        return new Result(completed.state(), mapper.isDesignerSessionArchived(stoppingSessionId), stopped, 0, 0);
    }

    public Result stopTaskDesignerRemotely(String taskId) {
        return mapper.findDesignerSessionByTask(taskId)
                .map(session -> stopRemote(session.id(), false))
                .orElseGet(() -> new Result(DesignerSessionState.CANCELLED.name(), false, 0, 0, 0));
    }

    void recoverInternalCancellations() {
        internalTerminations.activeParentActionDesigners(
                io.opencode.loopper.domain.AcceptanceCandidateInternalParentAction.DESIGNER_CANCEL)
                .forEach(sessionId -> {
                    try { stop(sessionId, internalTerminations.archiveRequested(sessionId)); }
                    catch (RuntimeException ignoredConcurrentRecovery) { }
                });
    }

    public void finalizeTaskDesignerInTransaction(String taskId) {
        mapper.findDesignerSessionByTask(taskId).ifPresent(session -> {
            if (DesignerSessionState.CANCELLED.name().equals(session.state())) return;
            finalizeLocal(session.id(), false);
        });
    }

    public void completeTaskDesignerInTransaction(String taskId) {
        mapper.findDesignerSessionByTask(taskId).ifPresent(session -> {
            requireNoActiveCandidateWriters(session.id());
            if (Set.of(DesignerSessionState.COMPLETED.name(), DesignerSessionState.CANCELLED.name())
                    .contains(session.state())) return;
            if (!DesignerSessionState.REVIEWING.name().equals(session.state()) || !remoteIds(session.id()).isEmpty()) {
                throw new ConflictException("TASK_DESIGNER_STILL_ACTIVE", "任务设计子流程尚未收束，不能完成父任务");
            }
            DesignerSessionRow completed = copySession(session, DesignerSessionState.COMPLETED, "COMPLETED", now());
            lifecycle.transition(sessionSubject(session), session.state(), completed.state(), LifecycleEvent.COMPLETE,
                    "TASK_COMPLETED", Map.of(), () -> mapper.updateDesignerSession(completed), sessionConflict());
        });
    }

    private void requireNoActiveCandidateWriters(String designerSessionId) {
        boolean openRun = !mapper.listOpenCandidateSubmissionRunsForDesigner(designerSessionId).isEmpty();
        boolean activePrompt = mapper.listCandidatePromptDispatchesForDesigner(designerSessionId).stream()
                .anyMatch(row -> !Set.of("STOPPED", "CANCELLED").contains(row.state()));
        boolean activeHandoff = !mapper.listAcceptanceCandidateHandoffsForDesigner(designerSessionId).isEmpty();
        boolean activeCleanup = mapper
                .existsUnstoppedAcceptanceCandidateHandoffCleanupForDesigner(designerSessionId);
        if (openRun || activePrompt || activeHandoff || activeCleanup) {
            throw new ConflictException("DESIGNER_CANDIDATE_WRITER_STILL_ACTIVE",
                    "Designer candidate run, prompt dispatch, or handoff is still active");
        }
    }

    private Result stopRemote(String sessionId, boolean archiveWhenComplete) {
        DesignerSessionRow session = session(sessionId);
        if (DesignerSessionState.CANCELLED.name().equals(session.state())) {
            return new Result(session.state(), mapper.isDesignerSessionArchived(sessionId), 0, 0, 0);
        }
        if (!DesignerSessionState.STOPPING.name().equals(session.state())) session = beginStopping(session);
        AcceptanceCandidateInternalTerminationWorkflow.Batch internal =
                internalTerminations.requestDesignerCancellation(session, archiveWhenComplete);
        if (!internal.ready()) {
            return new Result(DesignerSessionState.STOPPING.name(), false, internal.stoppedSessions(),
                    Math.max(1, internal.failedSessions()), 0);
        }
        Instant now = Instant.now();
        acceptanceHandoffs.prepareDesignerCancellation(session.id(), now);
        AcceptanceCandidateLegacyHandoffCoordinator.CancellationRecovery recovered =
                acceptanceHandoffRecovery.reconcileDesignerCancellation(session.id());
        boolean handoffsReady = recovered.ready()
                && acceptanceHandoffs.prepareDesignerCancellation(session.id(), Instant.now());
        boolean promptsReady = candidatePromptDispatches.prepareDesignerCancellation(session.id(), now);
        if (!handoffsReady || !promptsReady) {
            return new Result(DesignerSessionState.STOPPING.name(), false,
                    recovered.stoppedSessions(), Math.max(1, recovered.failedSessions()), 0);
        }
        int stopped = recovered.stoppedSessions() + internal.stoppedSessions();
        int failed = 0;
        Map<String, String> proofs = new LinkedHashMap<>(recovered.proofs());
        for (String remoteId : remoteIds(session.id())) {
            if (internalTerminations.ownsExternalSession(remoteId)) continue;
            try {
                OpenCodeClient.AbortConfirmation confirmation = runtimeControl.abort(remoteId, session.projectId());
                proofs.put(remoteId, CandidateSessionTerminationProof.from(confirmation).name());
                stopped++;
            } catch (RuntimeException failure) {
                failed++;
            }
        }
        if (failed > 0) return new Result(DesignerSessionState.STOPPING.name(), false, stopped, failed, 0);
        try {
            proofs = new LinkedHashMap<>(candidatePromptDispatches
                    .completeDesignerCancellation(session.id(), proofs));
            proofs = new LinkedHashMap<>(acceptanceHandoffs
                    .cancelAfterDesignerRemotesStopped(session.id(), proofs));
            closeCandidateRuns(session.id(), proofs);
        } catch (RuntimeException checkpointFailure) {
            return new Result(DesignerSessionState.STOPPING.name(), false, stopped, 1, 0);
        }
        return new Result(DesignerSessionState.STOPPING.name(), false, stopped, 0, 1);
    }

    private void closeCandidateRuns(String designerSessionId, Map<String, String> proofs) {
        mapper.listOpenCandidateSubmissionRunsForDesigner(designerSessionId).forEach(run -> {
            if (!CandidateSessionTerminationProof.persisted(proofs.get(run.externalSessionId()))) {
                throw new ConflictException("DESIGNER_REMOTE_STOP_PROOF_MISSING",
                        "Designer candidate run stop proof is missing for " + run.externalSessionId());
            }
            candidateSubmissions.close(new MachineCandidateSubmission.CloseCommand(
                    run.id(), run.version(), MachineCandidateSubmission.CandidateCloseReason.OWNER_REQUESTED));
        });
    }

    private void finalizeLocal(String sessionId, boolean archiveWhenComplete) {
        DesignerSessionRow session = session(sessionId);
        if (!DesignerSessionState.STOPPING.name().equals(session.state())) {
            throw new ConflictException("DESIGNER_SESSION_VERSION_CONFLICT", "设计会话已发生并发变化，请重试");
        }
        requireNoActiveCandidateWriters(sessionId);
        boolean persistedArchiveRequest = internalTerminations.archiveRequested(sessionId);
        String stoppedAt = now();
        disableAutoMode(session);
        stopRouters(session, stoppedAt);
        supersedeProfiles(session, stoppedAt);
        stopDecompositions(session, stoppedAt);
        stopWorkPackages(session, stoppedAt);
        stopCompilations(session, stoppedAt);
        stopReports(sessionId, stoppedAt);
        DesignerSessionRow cancelled = copySession(session, DesignerSessionState.CANCELLED, "ABORTED", stoppedAt);
        lifecycle.transition(sessionSubject(session), session.state(), cancelled.state(), LifecycleEvent.FINISH,
                "REMOTE_SESSIONS_STOPPED", Map.of(), () -> mapper.updateDesignerSession(cancelled), sessionConflict());
        internalTerminations.completeReadyParentActionInCurrentTransaction(
                sessionId, io.opencode.loopper.domain.AcceptanceCandidateInternalParentAction.DESIGNER_CANCEL);
        if (archiveWhenComplete || persistedArchiveRequest) mapper.archiveDesignerSession(sessionId, stoppedAt);
    }

    private void stopRouters(DesignerSessionRow session, String at) {
        mapper.listActiveTaskProfileRouterRuns().stream()
                .filter(row -> session.id().equals(row.designerSessionId())).forEach(row -> {
                    TaskProfileRouterRunRow stopped = new TaskProfileRouterRunRow(row.id(), row.designerSessionId(),
                            "SUPERSEDED", row.requirementSnapshot(), row.repositoryEvidenceJson(), row.externalSessionId(),
                            "ABORTED", row.responseMode(), row.semanticLabelsJson(), "DESIGNER_CANCELLED",
                            "Designer session was cancelled", row.createdAt(), at, row.version(),
                            row.projectStackProfileId(), row.componentKeysJson(), row.stackFingerprint());
                    lifecycle.mutateWithoutTransition(() -> mapper.updateTaskProfileRouterRun(stopped),
                            () -> new ConflictException("TASK_PROFILE_ROUTER_VERSION_CONFLICT", "任务设置识别记录被并发更新"));
                });
    }

    private void supersedeProfiles(DesignerSessionRow session, String at) {
        mapper.listDesignerTaskProfiles(session.id()).stream()
                .filter(row -> Set.of("PROVISIONAL", "FROZEN").contains(row.state())).forEach(row -> {
                    DesignerTaskProfileRow stopped = new DesignerTaskProfileRow(row.id(), row.designerSessionId(),
                            row.requirementRevisionId(), "SUPERSEDED", row.intent(), row.workflowTemplate(),
                            row.mutationMode(), row.artifactKindsJson(), row.technologiesJson(), row.testPolicy(),
                            row.executionStrategy(), row.rolePackId(), row.rolePackVersion(), row.confidence(),
                            row.evidenceJson(), row.resolutionSource(), row.decisionRequired(), row.createdAt(), at,
                            row.version(), row.projectStackProfileId(), row.componentKeysJson(), row.stackFingerprint());
                    lifecycle.mutateWithoutTransition(() -> mapper.updateDesignerTaskProfile(stopped),
                            () -> new ConflictException("TASK_PROFILE_VERSION_CONFLICT", "任务设置已被并发更新"));
                });
    }

    private void stopDecompositions(DesignerSessionRow session, String at) {
        mapper.activeTaskDecompositions().stream().filter(row -> session.id().equals(row.designerSessionId()))
                .forEach(row -> {
                    TaskDecompositionRow stopped = new TaskDecompositionRow(row.id(), row.designerSessionId(),
                            row.requirementRevisionId(), TaskDecompositionState.SESSION_ERROR.name(), row.resultType(),
                            row.normalizedGoal(), row.globalConstraintsJson(), row.planJson(), row.externalSessionId(),
                            "ABORTED", row.repairCount(), row.transportRetryCount(), row.sourceDraftVersion(),
                            "DESIGNER_CANCELLED", "Designer session was cancelled", row.createdAt(), at, row.version(),
                            row.workflowStep(), row.planningJson(), row.planningRepairCount(), row.planningResponseMode(),
                            row.planningResponseSchemaId(), row.planningFormatFallbackUsed(), row.finalResponseMode(),
                            row.finalResponseSchemaId(), row.finalFormatFallbackUsed(), row.semanticPlanJson(),
                            row.formatRepairCount(), row.semanticRepairCount(), row.serverCompiled());
                    lifecycle.transition(decompositionSubject(row, session), row.state(), stopped.state(),
                            LifecycleEvent.SESSION_FAIL, "DESIGNER_CANCELLED", Map.of(),
                            () -> mapper.updateTaskDecomposition(stopped), childConflict("TASK_DECOMPOSITION_VERSION_CONFLICT"));
                });
    }

    private void stopWorkPackages(DesignerSessionRow session, String at) {
        mapper.listDesignerWorkPackages(session.id()).stream()
                .filter(row -> Set.of("QUESTIONING", "DESIGNING", "COMPILING", "VALIDATING").contains(row.state()))
                .forEach(row -> {
                    DesignWorkPackageRow stopped = new DesignWorkPackageRow(row.id(), row.designerSessionId(),
                            row.requirementRevisionId(), row.decompositionId(), row.packageId(), row.ordinal(), row.title(),
                            row.objective(), row.scopeInJson(), row.scopeOutJson(), row.dependenciesJson(),
                            row.deliverablesJson(), row.acceptanceIntentJson(), row.requirementRefsJson(),
                            DesignWorkPackageState.FAILED.name(), row.designerExternalSessionId(), "ABORTED",
                            row.designMessageId(), row.designRevision(), row.redesignCount(),
                            row.designerTransportRetryCount(), row.compilerSummary(), row.handoffSummary(),
                            "DESIGNER_CANCELLED", "Designer session was cancelled", row.approvedDesignRevision(),
                            row.discussionRoundCount(), row.invalidatedByPackageId(), row.approvedAt(), row.createdAt(), at,
                            row.version(), row.planRevision(), row.correctionOfPackageId(), row.supersededAt());
                    lifecycle.transition(workPackageSubject(row, session), row.state(), stopped.state(), LifecycleEvent.FAIL,
                            "DESIGNER_CANCELLED", Map.of("packageId", row.packageId()),
                            () -> mapper.updateDesignWorkPackage(stopped), childConflict("DESIGN_WORK_PACKAGE_VERSION_CONFLICT"));
                });
    }

    private void stopCompilations(DesignerSessionRow session, String at) {
        mapper.activeLoopSpecCompilations().stream().filter(row -> session.id().equals(row.designerSessionId()))
                .forEach(row -> {
                    LoopSpecCompilationRow stopped = new LoopSpecCompilationRow(row.id(), row.designerSessionId(),
                            row.designRevision(), LoopSpecCompilationState.SESSION_ERROR.name(), row.externalSessionId(),
                            "ABORTED", row.repairCount(), row.sourceDesignMessageId(), row.sourceDraftVersion(),
                            "DESIGNER_CANCELLED", "Designer session was cancelled", row.createdAt(), at, row.version(),
                            row.workPackageId(), row.transportRetryCount(), row.compiledPackageJson(), row.workflowStep(),
                            row.planningJson(), row.planningRepairCount(), row.planningResponseMode(),
                            row.planningResponseSchemaId(), row.planningFormatFallbackUsed(), row.finalResponseMode(),
                            row.finalResponseSchemaId(), row.finalFormatFallbackUsed(), row.semanticPlanJson(),
                            row.formatRepairCount(), row.semanticRepairCount(), row.serverCompiled(),
                            row.compilationSource(), row.fallbackReason());
                    lifecycle.transition(compilationSubject(row, session), row.state(), stopped.state(),
                            LifecycleEvent.SESSION_FAIL, "DESIGNER_CANCELLED", Map.of(),
                            () -> mapper.updateLoopSpecCompilation(stopped), childConflict("LOOPSPEC_COMPILATION_VERSION_CONFLICT"));
                });
    }

    private void stopReports(String sessionId, String at) {
        mapper.listAnalysisReports(sessionId).stream()
                .filter(row -> Set.of("RUNNING", "VALIDATING").contains(row.state())).forEach(row -> {
                    AnalysisReportRow stopped = new AnalysisReportRow(row.id(), row.designerSessionId(),
                            row.taskProfileId(), "FAILED", row.title(), row.markdown(), row.evidenceJson(),
                            row.contentSha256(), row.sourceSnapshotSha256(), "DESIGNER_CANCELLED",
                            "Designer session was cancelled", row.createdAt(), at, row.version(),
                            row.externalSessionId(), "ABORTED", row.sourceRequirement(), row.rolePackId(),
                            row.rolePackVersion(), row.reviewerContractVersion(), row.responseMode(),
                            row.findingsJson(), row.deadlineAt());
                    lifecycle.mutateWithoutTransition(() -> mapper.updateAnalysisReport(stopped),
                            () -> new ConflictException("REPORT_VERSION_CONFLICT", "报告状态已并发变化"));
                });
    }

    private void disableAutoMode(DesignerSessionRow session) {
        DesignerAutoModeRow row = mapper.findDesignerAutoMode(session.id()).orElse(null);
        if (row == null || !Set.of(DesignerAutoModeState.ACTIVE.name(), DesignerAutoModeState.BLOCKED.name())
                .contains(row.state())) return;
        String at = now();
        DesignerAutoModeRow disabled = new DesignerAutoModeRow(row.designerSessionId(),
                DesignerAutoModeState.DISABLED.name(), "MODE_DISABLED", row.errorCode(), row.errorDetail(),
                row.taskId(), row.authorizedAt(), at, at, row.version());
        lifecycle.transition(autoModeSubject(session), row.state(), disabled.state(), LifecycleEvent.DISABLE,
                "DESIGNER_STOPPING", Map.of(), () -> mapper.updateDesignerAutoMode(disabled),
                childConflict("DESIGNER_AUTO_MODE_VERSION_CONFLICT"));
    }

    private DesignerSessionRow beginStopping(DesignerSessionRow row) {
        DesignerSessionRow stopped = copySession(row, DesignerSessionState.STOPPING, "STOPPING", now());
        lifecycle.transition(sessionSubject(row), row.state(), stopped.state(), LifecycleEvent.CANCEL,
                "USER_CLEAR_DESIGNER", Map.of(), () -> mapper.updateDesignerSession(stopped), sessionConflict());
        return session(row.id());
    }

    private Set<String> remoteIds(String sessionId) {
        Set<String> ids = new LinkedHashSet<>();
        mapper.listDesignerRemoteSessionIds(sessionId).stream()
                .filter(value -> value != null && !value.isBlank()).forEach(ids::add);
        return ids;
    }

    private DesignerSessionRow copySession(DesignerSessionRow row, DesignerSessionState state,
                                           String remoteState, String at) {
        return new DesignerSessionRow(row.id(), row.projectId(), state.name(), row.accessMode(), row.createdAt(), at,
                row.version(), row.externalSessionId(), remoteState, row.loopDraftId(), row.workflowPhase(),
                row.designRevision(), row.redesignCount(), row.currentRequirementRevision(), row.activeWorkPackageId(),
                row.discussionScope(), row.discussionRevision(), row.candidateSyncState());
    }

    private DesignerSessionRow session(String id) {
        return mapper.findDesignerSession(id)
                .orElseThrow(() -> new NotFoundException("Designer session not found: " + id));
    }

    private LifecycleTransitionService.Subject sessionSubject(DesignerSessionRow row) {
        return subject(LifecycleMachineType.DESIGNER_SESSION, row.id(), row.projectId());
    }
    private LifecycleTransitionService.Subject autoModeSubject(DesignerSessionRow row) {
        return subject(LifecycleMachineType.DESIGNER_AUTO_MODE, row.id(), row.projectId());
    }
    private LifecycleTransitionService.Subject decompositionSubject(TaskDecompositionRow row, DesignerSessionRow session) {
        return subject(LifecycleMachineType.TASK_DECOMPOSITION, row.id(), session.projectId());
    }
    private LifecycleTransitionService.Subject workPackageSubject(DesignWorkPackageRow row, DesignerSessionRow session) {
        return subject(LifecycleMachineType.DESIGN_WORK_PACKAGE, row.id(), session.projectId());
    }
    private LifecycleTransitionService.Subject compilationSubject(LoopSpecCompilationRow row, DesignerSessionRow session) {
        return subject(LifecycleMachineType.LOOPSPEC_COMPILATION, row.id(), session.projectId());
    }
    private LifecycleTransitionService.Subject subject(LifecycleMachineType type, String id, String projectId) {
        return new LifecycleTransitionService.Subject(type, id, LifecycleScopeType.PROJECT, projectId);
    }
    private java.util.function.Supplier<ConflictException> sessionConflict() {
        return childConflict("DESIGNER_SESSION_VERSION_CONFLICT");
    }
    private java.util.function.Supplier<ConflictException> childConflict(String code) {
        return () -> new ConflictException(code, "Designer 子状态已发生并发变化，请重试停止");
    }
    private static String now() { return Instant.now().toString(); }

    public record Result(String stopStatus, boolean archived, int stoppedSessions,
                         int failedSessions, int pendingFinalizations) {
        public boolean complete() {
            return DesignerSessionState.CANCELLED.name().equals(stopStatus)
                    && failedSessions == 0 && pendingFinalizations == 0;
        }
    }
}
