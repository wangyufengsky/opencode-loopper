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
import java.nio.file.Path;

/** Applies v7 acceptance candidate poll results through the existing Designer state machine. */
final class DesignerAcceptanceCandidateWorkflow {
    private final DesignerAcceptanceWorkflow acceptanceWorkflow;
    private final DesignerAcceptanceCandidateOrchestrator acceptanceCandidates;
    private final ProjectService projects;
    private final DesignerModelPromptTransport modelPrompts;

    DesignerAcceptanceCandidateWorkflow(
            DesignerAcceptanceWorkflow acceptanceWorkflow,
            DesignerAcceptanceCandidateOrchestrator acceptanceCandidates,
            ProjectService projects, DesignerModelPromptTransport modelPrompts) {
        this.acceptanceWorkflow = acceptanceWorkflow;
        this.acceptanceCandidates = acceptanceCandidates;
        this.projects = projects;
        this.modelPrompts = modelPrompts;
    }

    boolean poll(DesignerSessionService host, LoopSpecCompilationRow compilation,
                 DesignerSessionRow session, boolean timedOut) {
        DesignAcceptancePlanningRow planning = acceptanceWorkflow.find(compilation.id()).orElse(null);
        if (planning == null || !DesignerAcceptancePlanning.CONTRACT_VERSION_V7.equals(
                planning.contractVersion())) return false;
        DesignerAcceptanceWorkflow.RoutingResult routing = acceptanceWorkflow.frozenRoute(compilation.id());
        ProjectRow project = projects.get(session.projectId());
        DesignerAcceptanceCandidateOrchestrator.Poll polled = acceptanceCandidates.poll(
                compilation, planning, routing, Path.of(project.rootPath()), timedOut);
        if (polled.action() == DesignerAcceptanceCandidateOrchestrator.Action.NONE) return false;
        DesignRequirementRevisionRow revision = host.currentRequirement(session.id());
        DesignWorkPackageRow workPackage = host.requireCurrentPackage(session, compilation.workPackageId());
        switch (polled.action()) {
            case RUNNING -> updateRunning(host, compilation, session, revision, workPackage, polled);
            case ACCEPTED -> host.completeAcceptedAcceptanceCandidate(
                    compilation, session, workPackage, polled.remote(), polled.state());
            case WAITING_INPUT -> host.waitAcceptanceCandidate(
                    compilation, session, workPackage, "ACCEPTANCE_CANDIDATE_WAITING_INPUT",
                    polled.problemSummary(), polled.submission().problems(), polled.state());
            case START_LEGACY -> {
                LoopSpecCompilationRow stopped = host.persistAcceptanceCandidateProof(
                        host.getCompilation(compilation.id()), session, polled.remote(), polled.state());
                host.dispatchLegacyAcceptanceCandidate(stopped, session, revision, workPackage,
                        planning, routing, null);
            }
            case REJECTED -> {
                if (!host.consumeModelCall(session, revision, "WORK_PACKAGE_MODEL_CALL_LIMIT")) return true;
                modelPrompts.submit(polled.remote(), polled.prompt(), ModelResponseMode.TEXT_MARKER.name(),
                        null, session.id(), workPackage.packageId());
                host.publish(session, "STATUS", DesignerActor.COMPILER, true, "",
                        workPackage.packageId() + " 正在同一兼容 Session 中机械修正闭集选择");
            }
            case FAILED -> {
                LoopSpecCompilationRow failed = host.getCompilation(compilation.id());
                if (CandidateSessionTerminationProof.persisted(polled.state())) {
                    failed = host.persistAcceptanceCandidateProof(
                            failed, session, polled.remote(), polled.state());
                }
                host.failPackageCompilation(failed, session, polled.code(), polled.detail(), false);
            }
            case NONE -> { }
        }
        return true;
    }

    private void updateRunning(
            DesignerSessionService host, LoopSpecCompilationRow compilation,
            DesignerSessionRow session, DesignRequirementRevisionRow revision,
            DesignWorkPackageRow workPackage, DesignerAcceptanceCandidateOrchestrator.Poll polled) {
        DesignerSessionRow current = host.get(session.id());
        LoopSpecCompilationRow latest = host.getCompilation(compilation.id());
        if (polled.code() != null && !same(latest.externalSessionState(), polled.state())) {
            host.updateCompilation(latest, LoopSpecCompilationState.RUNNING,
                    polled.remote().id(), polled.state(), latest.repairCount(),
                    polled.code(), safeMessage(polled.detail()), session.projectId());
        }
        if (!same(current.externalSessionState(), polled.state())) {
            host.updateDesignerProjection(current, DesignerSessionState.RUNNING,
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
}
