package io.opencode.loopper.service;

import io.opencode.loopper.domain.AcceptanceCandidateInitialPromptFailureReason;
import io.opencode.loopper.persistence.DesignAcceptancePlanningRow;
import io.opencode.loopper.persistence.DesignRequirementRevisionRow;
import io.opencode.loopper.persistence.DesignWorkPackageRow;
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.LoopSpecCompilationRow;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Service;

/** Routes a fatal INITIAL dispatch through the durable stop saga and its authoritative terminal race. */
@Service
final class DesignerAcceptanceInitialPromptFailureRecovery {
    private final AcceptanceCandidateInternalTerminationWorkflow terminations;
    private final AcceptanceCandidateInternalParentSettlement parentSettlement;
    private final AcceptanceCandidateInternalLaunchService launches;
    private final DesignerAcceptanceCandidateOrchestrator candidates;
    private final AcceptanceCandidateProofService proofs;

    DesignerAcceptanceInitialPromptFailureRecovery(
            AcceptanceCandidateInternalTerminationWorkflow terminations,
            AcceptanceCandidateInternalParentSettlement parentSettlement,
            AcceptanceCandidateInternalLaunchService launches,
            DesignerAcceptanceCandidateOrchestrator candidates,
            AcceptanceCandidateProofService proofs) {
        this.terminations = terminations;
        this.parentSettlement = parentSettlement;
        this.launches = launches;
        this.candidates = candidates;
        this.proofs = proofs;
    }

    boolean request(DesignerAcceptanceCandidateWorkflow.Port host, LoopSpecCompilationRow compilation,
            DesignerSessionRow session, DesignRequirementRevisionRow revision, DesignWorkPackageRow workPackage,
            DesignAcceptancePlanningRow planning, DesignerAcceptanceWorkflow.RoutingResult routing,
            CandidatePromptDispatchService.Status status) {
        AcceptanceCandidateInitialPromptFailureReason reason = reason(status);
        if (reason == null) return false;
        var result = terminations.requestInitialPromptFailure(compilation.id(), reason)
                .orElseThrow(() -> stale("INITIAL 提示失败意图未能持久化"));
        return settle(host, compilation, session, revision, workPackage, planning, routing, result);
    }

    boolean recover(DesignerAcceptanceCandidateWorkflow.Port host, LoopSpecCompilationRow compilation,
            DesignerSessionRow session, DesignRequirementRevisionRow revision, DesignWorkPackageRow workPackage,
            DesignAcceptancePlanningRow planning, DesignerAcceptanceWorkflow.RoutingResult routing) {
        var result = terminations.advanceInitialFailure(compilation.id()).orElse(null);
        return result != null && settle(host, compilation, session, revision, workPackage, planning, routing, result);
    }

