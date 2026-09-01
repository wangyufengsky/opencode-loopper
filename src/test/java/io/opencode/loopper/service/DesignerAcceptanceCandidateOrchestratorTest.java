package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.domain.MachineCandidateOutcome;
import io.opencode.loopper.domain.MachineCandidateRunState;
import io.opencode.loopper.domain.SessionFailure;
import io.opencode.loopper.persistence.LoopSpecCompilationRow;
import io.opencode.loopper.runtime.InternalMcpCredentialProvider;
import io.opencode.loopper.runtime.InternalMcpRuntimeAccess;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class DesignerAcceptanceCandidateOrchestratorTest {
    private final AcceptanceClosedChoiceCandidateCoordinator candidates =
            mock(AcceptanceClosedChoiceCandidateCoordinator.class);
    private final OpenCodeClient openCode = mock(OpenCodeClient.class);
    private final InternalMcpRuntimeAccess runtime = new InternalMcpRuntimeAccess();
    private final DesignerAcceptanceCandidateOrchestrator orchestrator =
            new DesignerAcceptanceCandidateOrchestrator(candidates, new ObjectMapper(), openCode, runtime);
    private InternalMcpCredentialProvider.Credentials credentials;

    @BeforeEach
    void setUp() {
        credentials = new InternalMcpCredentialProvider.Credentials(
                "generation-1", "loopper_internal_test", "secret",
                URI.create("http://127.0.0.1:8080/api/internal-mcp-streamable"));
        runtime.activate(credentials);
        runtime.connected(credentials.generation());
    }

    @Test
    void acceptedCandidateRevalidatesBindingAndRequiresPositiveAbortProofBeforeRelease() {
        LoopSpecCompilationRow compilation = compilation();
        MachineCandidateSubmission.RunSnapshot accepted = run(MachineCandidateRunState.ACCEPTED);
        when(candidates.find(compilation.id())).thenReturn(Optional.of(accepted));
        when(openCode.sessionStatus(any())).thenReturn(new OpenCodeClient.SessionStatus("RUNNING"));
        when(openCode.abortWithConfirmation(any()))
                .thenReturn(OpenCodeClient.AbortConfirmation.ACKNOWLEDGED);

        DesignerAcceptanceCandidateOrchestrator.Poll result = orchestrator.poll(
                compilation, null, null, Path.of("/tmp/project"), false);

        assertThat(result.action()).isEqualTo(DesignerAcceptanceCandidateOrchestrator.Action.ACCEPTED);
        assertThat(result.state()).isEqualTo("ABORT_ACKNOWLEDGED");
        verify(candidates).validate(accepted);
        verify(openCode).sessionStatus(result.remote());
        verify(openCode).abortWithConfirmation(result.remote());
    }

    @Test
    void completedAcceptedCandidateUsesRemoteCompletionWithoutAbort() {
        LoopSpecCompilationRow compilation = compilation();
        MachineCandidateSubmission.RunSnapshot accepted = run(MachineCandidateRunState.ACCEPTED);
        when(candidates.find(compilation.id())).thenReturn(Optional.of(accepted));
        when(openCode.sessionStatus(any())).thenReturn(new OpenCodeClient.SessionStatus("COMPLETED"));

        DesignerAcceptanceCandidateOrchestrator.Poll result = orchestrator.poll(
                compilation, null, null, Path.of("/tmp/project"), false);

        assertThat(result.action()).isEqualTo(DesignerAcceptanceCandidateOrchestrator.Action.ACCEPTED);
        assertThat(result.state()).isEqualTo("REMOTE_COMPLETED");
        verify(candidates).validate(accepted);
        verify(openCode, never()).abortWithConfirmation(any());
    }

    @Test
    void unconfirmedAcceptedAbortRemainsRecoverableOnTheSameRun() {
        LoopSpecCompilationRow compilation = compilation();
        MachineCandidateSubmission.RunSnapshot accepted = run(MachineCandidateRunState.ACCEPTED);
        when(candidates.find(compilation.id())).thenReturn(Optional.of(accepted));
        when(openCode.sessionStatus(any())).thenReturn(new OpenCodeClient.SessionStatus("RUNNING"));
        doThrow(new SessionFailure("OPENCODE_ABORT_UNCONFIRMED", "not acknowledged"))
                .when(openCode).abortWithConfirmation(any());

        DesignerAcceptanceCandidateOrchestrator.Poll result = orchestrator.poll(
                compilation, null, null, Path.of("/tmp/project"), false);

        assertThat(result.action()).isEqualTo(DesignerAcceptanceCandidateOrchestrator.Action.RUNNING);
        assertThat(result.state()).isEqualTo("DISCONNECTED");
        assertThat(result.code()).isEqualTo("OPENCODE_ACCEPTANCE_CANDIDATE_STOP_UNCONFIRMED");
        verify(candidates, never()).close(any(), any());
    }

    @Test
    void acceptedGenerationMismatchRemainsRecoverableWithoutRemoteAccess() {
        LoopSpecCompilationRow compilation = compilation();
        MachineCandidateSubmission.RunSnapshot accepted = run(MachineCandidateRunState.ACCEPTED);
        when(candidates.find(compilation.id())).thenReturn(Optional.of(accepted));
        doThrow(new ConflictException("CANDIDATE_RUNTIME_GENERATION_STALE", "stale generation"))
                .when(candidates).validate(accepted);

        DesignerAcceptanceCandidateOrchestrator.Poll result = orchestrator.poll(
                compilation, null, null, Path.of("/tmp/project"), false);

        assertThat(result.action()).isEqualTo(DesignerAcceptanceCandidateOrchestrator.Action.RUNNING);
        assertThat(result.state()).isEqualTo("DISCONNECTED");
        assertThat(result.code()).isEqualTo("CANDIDATE_RUNTIME_GENERATION_STALE");
        verify(openCode, never()).sessionStatus(any());
        verify(openCode, never()).abortWithConfirmation(any());
    }

    @Test
    void waitingInputRequiresTheSameStopProofBeforeItIsReleased() {
        LoopSpecCompilationRow compilation = compilation();
        MachineCandidateSubmission.RunSnapshot waiting = run(MachineCandidateRunState.WAITING_INPUT);
        MachineCandidateSubmission.SubmissionResult terminal = waitingResult();
        when(candidates.find(compilation.id())).thenReturn(Optional.of(waiting));
        when(candidates.terminal(compilation.id())).thenReturn(Optional.of(terminal));
        when(openCode.sessionStatus(any())).thenReturn(new OpenCodeClient.SessionStatus("RUNNING"));
        when(openCode.abortWithConfirmation(any()))
                .thenReturn(OpenCodeClient.AbortConfirmation.ALREADY_ABSENT);

        DesignerAcceptanceCandidateOrchestrator.Poll result = orchestrator.poll(
                compilation, null, null, Path.of("/tmp/project"), false);

        assertThat(result.action()).isEqualTo(DesignerAcceptanceCandidateOrchestrator.Action.WAITING_INPUT);
        assertThat(result.state()).isEqualTo("ALREADY_ABSENT");
        assertThat(result.submission()).isSameAs(terminal);
        verify(candidates).validate(waiting);
    }

    @Test
    void restartFromAcceptedRunReusesPersistedProofWithoutAnotherPromptOrAbort() {
        LoopSpecCompilationRow compilation = withExternalState("ABORT_ACKNOWLEDGED");
        MachineCandidateSubmission.RunSnapshot accepted = run(MachineCandidateRunState.ACCEPTED);
        when(candidates.find(compilation.id())).thenReturn(Optional.of(accepted));

        DesignerAcceptanceCandidateOrchestrator.Poll result = orchestrator.poll(
                compilation, null, null, Path.of("/tmp/project"), false);

        assertThat(result.action()).isEqualTo(DesignerAcceptanceCandidateOrchestrator.Action.ACCEPTED);
        assertThat(result.state()).isEqualTo("ABORT_ACKNOWLEDGED");
        verify(candidates).validate(accepted);
        verify(openCode, never()).sessionStatus(any());
        verify(openCode, never()).abortWithConfirmation(any());
    }

    @Test
    void internalRunSwitchesToLegacyOnlyAfterNormalRemoteCompletion() {
        LoopSpecCompilationRow compilation = compilation();
        MachineCandidateSubmission.RunSnapshot open = run(MachineCandidateRunState.OPEN);
        when(candidates.find(compilation.id())).thenReturn(Optional.of(open));
        when(openCode.pendingQuestions(any())).thenReturn(java.util.List.of());
        when(openCode.sessionStatus(any())).thenReturn(new OpenCodeClient.SessionStatus("COMPLETED"));

        DesignerAcceptanceCandidateOrchestrator.Poll result = orchestrator.poll(
                compilation, null, null, Path.of("/tmp/project"), false);

        assertThat(result.action()).isEqualTo(DesignerAcceptanceCandidateOrchestrator.Action.START_LEGACY);
        assertThat(result.state()).isEqualTo("REMOTE_COMPLETED");
        verify(candidates).close(compilation.id(),
                MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP);
        verify(openCode, never()).abortWithConfirmation(any());
    }

    @Test
    void legacyOutputIsSubmittedOnlyAfterItsOriginalSessionNormallyCompletes() {
        LoopSpecCompilationRow compilation = compilation();
        MachineCandidateSubmission.RunSnapshot open = legacyRun(MachineCandidateRunState.OPEN);
        MachineCandidateSubmission.RunSnapshot accepted = legacyRun(MachineCandidateRunState.ACCEPTED);
        MachineCandidateSubmission.SubmissionResult acceptedResult = new MachineCandidateSubmission.SubmissionResult(
                "run-1", MachineCandidateOutcome.ACCEPTED, MachineCandidateRunState.ACCEPTED,
                1, 1, false, java.util.List.of(), "a".repeat(64), 1, "{}");
        when(candidates.find(compilation.id())).thenReturn(Optional.of(open), Optional.of(accepted));
        when(openCode.pendingQuestions(any())).thenReturn(java.util.List.of());
        when(openCode.sessionStatus(any())).thenReturn(new OpenCodeClient.SessionStatus("COMPLETED"));
        when(openCode.sessionOutput(any())).thenReturn("valid legacy candidate");
        when(candidates.submitLegacy(compilation.id(), "valid legacy candidate"))
                .thenReturn(acceptedResult);

        DesignerAcceptanceCandidateOrchestrator.Poll result = orchestrator.poll(
                compilation, null, null, Path.of("/tmp/project"), false);

        assertThat(result.action()).isEqualTo(DesignerAcceptanceCandidateOrchestrator.Action.ACCEPTED);
        assertThat(result.state()).isEqualTo("REMOTE_COMPLETED");
        verify(candidates).submitLegacy(compilation.id(), "valid legacy candidate");
    }

    private LoopSpecCompilationRow compilation() {
        return withExternalState("RUNNING");
    }

    private LoopSpecCompilationRow withExternalState(String externalState) {
        return new LoopSpecCompilationRow(
                "compilation-1", "designer-1", 3, "RUNNING", "remote-1", externalState, 0,
                "message-1", 1, null, null, "now", "now", 7);
    }

    private MachineCandidateSubmission.SubmissionResult waitingResult() {
        return new MachineCandidateSubmission.SubmissionResult(
                "run-1", MachineCandidateOutcome.WAITING_INPUT, MachineCandidateRunState.WAITING_INPUT,
                2, 0, false, java.util.List.of(new MachineCandidateSubmission.Problem(
                        "ACCEPTANCE_CANDIDATE_SELECTION_INVALID", "/capabilityPreferences",
                        "selection invalid")), null, 2, "{}");
    }

    private MachineCandidateSubmission.RunSnapshot run(MachineCandidateRunState state) {
        return candidateRun(state, MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP);
    }

    private MachineCandidateSubmission.RunSnapshot legacyRun(MachineCandidateRunState state) {
        return candidateRun(state, MachineCandidateSubmission.SubmissionChannel.IN_PROCESS_LEGACY);
    }

    private MachineCandidateSubmission.RunSnapshot candidateRun(
            MachineCandidateRunState state, MachineCandidateSubmission.SubmissionChannel channel) {
        return new MachineCandidateSubmission.RunSnapshot(
                "run-1", MachineCandidateSubmission.CandidateScope.designerSession("designer-1"),
                MachineCandidateSubmission.CandidateOwnerRef.loopSpecCompilation("compilation-1"),
                MachineCandidateKind.ACCEPTANCE_CLOSED_CHOICE_V7,
                AcceptanceClosedChoiceCandidateCoordinator.WORKFLOW_STEP, 3, 7,
                channel,
                AcceptanceClosedChoiceCandidateCoordinator.CONTRACT_VERSION,
                credentials.generation(), "remote-1", state, 2, 1, "attempt-1", 0);
    }
}
