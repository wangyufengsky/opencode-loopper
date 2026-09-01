package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.MachineCandidateRunState;
import io.opencode.loopper.persistence.TaskPackagePlanRevisionRow;
import io.opencode.loopper.runtime.InternalMcpCredentialProvider;
import io.opencode.loopper.runtime.InternalMcpRuntimeAccess;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RollingPackagePlanCandidateOrchestratorTest {
    private final MachineCandidateSubmission submissions = mock(MachineCandidateSubmission.class);
    private final CandidateRuntimeBindingService bindings = mock(CandidateRuntimeBindingService.class);
    private final OpenCodeClient openCode = mock(OpenCodeClient.class);
    private final InternalMcpRuntimeAccess runtime = new InternalMcpRuntimeAccess();
    private final LoopperProperties properties = new LoopperProperties();
    private RollingPackagePlanCandidateOrchestrator orchestrator;
    private InternalMcpCredentialProvider.Credentials credentials;

    @BeforeEach
    void setUp() {
        credentials = new InternalMcpCredentialProvider.Credentials(
                "generation-1", "loopper_internal_rolling", "secret",
                URI.create("http://127.0.0.1:18080/api/internal-mcp-streamable"));
        runtime.activate(credentials);
        runtime.connected(credentials.generation());
        properties.getInternalCandidate().setRollingPackagePlanV1Enabled(true);
        orchestrator = new RollingPackagePlanCandidateOrchestrator(
                submissions, Optional.of(bindings), openCode, runtime, properties);
    }

    @Test
    void opensOneTaskScopedRunAndNamesOnlyTheExactPrivateSubmissionTool() {
        TaskPackagePlanRevisionRow owner = owner();
        OpenCodeClient.OpenCodeSession remote = remote();
        when(bindings.bind(remote, MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP))
                .thenReturn(new CandidateRuntimeBindingService.Binding(
                        remote.id(), credentials.generation(), "MANAGED"));
        when(submissions.open(any())).thenAnswer(call -> snapshot(call.getArgument(0)));

        RollingPackagePlanCandidateOrchestrator.Start start = orchestrator.open(owner, remote, "base facts");

        assertThat(start.run()).satisfies(run -> {
            assertThat(run.scope()).isEqualTo(MachineCandidateSubmission.CandidateScope.task("task-1"));
            assertThat(run.owner()).isEqualTo(
                    MachineCandidateSubmission.CandidateOwnerRef.taskPackagePlanRevision("plan-1"));
            assertThat(run.sourceRevision()).isEqualTo(4);
            assertThat(run.ownerVersion()).isEqualTo(1);
            assertThat(run.maxAttempts()).isEqualTo(3);
        });
        assertThat(start.prompt()).contains("base facts", credentials.exactToolName(), start.run().runId(),
                "ROLLING_PACKAGE_PLAN_V1", "expectedSubmissionRevision",
                "\"packageKey\":\"WP-2\"", "\"replaces\":[\"WP-2\"]", "\"requirementRefs\":[]")
                .contains("final assistant text is ignored")
                .doesNotContain("MARKER", "Markdown fallback", "command", "allowedPaths");
    }

    @Test
    void normalCompletionWithoutSubmissionFailsClosedAndNeverReadsLegacyOutput() {
        TaskPackagePlanRevisionRow owner = owner();
        MachineCandidateSubmission.RunSnapshot open = run(owner, MachineCandidateRunState.OPEN, 0);
        when(submissions.find(orchestrator.runId(owner))).thenReturn(Optional.of(open));
        when(submissions.close(any())).thenReturn(closed(open));
        when(openCode.sessionStatus(any())).thenReturn(new OpenCodeClient.SessionStatus("COMPLETED"));

        RollingPackagePlanCandidateOrchestrator.Poll result = orchestrator.poll(
                owner, Path.of("/tmp/project"), false);

        assertThat(result.action()).isEqualTo(RollingPackagePlanCandidateOrchestrator.Action.FAILED);
        assertThat(result.reasonCode()).isEqualTo("ROLLING_PACKAGE_ZERO_SUBMISSION");
        assertThat(result.terminationProof()).isEqualTo("REMOTE_COMPLETED");
        verify(submissions).close(new MachineCandidateSubmission.CloseCommand(
                open.runId(), open.version(),
                MachineCandidateSubmission.CandidateCloseReason.NORMAL_COMPLETION_ZERO_SUBMISSION));
        verify(openCode, never()).sessionOutput(any());
    }

    @Test
    void acceptedCandidateRequiresPositiveAbortProofBeforeSettlement() {
        TaskPackagePlanRevisionRow owner = owner();
        MachineCandidateSubmission.RunSnapshot accepted = run(owner, MachineCandidateRunState.ACCEPTED, 1);
        when(submissions.find(orchestrator.runId(owner))).thenReturn(Optional.of(accepted));
        when(openCode.sessionStatus(any())).thenReturn(new OpenCodeClient.SessionStatus("RUNNING"));
        when(openCode.abortWithConfirmation(any())).thenReturn(OpenCodeClient.AbortConfirmation.ACKNOWLEDGED);

        RollingPackagePlanCandidateOrchestrator.Poll result = orchestrator.poll(
                owner, Path.of("/tmp/project"), false);

        assertThat(result.action()).isEqualTo(RollingPackagePlanCandidateOrchestrator.Action.ACCEPTED);
        assertThat(result.terminationProof()).isEqualTo("ABORT_ACKNOWLEDGED");
        verify(bindings).validate(accepted, MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP);
    }

    @Test
    void uncertainStopKeepsTheSameRunDisconnected() {
        TaskPackagePlanRevisionRow owner = owner();
        MachineCandidateSubmission.RunSnapshot accepted = run(owner, MachineCandidateRunState.ACCEPTED, 1);
        when(submissions.find(orchestrator.runId(owner))).thenReturn(Optional.of(accepted));
        when(openCode.sessionStatus(any())).thenReturn(new OpenCodeClient.SessionStatus("RUNNING"));
        doThrow(new ConflictException("OPENCODE_ABORT_UNCONFIRMED", "uncertain"))
                .when(openCode).abortWithConfirmation(any());

        RollingPackagePlanCandidateOrchestrator.Poll result = orchestrator.poll(
                owner, Path.of("/tmp/project"), false);

        assertThat(result.action()).isEqualTo(RollingPackagePlanCandidateOrchestrator.Action.DISCONNECTED);
        assertThat(result.run()).isEqualTo(accepted);
        assertThat(result.reasonCode()).isEqualTo("OPENCODE_ABORT_UNCONFIRMED");
    }

    private TaskPackagePlanRevisionRow owner() {
        return new TaskPackagePlanRevisionRow("plan-1", "task-1", "designer-1", "requirement-1",
                4, "GENERATING", "AI", "[]", "{}", "remote-1", "PROMPTING",
                null, null, "checkpoint-1", 7, "package-run-2", 3,
                "2026-09-02T00:00:00Z", "2026-09-02T00:00:00Z", null, null, 1);
    }

    private OpenCodeClient.OpenCodeSession remote() {
        return new OpenCodeClient.OpenCodeSession(
                "remote-1", Path.of("/tmp/project"), credentials.generation(), credentials.serverName());
    }

    private MachineCandidateSubmission.RunSnapshot snapshot(MachineCandidateSubmission.OpenCommand command) {
        return new MachineCandidateSubmission.RunSnapshot(command.runId(), command.scope(), command.owner(),
                command.candidateKind(), command.workflowStep(), command.sourceRevision(), command.ownerVersion(),
                command.submissionChannel(), command.contractVersion(), command.runtimeGenerationId(),
                command.externalSessionId(), MachineCandidateRunState.OPEN, command.maxAttempts(), 0, null, 0);
    }

    private MachineCandidateSubmission.RunSnapshot run(TaskPackagePlanRevisionRow owner,
                                                        MachineCandidateRunState state, int attempts) {
        return new MachineCandidateSubmission.RunSnapshot(orchestrator.runId(owner),
                MachineCandidateSubmission.CandidateScope.task(owner.taskId()),
                MachineCandidateSubmission.CandidateOwnerRef.taskPackagePlanRevision(owner.id()),
                io.opencode.loopper.domain.MachineCandidateKind.ROLLING_PACKAGE_PLAN_V1,
                "ROLLING_PACKAGE_PLAN_V1", owner.revision(), owner.version(),
                MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP,
                "ROLLING_PACKAGE_PLAN_V1", credentials.generation(), owner.externalSessionId(),
                state, 3, attempts, state.terminal() ? "attempt-1" : null, 0);
    }

    private MachineCandidateSubmission.RunSnapshot closed(MachineCandidateSubmission.RunSnapshot run) {
        return new MachineCandidateSubmission.RunSnapshot(run.runId(), run.scope(), run.owner(), run.candidateKind(),
                run.workflowStep(), run.sourceRevision(), run.ownerVersion(), run.submissionChannel(),
                run.contractVersion(), run.runtimeGenerationId(), run.externalSessionId(),
                MachineCandidateRunState.CLOSED, run.maxAttempts(), run.attemptsUsed(), run.terminalAttemptId(),
                run.version() + 1, MachineCandidateSubmission.CandidateCloseReason.NORMAL_COMPLETION_ZERO_SUBMISSION);
    }
}
