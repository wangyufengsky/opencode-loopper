package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opencode.loopper.domain.MachineCandidateRunState;
import io.opencode.loopper.persistence.AnalysisReportRow;
import io.opencode.loopper.persistence.GenericCandidateInternalLaunchRow;
import io.opencode.loopper.persistence.GenericCandidateInternalTerminationIntentRow;
import io.opencode.loopper.persistence.LoopperMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GenericCandidateDesignerTerminationWorkflowTest {

    @Test
    void promotesAnExistingFailureIntentAndDoesNotCreateASecondIntent() {
        LoopperMapper mapper = mock(LoopperMapper.class);
        GenericCandidateInternalTerminationPreparer preparer =
                mock(GenericCandidateInternalTerminationPreparer.class);
        GenericCandidateInternalTerminationCoordinator coordinator =
                mock(GenericCandidateInternalTerminationCoordinator.class);
        GenericCandidateInternalTerminationIntentStore intents =
                mock(GenericCandidateInternalTerminationIntentStore.class);
        GenericCandidateInternalLaunchCleanupLedger cleanup =
                mock(GenericCandidateInternalLaunchCleanupLedger.class);
        MachineCandidateSubmission submissions = mock(MachineCandidateSubmission.class);
        ReviewerReportCandidateSettlementService settlement =
                mock(ReviewerReportCandidateSettlementService.class);
        GenericCandidateDesignerTerminationWorkflow workflow =
                new GenericCandidateDesignerTerminationWorkflow(mapper, preparer, coordinator,
                        intents, cleanup, submissions, settlement);
        GenericCandidateInternalLaunchRow settled = launch("SETTLED", null);
        GenericCandidateInternalLaunchRow terminal = launch("FAILED_STOPPED", "ABORT_ACKNOWLEDGED");
        when(mapper.listGenericCandidateInternalLaunchesForDesigner("designer-1"))
                .thenReturn(List.of(settled));
        when(mapper.findGenericCandidateInternalLaunch("launch-1"))
                .thenReturn(Optional.of(settled), Optional.of(terminal), Optional.of(terminal));
        GenericCandidateInternalTerminationIntentRow existing = intent("REQUESTED", false);
        GenericCandidateInternalTerminationIntentRow promoted = intent("REQUESTED", true);
        GenericCandidateInternalTerminationIntentRow ready = intent("READY", true);
        when(intents.findForLaunch("launch-1")).thenReturn(Optional.of(existing));
        when(intents.requestOwnerCancel(existing, true)).thenReturn(promoted);
        when(intents.require("intent-1")).thenReturn(ready);
        when(coordinator.advance("intent-1")).thenReturn(
                new GenericCandidateInternalTerminationCoordinator.Result(
                        GenericCandidateInternalTerminationCoordinator.Status.READY, ready, null));
        MachineCandidateSubmission.RunSnapshot run = mock(MachineCandidateSubmission.RunSnapshot.class);
        when(run.runId()).thenReturn("run-1");
        when(run.version()).thenReturn(4L);
        when(run.state()).thenReturn(MachineCandidateRunState.OPEN);
        when(submissions.find("run-1")).thenReturn(Optional.of(run));
        AnalysisReportRow report = mock(AnalysisReportRow.class);
        when(mapper.findAnalysisReport("designer-1", "report-1")).thenReturn(Optional.of(report));
        when(settlement.settle(report, terminal, ready,
                "DESIGNER_CANCELLED", "Designer session was cancelled")).thenReturn(true);
        when(cleanup.list("launch-1")).thenReturn(List.of());

        GenericCandidateDesignerTerminationWorkflow.Batch result =
                workflow.requestDesignerCancellation("designer-1", true);

        assertThat(result.ready()).isTrue();
        assertThat(result.proofs()).containsEntry("remote-1", "ABORT_ACKNOWLEDGED");
        verify(intents).requestOwnerCancel(existing, true);
        verify(preparer, never()).prepare(any());
        verify(submissions).close(new MachineCandidateSubmission.CloseCommand(
                "run-1", 4, MachineCandidateSubmission.CandidateCloseReason.OWNER_REQUESTED));
    }

    private GenericCandidateInternalLaunchRow launch(String state, String proof) {
        GenericCandidateInternalLaunchRow row = mock(GenericCandidateInternalLaunchRow.class);
        when(row.id()).thenReturn("launch-1");
        when(row.candidateRunId()).thenReturn("run-1");
        when(row.designerSessionId()).thenReturn("designer-1");
        when(row.analysisReportId()).thenReturn("report-1");
        when(row.externalSessionId()).thenReturn("remote-1");
        when(row.state()).thenReturn(state);
        when(row.terminationProof()).thenReturn(proof);
        return row;
    }

    private GenericCandidateInternalTerminationIntentRow intent(String state, boolean cancel) {
        GenericCandidateInternalTerminationIntentRow row =
                mock(GenericCandidateInternalTerminationIntentRow.class);
        when(row.id()).thenReturn("intent-1");
        when(row.launchId()).thenReturn("launch-1");
        when(row.state()).thenReturn(state);
        when(row.ownerCancelRequested()).thenReturn(cancel);
        return row;
    }
}
