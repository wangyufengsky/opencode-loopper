package io.opencode.loopper.service;

import io.opencode.loopper.domain.DesignWorkflowPhase;
import io.opencode.loopper.domain.DesignerActor;
import io.opencode.loopper.domain.DesignerSessionState;
import io.opencode.loopper.domain.LoopSpecCompilationState;
import io.opencode.loopper.domain.ModelResponseMode;
import io.opencode.loopper.persistence.DesignAcceptancePlanningRow;
import io.opencode.loopper.persistence.DesignRequirementRevisionRow;
import io.opencode.loopper.persistence.DesignWorkPackageRow;
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.LoopSpecCompilationRow;
import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

/** Applies v7 acceptance candidate poll results through the existing Designer state machine. */
final class DesignerAcceptanceCandidateWorkflow {
    private final DesignerAcceptanceWorkflow acceptanceWorkflow;
    private final DesignerAcceptanceCandidateOrchestrator acceptanceCandidates;
    private final AcceptanceCandidateProofService acceptanceCandidateProofs;
    private final ProjectService projects;
    private final DesignerModelPromptTransport modelPrompts;

    DesignerAcceptanceCandidateWorkflow(
            DesignerAcceptanceWorkflow acceptanceWorkflow,
            DesignerAcceptanceCandidateOrchestrator acceptanceCandidates,
            AcceptanceCandidateProofService acceptanceCandidateProofs,
            ProjectService projects, DesignerModelPromptTransport modelPrompts) {
        this.acceptanceWorkflow = acceptanceWorkflow;
        this.acceptanceCandidates = acceptanceCandidates;
        this.acceptanceCandidateProofs = acceptanceCandidateProofs;
        this.projects = projects;
        this.modelPrompts = modelPrompts;
    }

    boolean poll(Port host, LoopSpecCompilationRow compilation,
                 DesignerSessionRow session, boolean timedOut) {
        DesignAcceptancePlanningRow planning = acceptanceWorkflow.find(compilation.id()).orElse(null);
        if (planning == null || !DesignerAcceptancePlanning.CONTRACT_VERSION_V7.equals(
                planning.contractVersion())) return false;
        DesignerAcceptanceWorkflow.RoutingResult routing = acceptanceWorkflow.frozenRoute(compilation.id());
        ProjectRow project = projects.get(session.projectId());
        DesignerAcceptanceCandidateOrchestrator.Poll polled = acceptanceCandidates.poll(
                compilation, planning, routing, Path.of(project.rootPath()), timedOut);
        if (polled.action() == DesignerAcceptanceCandidateOrchestrator.Action.NONE) return false;
        DesignRequirementRevisionRow revision = host.currentRequirement().apply(session.id());
        DesignWorkPackageRow workPackage = host.requireCurrentPackage().apply(session, compilation.workPackageId());
        switch (polled.action()) {
            case RUNNING -> updateRunning(host, compilation, session, revision, workPackage, polled);
            case ACCEPTED -> host.completeAccepted().apply(
                    compilation, session, workPackage, polled.remote(), polled.run(), polled.state());
            case WAITING_INPUT -> host.waitForInput().apply(
                    compilation, session, workPackage, "ACCEPTANCE_CANDIDATE_WAITING_INPUT",
                    polled.problemSummary(), polled.submission().problems(), polled.run(), polled.state());
            case START_LEGACY -> {
                LoopSpecCompilationRow stopped = acceptanceCandidateProofs
                        .persistIfOwned(polled.run(), polled.state()).orElse(null);
                if (stopped == null) return true;
                host.dispatchLegacy().apply(stopped, session, revision, workPackage,
                        planning, routing, null);
            }
            case START_LEGACY_HANDOFF -> host.dispatchLegacy().apply(
                    compilation, session, revision, workPackage, planning, routing, null);
            case REJECTED -> {
                if (!host.consumeModelCall().apply(session, revision, "WORK_PACKAGE_MODEL_CALL_LIMIT")) return true;
                modelPrompts.submit(polled.remote(), polled.prompt(), ModelResponseMode.TEXT_MARKER.name(),
                        null, session.id(), workPackage.packageId());
                host.publish().apply(session, "STATUS", DesignerActor.COMPILER, true, "",
                        workPackage.packageId() + " 正在同一兼容 Session 中机械修正闭集选择");
            }
            case FAILED -> {
                LoopSpecCompilationRow failed = host.getCompilation().apply(compilation.id());
                if (CandidateSessionTerminationProof.persisted(polled.state())) {
                    failed = acceptanceCandidateProofs.persistIfOwned(polled.run(), polled.state()).orElse(null);
                    if (failed == null) return true;
                }
                host.failPackageCompilation().apply(failed, session, polled.code(), polled.detail(), false);
            }
            case NONE -> { }
        }
        return true;
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
            ConsumeModelCall consumeModelCall, Publish publish,
            Function<String, DesignerSessionRow> getSession,
            Function<String, LoopSpecCompilationRow> getCompilation,
            UpdateCompilation updateCompilation, UpdateDesignerProjection updateDesignerProjection,
            FailPackageCompilation failPackageCompilation) { }

    @FunctionalInterface interface CompleteAccepted {
        void apply(LoopSpecCompilationRow compilation, DesignerSessionRow session, DesignWorkPackageRow workPackage,
                   OpenCodeClient.OpenCodeSession remote, MachineCandidateSubmission.RunSnapshot run, String proof);
    }
    @FunctionalInterface interface WaitForInput {
        void apply(LoopSpecCompilationRow compilation, DesignerSessionRow session, DesignWorkPackageRow workPackage,
                   String code, String detail, List<MachineCandidateSubmission.Problem> problems,
                   MachineCandidateSubmission.RunSnapshot run, String proof);
    }
    @FunctionalInterface interface DispatchLegacy {
        void apply(LoopSpecCompilationRow compilation, DesignerSessionRow session,
                   DesignRequirementRevisionRow revision, DesignWorkPackageRow workPackage,
                   DesignAcceptancePlanningRow planning,
                   DesignerAcceptanceWorkflow.RoutingResult routing,
                   MachineCandidateSubmission.SubmissionResult rejected);
    }
    @FunctionalInterface interface ConsumeModelCall {
        boolean apply(DesignerSessionRow session, DesignRequirementRevisionRow revision, String code);
    }
    @FunctionalInterface interface Publish {
        void apply(DesignerSessionRow session, String type, DesignerActor actor, boolean model,
                   String content, String detail);
    }
    @FunctionalInterface interface UpdateCompilation {
        LoopSpecCompilationRow apply(LoopSpecCompilationRow row, LoopSpecCompilationState state,
                                     String remoteId, String remoteState, int repairCount,
                                     String code, String detail, String projectId);
    }
    @FunctionalInterface interface UpdateDesignerProjection {
        DesignerSessionRow apply(DesignerSessionRow row, DesignerSessionState state, DesignWorkflowPhase phase,
                                 String remoteId, String remoteState, int designRevision, int redesignCount,
                                 Integer requirementRevision, String packageId);
    }
    @FunctionalInterface interface FailPackageCompilation {
        void apply(LoopSpecCompilationRow row, DesignerSessionRow session, String code, String detail,
                   boolean stopRemote);
    }
}
