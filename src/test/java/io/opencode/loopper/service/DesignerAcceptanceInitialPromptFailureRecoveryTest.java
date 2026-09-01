package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opencode.loopper.domain.AcceptanceCandidateInitialPromptFailureReason;
import io.opencode.loopper.domain.MachineCandidateRunState;
import io.opencode.loopper.persistence.AcceptanceCandidateInternalLaunchRow;
import io.opencode.loopper.persistence.AcceptanceCandidateInternalTerminationIntentRow;
import io.opencode.loopper.persistence.DesignAcceptancePlanningRow;
import io.opencode.loopper.persistence.DesignRequirementRevisionRow;
import io.opencode.loopper.persistence.DesignWorkPackageRow;
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.LoopSpecCompilationRow;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class DesignerAcceptanceInitialPromptFailureRecoveryTest {
    private final AcceptanceCandidateInternalTerminationWorkflow terminations =
            mock(AcceptanceCandidateInternalTerminationWorkflow.class);
    private final AcceptanceCandidateInternalParentSettlement parentSettlement =
            mock(AcceptanceCandidateInternalParentSettlement.class);
    private final AcceptanceCandidateInternalLaunchService launches =
            mock(AcceptanceCandidateInternalLaunchService.class);
    private final DesignerAcceptanceCandidateOrchestrator candidates =
            mock(DesignerAcceptanceCandidateOrchestrator.class);
    private final AcceptanceCandidateProofService proofs = mock(AcceptanceCandidateProofService.class);
    private final DesignerAcceptanceInitialPromptFailureRecovery recovery =
            new DesignerAcceptanceInitialPromptFailureRecovery(
                    terminations, parentSettlement, launches, candidates, proofs);

    @Test
    void terminalAcceptedRaceUsesTheAuthoritativeCandidateRouteInsteadOfTheOriginalPromptFailure() {
        Fixture ownerStopped = fixture(AcceptanceCandidateInitialPromptFailureReason.RESULT_UNKNOWN);
        AcceptanceCandidateInternalTerminationIntentRow intent = ownerStopped.result().intent();
        var run = mock(MachineCandidateSubmission.RunSnapshot.class);
        when(run.state()).thenReturn(MachineCandidateRunState.ACCEPTED);
        var settled = new AcceptanceCandidateInternalTerminationSettlementService.SettledRun(
                AcceptanceCandidateInternalTerminationSettlementService.RunOutcome.TERMINAL_RACE, run);
        var result = AcceptanceCandidateInternalTerminationCoordinator.Result.ready(intent, settled);
        when(terminations.advanceInitialFailure("compilation")).thenReturn(Optional.of(result));
        when(candidates.routeTerminalAfterStop(eq("compilation"), any(), any()))
                .thenAnswer(invocation -> DesignerAcceptanceCandidateOrchestrator.Poll.accepted(
                        invocation.getArgument(1), run, "ABORT_ACKNOWLEDGED"));
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return null;
        }).when(parentSettlement).settleInitialFailure(eq("intent"), any(Runnable.class));
        DesignerAcceptanceCandidateWorkflow.CompleteAccepted complete =
                mock(DesignerAcceptanceCandidateWorkflow.CompleteAccepted.class);
        DesignerAcceptanceCandidateWorkflow.FailStoppedInitial fail =
                mock(DesignerAcceptanceCandidateWorkflow.FailStoppedInitial.class);
        DesignerAcceptanceCandidateWorkflow.Port port = new DesignerAcceptanceCandidateWorkflow.Port(
                null, null, complete, null, null, null, null, null, null, null, null,
                null, null, null, fail, null);

        assertThat(recovery.recover(port, ownerStopped.compilation(), ownerStopped.session(),
                ownerStopped.revision(), ownerStopped.workPackage(),
                mock(DesignAcceptancePlanningRow.class), null)).isTrue();

        verify(complete).apply(eq(ownerStopped.compilation()), eq(ownerStopped.session()),
                eq(ownerStopped.workPackage()), any(), eq(run), eq("ABORT_ACKNOWLEDGED"));
        verify(fail, org.mockito.Mockito.never()).apply(any(), any(), any(), any(), any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = CandidatePromptDispatchService.Status.class,
            names = {"BUDGET_EXHAUSTED", "LOOKUP_UNSUPPORTED", "RESULT_UNKNOWN"})
    void everyFatalInitialDispatchStopsTheWriterBeforeApplyingItsExactParentOutcome(
            CandidatePromptDispatchService.Status status) {
        AcceptanceCandidateInitialPromptFailureReason reason = switch (status) {
            case BUDGET_EXHAUSTED -> AcceptanceCandidateInitialPromptFailureReason.BUDGET_EXHAUSTED;
            case LOOKUP_UNSUPPORTED -> AcceptanceCandidateInitialPromptFailureReason.LOOKUP_UNSUPPORTED;
            case RESULT_UNKNOWN -> AcceptanceCandidateInitialPromptFailureReason.RESULT_UNKNOWN;
            default -> throw new IllegalArgumentException();
        };
        Fixture fixture = fixture(reason);
        when(terminations.requestInitialPromptFailure("compilation", reason))
                .thenReturn(Optional.of(fixture.result()));
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return null;
        }).when(parentSettlement).settleInitialFailure(eq("intent"), any(Runnable.class));
        DesignerAcceptanceCandidateWorkflow.WaitForInput wait =
                mock(DesignerAcceptanceCandidateWorkflow.WaitForInput.class);
        DesignerAcceptanceCandidateWorkflow.FailStoppedInitial fail =
                mock(DesignerAcceptanceCandidateWorkflow.FailStoppedInitial.class);
        DesignerAcceptanceCandidateWorkflow.Port port = new DesignerAcceptanceCandidateWorkflow.Port(
                null, null, null, wait, null, null, null, null, null, null, null,
                null, null, null, fail, null);

        assertThat(recovery.request(port, fixture.compilation(), fixture.session(), fixture.revision(),
                fixture.workPackage(), mock(DesignAcceptancePlanningRow.class), null, status)).isTrue();

        verify(parentSettlement).settleInitialFailure(eq("intent"), any(Runnable.class));
        if (status == CandidatePromptDispatchService.Status.RESULT_UNKNOWN) {
            verify(fail).apply(eq(fixture.compilation()), eq(fixture.session()), eq(fixture.workPackage()),
                    eq("OPENCODE_PROMPT_RESULT_UNKNOWN"), any(), eq("ABORT_ACKNOWLEDGED"));
        } else {
            String code = status == CandidatePromptDispatchService.Status.BUDGET_EXHAUSTED
                    ? "WORK_PACKAGE_MODEL_CALL_LIMIT" : "DESIGN_INCOMPLETE";
            verify(wait).apply(eq(fixture.compilation()), eq(fixture.session()), eq(fixture.workPackage()),
                    eq(code), any(), eq(java.util.List.of()), eq(null), eq(null));
        }
    }

    private Fixture fixture(AcceptanceCandidateInitialPromptFailureReason reason) {
        LoopSpecCompilationRow compilation = mock(LoopSpecCompilationRow.class);
        when(compilation.id()).thenReturn("compilation");
        DesignerSessionRow session = mock(DesignerSessionRow.class);
        DesignRequirementRevisionRow revision = mock(DesignRequirementRevisionRow.class);
        DesignWorkPackageRow workPackage = mock(DesignWorkPackageRow.class);
        AcceptanceCandidateInternalTerminationIntentRow intent =
                new AcceptanceCandidateInternalTerminationIntentRow(
                        "intent", "launch", "designer", "compilation", "run",
                        "INITIAL_PROMPT_FAILURE", "FAILED_STOPPED", false, reason.name(), "NONE", "READY",
                        1, "revision", 0, "ready", null, null, null, "now", "now", 1);
        var run = mock(MachineCandidateSubmission.RunSnapshot.class);
        when(run.state()).thenReturn(MachineCandidateRunState.CLOSED);
        when(run.closeReason()).thenReturn(MachineCandidateSubmission.CandidateCloseReason.OWNER_REQUESTED);
        var settled = new AcceptanceCandidateInternalTerminationSettlementService.SettledRun(
                AcceptanceCandidateInternalTerminationSettlementService.RunOutcome.OWNER_STOPPED, run);
        var result = AcceptanceCandidateInternalTerminationCoordinator.Result.ready(intent, settled);
        AcceptanceCandidateInternalLaunchRow launch = mock(AcceptanceCandidateInternalLaunchRow.class);
        when(launch.externalSessionId()).thenReturn("remote");
        when(launch.canonicalDirectory()).thenReturn("/tmp/project");
        when(launch.runtimeGenerationId()).thenReturn("generation");
        when(launch.internalMcpServer()).thenReturn("internal");
        when(launch.terminationProof()).thenReturn("ABORT_ACKNOWLEDGED");
        when(launches.require("launch")).thenReturn(launch);
        when(launches.requireForCompilation("compilation")).thenReturn(launch);
        return new Fixture(compilation, session, revision, workPackage, result);
    }

    private record Fixture(LoopSpecCompilationRow compilation, DesignerSessionRow session,
            DesignRequirementRevisionRow revision, DesignWorkPackageRow workPackage,
            AcceptanceCandidateInternalTerminationCoordinator.Result result) { }
}
