package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.LoopperMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;

class DesignerTerminationServiceTest {
    @ParameterizedTest
    @ValueSource(strings = {"REVIEWING", "COMPLETED"})
    void handedOffCandidateWithoutProofBlocksNormalAndIdempotentParentCompletion(String designerState) {
        LoopperMapper mapper = mock(LoopperMapper.class);
        DesignerSessionRow designer = new DesignerSessionRow(
                "designer-1", "project-1", designerState, "READ_ONLY", "now", "now", 4,
                null, null, null, "COMPILING", 1, 0, 1, "WP-1");
        when(mapper.findDesignerSessionByTask("task-1")).thenReturn(Optional.of(designer));
        when(mapper.listDesignerRemoteSessionIds("designer-1")).thenReturn(List.of());
        when(mapper.listOpenCandidateSubmissionRunsForDesigner("designer-1")).thenReturn(List.of());
        when(mapper.listCandidatePromptDispatchesForDesigner("designer-1")).thenReturn(List.of());
        when(mapper.listAcceptanceCandidateHandoffsForDesigner("designer-1"))
                .thenReturn(List.of(mock(io.opencode.loopper.persistence.AcceptanceCandidateLegacyHandoffRow.class)));
        DesignerTerminationService service = new DesignerTerminationService(mapper,
                mock(DesignerSessionRuntimeControl.class), mock(LifecycleTransitionService.class),
                mock(PlatformTransactionManager.class), mock(AcceptanceCandidateLegacyHandoffService.class),
                mock(AcceptanceCandidateLegacyHandoffCoordinator.class),
                mock(CandidatePromptDispatchService.class), mock(MachineCandidateSubmission.class),
                mock(AcceptanceCandidateInternalTerminationWorkflow.class), readyGenericTerminations());

        assertThatThrownBy(() -> service.completeTaskDesignerInTransaction("task-1"))
                .isInstanceOfSatisfying(ConflictException.class, failure ->
                        assertThat(failure.code()).isEqualTo("DESIGNER_CANDIDATE_WRITER_STILL_ACTIVE"));
        verify(mapper, never()).updateDesignerSession(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void activeCandidateIoClaimFencesDesignerStopBeforeAnyRemoteAbort() {
        LoopperMapper mapper = mock(LoopperMapper.class);
        DesignerSessionRuntimeControl runtime = mock(DesignerSessionRuntimeControl.class);
        AcceptanceCandidateLegacyHandoffService handoffs = mock(AcceptanceCandidateLegacyHandoffService.class);
        DesignerSessionRow stopping = new DesignerSessionRow(
                "designer-1", "project-1", "STOPPING", "READ_ONLY", "now", "now", 4,
                "legacy-remote", "RUNNING", null, "COMPILING", 1, 0, 1, "WP-1");
        when(mapper.findDesignerSession("designer-1")).thenReturn(Optional.of(stopping));
        when(handoffs.prepareDesignerCancellation(org.mockito.ArgumentMatchers.eq("designer-1"),
                org.mockito.ArgumentMatchers.any(Instant.class))).thenReturn(false);
        AcceptanceCandidateLegacyHandoffCoordinator recovery =
                mock(AcceptanceCandidateLegacyHandoffCoordinator.class);
        when(recovery.reconcileDesignerCancellation("designer-1")).thenReturn(
                AcceptanceCandidateLegacyHandoffCoordinator.CancellationRecovery.pending());
        DesignerTerminationService service = new DesignerTerminationService(mapper, runtime,
                mock(LifecycleTransitionService.class), mock(PlatformTransactionManager.class), handoffs,
                recovery,
                mock(CandidatePromptDispatchService.class),
                mock(MachineCandidateSubmission.class),
                readyInternalTerminations(), readyGenericTerminations());

        DesignerTerminationService.Result result = service.stop("designer-1", false);

        assertThat(result.stopStatus()).isEqualTo("STOPPING");
        assertThat(result.failedSessions()).isEqualTo(1);
        verify(recovery).reconcileDesignerCancellation("designer-1");
        verifyNoInteractions(runtime);
        verify(handoffs, never()).cancelAfterDesignerRemotesStopped(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void cleanupRemoteBlocksDesignerCompletionEvenWhenItsParentHandoffIsTerminal() {
        LoopperMapper mapper = mock(LoopperMapper.class);
        DesignerSessionRow designer = new DesignerSessionRow(
                "designer-1", "project-1", "COMPLETED", "READ_ONLY", "now", "now", 4,
                null, null, null, "COMPILING", 1, 0, 1, "WP-1");
        when(mapper.findDesignerSessionByTask("task-1")).thenReturn(Optional.of(designer));
        when(mapper.listOpenCandidateSubmissionRunsForDesigner("designer-1")).thenReturn(List.of());
        when(mapper.listCandidatePromptDispatchesForDesigner("designer-1")).thenReturn(List.of());
        when(mapper.listAcceptanceCandidateHandoffsForDesigner("designer-1")).thenReturn(List.of());
        when(mapper.existsUnstoppedAcceptanceCandidateHandoffCleanupForDesigner("designer-1"))
                .thenReturn(true);
        DesignerTerminationService service = new DesignerTerminationService(mapper,
                mock(DesignerSessionRuntimeControl.class), mock(LifecycleTransitionService.class),
                mock(PlatformTransactionManager.class), mock(AcceptanceCandidateLegacyHandoffService.class),
                mock(AcceptanceCandidateLegacyHandoffCoordinator.class),
                mock(CandidatePromptDispatchService.class), mock(MachineCandidateSubmission.class),
                mock(AcceptanceCandidateInternalTerminationWorkflow.class), readyGenericTerminations());

        assertThatThrownBy(() -> service.completeTaskDesignerInTransaction("task-1"))
                .isInstanceOfSatisfying(ConflictException.class, failure ->
                        assertThat(failure.code()).isEqualTo("DESIGNER_CANDIDATE_WRITER_STILL_ACTIVE"));
    }

    private AcceptanceCandidateInternalTerminationWorkflow readyInternalTerminations() {
        AcceptanceCandidateInternalTerminationWorkflow workflow =
                mock(AcceptanceCandidateInternalTerminationWorkflow.class);
        when(workflow.requestDesignerCancellation(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(new AcceptanceCandidateInternalTerminationWorkflow.Batch(List.of()));
        return workflow;
    }

    private GenericCandidateDesignerTerminationWorkflow readyGenericTerminations() {
        GenericCandidateDesignerTerminationWorkflow workflow =
                mock(GenericCandidateDesignerTerminationWorkflow.class);
        when(workflow.requestDesignerCancellation(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(new GenericCandidateDesignerTerminationWorkflow.Batch(
                        java.util.Map.of(), 0, 0));
        return workflow;
    }
}
