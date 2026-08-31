package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.domain.MachineCandidateRunState;
import io.opencode.loopper.persistence.DesignWorkPackageRow;
import io.opencode.loopper.runtime.InternalMcpCredentialProvider;
import io.opencode.loopper.runtime.InternalMcpRuntimeAccess;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DesignerPackageCandidateOrchestratorTest {
    private final MachineCandidateSubmission submissions = mock(MachineCandidateSubmission.class);
    private final CandidateRuntimeBindingService bindings = mock(CandidateRuntimeBindingService.class);
    private final OpenCodeClient openCode = mock(OpenCodeClient.class);
    private final InternalMcpRuntimeAccess runtime = new InternalMcpRuntimeAccess();
    private final LoopperProperties properties = new LoopperProperties();
    private DesignerPackageCandidateOrchestrator orchestrator;
    private InternalMcpCredentialProvider.Credentials credentials;

    @BeforeEach
    void setUp() {
        credentials = new InternalMcpCredentialProvider.Credentials(
                "generation-1", "loopper_internal_test", "secret",
                URI.create("http://127.0.0.1:8080/api/internal-mcp-streamable"));
        runtime.activate(credentials);
        runtime.connected(credentials.generation());
        properties.getInternalCandidate().setPackageDesignV1Enabled(true);
        orchestrator = new DesignerPackageCandidateOrchestrator(
                submissions, Optional.of(bindings), openCode, runtime, properties);
    }

    @Test
    void opensOneGenerationBoundThreeAttemptRunAndNamesTheExactPrivateTool() {
        DesignWorkPackageRow workPackage = workPackage();
        OpenCodeClient.OpenCodeSession remote = remote();
        when(bindings.bind(remote, MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP))
                .thenReturn(new CandidateRuntimeBindingService.Binding(
                        remote.id(), credentials.generation(), "MANAGED"));
        when(submissions.open(any())).thenAnswer(call -> {
            MachineCandidateSubmission.OpenCommand command = call.getArgument(0);
            return new MachineCandidateSubmission.RunSnapshot(
                    command.runId(), command.designerSessionId(), command.owner(), command.candidateKind(),
                    command.workflowStep(), command.sourceRevision(), command.ownerVersion(),
                    command.submissionChannel(), command.contractVersion(), command.runtimeGenerationId(),
                    command.externalSessionId(), MachineCandidateRunState.OPEN, command.maxAttempts(), 0, null, 0);
        });

        DesignerPackageCandidateOrchestrator.Start start = orchestrator.open(workPackage, remote, "base prompt");

        assertThat(start.run().candidateKind()).isEqualTo(MachineCandidateKind.PACKAGE_DESIGN_V1);
        assertThat(start.run().owner().designWorkPackageId()).isEqualTo(workPackage.id());
        assertThat(start.run().sourceRevision()).isEqualTo(workPackage.designRevision() + 1L);
        assertThat(start.run().ownerVersion()).isEqualTo(workPackage.version());
        assertThat(start.run().maxAttempts()).isEqualTo(3);
        assertThat(start.prompt()).contains("base prompt", credentials.exactToolName(), start.run().runId(),
                "expectedSubmissionRevision", "PACKAGE_DESIGN_V1",
                "\"key\":\"REQ-1\"", "\"statement\":\"需求语义\"",
                "\"observableResult\":\"可观察结果\"", "\"requirementRefs\":[\"REQ-1\"]",
                "\"kind\":\"DELIVERABLE\"", "\"includes\":[\"SC-1\",\"DEL-1\"]",
                "\"dependencies\":[]", "candidate-local reference, not a server stable ID",
                "explicitly requests Markdown-only or no private submission",
                "must respect that choice and do not call the private tool")
                .doesNotContain("\"id\":\"STAGE-1\"");
    }

    @Test
    void acceptedCandidateRequiresGenerationValidationAndExplicitRemoteTerminationProof() {
        DesignWorkPackageRow workPackage = workPackage();
        MachineCandidateSubmission.RunSnapshot accepted = run(workPackage, MachineCandidateRunState.ACCEPTED, 1);
        when(submissions.find(orchestrator.runId(workPackage))).thenReturn(Optional.of(accepted));
        when(openCode.sessionStatus(any())).thenReturn(new OpenCodeClient.SessionStatus("RUNNING"));

        DesignerPackageCandidateOrchestrator.Poll result = orchestrator.poll(workPackage, Path.of("/tmp/project"), false);

        assertThat(result.action()).isEqualTo(DesignerPackageCandidateOrchestrator.Action.ACCEPTED);
        verify(bindings).validate(accepted, MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP);
        verify(openCode).abortWithConfirmation(result.remote());
    }

    @Test
    void completedSessionWithoutSubmissionClosesRunAndReturnsNonEmptyMarkdownFallback() {
        DesignWorkPackageRow workPackage = workPackage();
        MachineCandidateSubmission.RunSnapshot open = run(workPackage, MachineCandidateRunState.OPEN, 0);
        when(submissions.find(orchestrator.runId(workPackage))).thenReturn(Optional.of(open));
        when(submissions.close(any())).thenReturn(run(workPackage, MachineCandidateRunState.CLOSED, 0));
        when(openCode.sessionStatus(any())).thenReturn(new OpenCodeClient.SessionStatus("COMPLETED"));
        when(openCode.sessionOutput(any())).thenReturn("# complete design");

        DesignerPackageCandidateOrchestrator.Poll result = orchestrator.poll(workPackage, Path.of("/tmp/project"), false);

        assertThat(result.action()).isEqualTo(DesignerPackageCandidateOrchestrator.Action.MARKDOWN_FALLBACK);
        assertThat(result.markdown()).isEqualTo("# complete design");
        assertThat(result.reasonCode()).isEqualTo("MODEL_COMPLETED_WITHOUT_SUBMISSION");
        verify(submissions).close(new MachineCandidateSubmission.CloseCommand(open.runId(), open.version()));
    }

    @Test
    void exhaustedMechanicalRejectionsFallbackOnlyAfterTheSameSessionCompletes() {
        DesignWorkPackageRow workPackage = workPackage();
        MachineCandidateSubmission.RunSnapshot exhausted = run(
                workPackage, MachineCandidateRunState.FALLBACK_REQUIRED, 3);
        when(submissions.find(orchestrator.runId(workPackage))).thenReturn(Optional.of(exhausted));
        when(openCode.sessionStatus(any())).thenReturn(new OpenCodeClient.SessionStatus("COMPLETED"));
        when(openCode.sessionOutput(any())).thenReturn("# corrected final design");

        DesignerPackageCandidateOrchestrator.Poll result = orchestrator.poll(workPackage, Path.of("/tmp/project"), false);

        assertThat(result.action()).isEqualTo(DesignerPackageCandidateOrchestrator.Action.MARKDOWN_FALLBACK);
        assertThat(result.reasonCode()).isEqualTo("MECHANICAL_REJECTIONS_EXHAUSTED");
    }

    @Test
    void generationConflictAndTimeoutFailClosedWithoutReadingMarkdown() {
        DesignWorkPackageRow workPackage = workPackage();
        MachineCandidateSubmission.RunSnapshot open = run(workPackage, MachineCandidateRunState.OPEN, 0);
        when(submissions.find(orchestrator.runId(workPackage))).thenReturn(Optional.of(open));
        doThrow(new ConflictException("CANDIDATE_RUNTIME_GENERATION_STALE", "stale"))
                .when(bindings).validate(open, MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP);

        DesignerPackageCandidateOrchestrator.Poll stale = orchestrator.poll(
                workPackage, Path.of("/tmp/project"), false);

        assertThat(stale.action()).isEqualTo(DesignerPackageCandidateOrchestrator.Action.FAILED);
        assertThat(stale.reasonCode()).isEqualTo("CANDIDATE_RUNTIME_GENERATION_STALE");
        verify(openCode, org.mockito.Mockito.never()).sessionOutput(any());
    }

    @Test
    void acceptedCandidateWithUnconfirmedStopFailsClosedWithoutReadingAssistantOutput() {
        DesignWorkPackageRow workPackage = workPackage();
        MachineCandidateSubmission.RunSnapshot accepted = run(workPackage, MachineCandidateRunState.ACCEPTED, 1);
        when(submissions.find(orchestrator.runId(workPackage))).thenReturn(Optional.of(accepted));
        when(openCode.sessionStatus(any())).thenReturn(new OpenCodeClient.SessionStatus("RUNNING"));
        doThrow(new io.opencode.loopper.domain.SessionFailure(
                "OPENCODE_ABORT_UNCONFIRMED", "remote writer stop was not confirmed"))
                .when(openCode).abortWithConfirmation(any());

        DesignerPackageCandidateOrchestrator.Poll result = orchestrator.poll(
                workPackage, Path.of("/tmp/project"), false);

        assertThat(result.action()).isEqualTo(DesignerPackageCandidateOrchestrator.Action.FAILED);
        assertThat(result.reasonCode()).isEqualTo("OPENCODE_ABORT_UNCONFIRMED");
        verify(openCode, org.mockito.Mockito.never()).sessionOutput(any());
    }

    @Test
    void timedOutCandidateConfirmsStopAndNeverTreatsPartialOutputAsFallback() {
        DesignWorkPackageRow workPackage = workPackage();
        MachineCandidateSubmission.RunSnapshot open = run(workPackage, MachineCandidateRunState.OPEN, 0);
        when(submissions.find(orchestrator.runId(workPackage))).thenReturn(Optional.of(open));
        when(submissions.close(any())).thenReturn(run(workPackage, MachineCandidateRunState.CLOSED, 0));

        DesignerPackageCandidateOrchestrator.Poll result = orchestrator.poll(
                workPackage, Path.of("/tmp/project"), true);

        assertThat(result.action()).isEqualTo(DesignerPackageCandidateOrchestrator.Action.FAILED);
        assertThat(result.reasonCode()).isEqualTo("OPENCODE_PACKAGE_DESIGN_CANDIDATE_TIMEOUT");
        verify(openCode).abortWithConfirmation(result.remote());
        verify(openCode, org.mockito.Mockito.never()).sessionOutput(any());
    }

    private MachineCandidateSubmission.RunSnapshot run(
            DesignWorkPackageRow workPackage, MachineCandidateRunState state, int attempts) {
        return new MachineCandidateSubmission.RunSnapshot(
                orchestrator.runId(workPackage), workPackage.designerSessionId(),
                MachineCandidateSubmission.CandidateOwner.designWorkPackage(workPackage.id()),
                MachineCandidateKind.PACKAGE_DESIGN_V1, DesignerPackageCandidateOrchestrator.WORKFLOW_STEP,
                workPackage.designRevision() + 1L, workPackage.version(),
                MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP, "PACKAGE_DESIGN_V1",
                credentials.generation(), "remote-1", state, 3, attempts,
                state == MachineCandidateRunState.OPEN ? null : "attempt-1", 0);
    }

    private OpenCodeClient.OpenCodeSession remote() {
        return new OpenCodeClient.OpenCodeSession(
                "remote-1", Path.of("/tmp/project"), credentials.generation(), credentials.serverName());
    }

    private DesignWorkPackageRow workPackage() {
        return new DesignWorkPackageRow(
                "work-package-row", "designer-1", "revision-1", "decomposition-1", "WP-1", 0,
                "Package", "Objective", "[]", "[]", "[]", "[]", "[]", "[]", "DESIGNING",
                "remote-1", "RUNNING", null, 2, 0, 0, null, null, null, null,
                null, 0, null, null, "now", "now", 7);
    }
}