    private boolean settle(DesignerAcceptanceCandidateWorkflow.Port host, LoopSpecCompilationRow compilation,
            DesignerSessionRow session, DesignRequirementRevisionRow revision, DesignWorkPackageRow workPackage,
            DesignAcceptancePlanningRow planning, DesignerAcceptanceWorkflow.RoutingResult routing,
            AcceptanceCandidateInternalTerminationCoordinator.Result result) {
        if (result.status() != AcceptanceCandidateInternalTerminationCoordinator.Status.READY) return true;
        var intent = result.intent();
        var launch = launches.require(intent.launchId());
        String proof = launch.terminationProof();
        var settled = result.settledRun().orElseThrow(() -> stale("终止后的候选运行缺失"));
        OpenCodeClient.OpenCodeSession remote = new OpenCodeClient.OpenCodeSession(
                launch.externalSessionId(), Path.of(launch.canonicalDirectory()),
                launch.runtimeGenerationId(), launch.internalMcpServer());
        if (settled.outcome() == AcceptanceCandidateInternalTerminationSettlementService.RunOutcome.OWNER_STOPPED) {
            settleOriginalFailure(host, compilation, session, workPackage, intent.id(), intent.reasonCode());
            return true;
        }
        var stopped = DesignerAcceptanceCandidateOrchestrator.StopResult.terminalRace(
                settled.terminalRun(), proof);
        var terminal = candidates.routeTerminalAfterStop(compilation.id(), remote, stopped);
        switch (terminal.action()) {
            case ACCEPTED -> parentSettlement.settleInitialFailure(intent.id(), () ->
                    host.completeAccepted().apply(compilation, session, workPackage, remote,
                            terminal.run(), terminal.state()));
            case WAITING_INPUT -> parentSettlement.settleInitialFailure(intent.id(), () ->
                    host.waitForInput().apply(compilation, session, workPackage,
                            "ACCEPTANCE_CANDIDATE_WAITING_INPUT", terminal.problemSummary(),
                            terminal.submission().problems(), terminal.run(), terminal.state()));
            case START_LEGACY -> {
                LoopSpecCompilationRow stoppedOwner = proofs.persistIfOwned(
                        terminal.run(), terminal.state()).orElseThrow(() -> stale("候选 owner 已变化"));
                host.dispatchLegacy().apply(stoppedOwner, session, revision, workPackage,
                        planning, routing, null, terminal.state());
                parentSettlement.settleInitialFailure(intent.id(), () -> { });
            }
            case FAILED -> parentSettlement.settleInitialFailure(intent.id(), () -> {
                LoopSpecCompilationRow stoppedOwner = proofs.persistIfOwned(
                        terminal.run(), terminal.state()).orElseThrow(() -> stale("候选 owner 已变化"));
                host.failStoppedInitial().apply(stoppedOwner, session, workPackage,
                        terminal.code(), terminal.detail(), terminal.state());
            });
            default -> throw stale("终止竞态没有可收束的候选终态");
        }
        return true;
    }

    private void settleOriginalFailure(DesignerAcceptanceCandidateWorkflow.Port host,
            LoopSpecCompilationRow compilation, DesignerSessionRow session,
            DesignWorkPackageRow workPackage, String intentId, String reasonCode) {
        AcceptanceCandidateInitialPromptFailureReason reason =
                AcceptanceCandidateInitialPromptFailureReason.valueOf(reasonCode);
        String code = switch (reason) {
            case BUDGET_EXHAUSTED -> "WORK_PACKAGE_MODEL_CALL_LIMIT";
            case LOOKUP_UNSUPPORTED -> "DESIGN_INCOMPLETE";
            case RESULT_UNKNOWN -> "OPENCODE_PROMPT_RESULT_UNKNOWN";
        };
        String detail = switch (reason) {
            case BUDGET_EXHAUSTED -> "验收候选 INITIAL 提示没有可用的模型调用预算";
            case LOOKUP_UNSUPPORTED -> "OpenCode 不支持 INITIAL 提示的精确确认恢复";
            case RESULT_UNKNOWN -> "INITIAL 提示已越过 POST 边界但无法确认结果";
        };
        parentSettlement.settleInitialFailure(intentId, () -> {
            if (reason == AcceptanceCandidateInitialPromptFailureReason.RESULT_UNKNOWN) {
                host.failStoppedInitial().apply(compilation, session, workPackage, code, detail,
                        launches.requireForCompilation(compilation.id()).terminationProof());
            } else {
                host.waitForInput().apply(compilation, session, workPackage, code, detail,
                        List.of(), null, null);
            }
        });
    }

    private static AcceptanceCandidateInitialPromptFailureReason reason(
            CandidatePromptDispatchService.Status status) {
        if (status == CandidatePromptDispatchService.Status.BUDGET_EXHAUSTED) {
            return AcceptanceCandidateInitialPromptFailureReason.BUDGET_EXHAUSTED;
        }
        if (status == CandidatePromptDispatchService.Status.LOOKUP_UNSUPPORTED) {
            return AcceptanceCandidateInitialPromptFailureReason.LOOKUP_UNSUPPORTED;
        }
        if (status == CandidatePromptDispatchService.Status.RESULT_UNKNOWN) {
            return AcceptanceCandidateInitialPromptFailureReason.RESULT_UNKNOWN;
        }
        return null;
    }

    private static ConflictException stale(String detail) {
        return new ConflictException("ACCEPTANCE_INITIAL_PROMPT_FAILURE_STALE", detail);
    }
}
