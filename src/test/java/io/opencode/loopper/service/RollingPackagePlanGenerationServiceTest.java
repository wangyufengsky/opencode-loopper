package io.opencode.loopper.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.domain.MachineCandidateRunState;
import io.opencode.loopper.persistence.DesignRequirementRevisionRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.RollingPackagePlanAcceptedResultRow;
import io.opencode.loopper.persistence.TaskPackagePlanRevisionRow;
import io.opencode.loopper.persistence.TaskPackageRunRow;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.persistence.TaskWorkspaceCheckpointRow;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RollingPackagePlanGenerationServiceTest {
    private final LoopperMapper mapper = mock(LoopperMapper.class);
    private final RollingPackagePlanService plans = mock(RollingPackagePlanService.class);
    private final TaskWorkspaceCheckpointService checkpoints = mock(TaskWorkspaceCheckpointService.class);
    private final OpenCodeClient openCode = mock(OpenCodeClient.class);
    private final LoopperProperties properties = new LoopperProperties();
    private final AiOutputExtractor extractor = mock(AiOutputExtractor.class);
    private final RollingPackagePlanCandidateOrchestrator candidates =
            mock(RollingPackagePlanCandidateOrchestrator.class);
    private RollingPackagePlanGenerationService service;
    private TaskPackagePlanRevisionRow pending;
    private TaskPackagePlanRevisionRow prompting;
    private TaskPackagePlanRevisionRow running;

    @BeforeEach
    void setUp() {
        pending = row(null, "PENDING", 0);
        prompting = row("remote-1", "PROMPTING", 1);
        running = row("remote-1", "RUNNING", 2);
        service = new RollingPackagePlanGenerationService(mapper, plans, new ObjectMapper(), checkpoints,
                openCode, properties, extractor, candidates);
        TaskRow task = mock(TaskRow.class);
        TaskPackageRunRow baseRun = mock(TaskPackageRunRow.class);
        TaskWorkspaceCheckpointRow checkpoint = mock(TaskWorkspaceCheckpointRow.class);
        when(task.version()).thenReturn(7L);
        when(baseRun.version()).thenReturn(3L);
        when(mapper.findTask("task-1")).thenReturn(Optional.of(task));
        when(mapper.findTaskPackageRun("package-run-2")).thenReturn(Optional.of(baseRun));
        when(mapper.findTaskWorkspaceCheckpoint("checkpoint-1")).thenReturn(Optional.of(checkpoint));
        when(checkpoints.designSnapshot(task, checkpoint)).thenReturn(Path.of("/tmp/snapshot"));
        DesignRequirementRevisionRow requirement = mock(DesignRequirementRevisionRow.class);
        when(requirement.requirementText()).thenReturn("冻结需求");
        when(mapper.findDesignRequirementRevision("requirement-1")).thenReturn(Optional.of(requirement));
        when(mapper.listTaskPackageRuns("task-1")).thenReturn(List.of());
        when(mapper.listPackageFactSnapshots("task-1")).thenReturn(List.of());
    }

    @Test
    void candidateEligibleDispatchNeverCreatesTheLegacyDecomposerSession() {
        when(plans.beginSuggestion("task-1", 7, "package-run-2", 3))
                .thenReturn(new RollingPackagePlanService.SuggestionAnchor(pending, null));
        when(candidates.eligibility()).thenReturn(new RollingPackagePlanCandidateOrchestrator.Eligibility(
                true, null, null));
        when(openCode.healthy()).thenReturn(true);
        OpenCodeClient.OpenCodeSession remote = new OpenCodeClient.OpenCodeSession(
                "remote-1", Path.of("/tmp/snapshot"), "generation-1", "loopper_internal_test");
        when(candidates.create(eq(Path.of("/tmp/snapshot")), eq("plan-1"), any())).thenReturn(remote);
        when(plans.attachSuggestionSession(pending, "remote-1", "PROMPTING")).thenReturn(prompting);
        MachineCandidateSubmission.RunSnapshot run = run();
        when(candidates.open(eq(prompting), eq(remote), any())).thenReturn(
                new RollingPackagePlanCandidateOrchestrator.Start(remote, run, "candidate prompt"));
        RollingPackagePlanService.Proposal proposal = new RollingPackagePlanService.Proposal(
                "plan-1", 4, "GENERATING", 2, "[]", "{}", "AI", "RUNNING",
                null, null, "now", "now", null);
        when(plans.proposals("task-1")).thenReturn(List.of(proposal));

        service.suggest("task-1", 7, "package-run-2", 3);

        verify(candidates).create(eq(Path.of("/tmp/snapshot")), eq("plan-1"), any());
        verify(candidates).open(eq(prompting), eq(remote), any());
        verify(openCode).promptAsync(eq(remote), any(OpenCodeClient.PromptRequest.class));
        verify(plans).updateSuggestionState(prompting, "RUNNING");
        verify(openCode, never()).createSession(any(), any(), any(),
                eq(OpenCodeClient.SessionProfile.DECOMPOSER_READ_ONLY));
    }

    @Test
    void zeroSubmissionWithRemoteCompletionProofFailsWithoutMarkerExtraction() {
        when(mapper.listGeneratingTaskPackagePlanRevisions()).thenReturn(List.of(running));
        when(mapper.findTaskPackagePlanRevision("plan-1")).thenReturn(Optional.of(running));
        MachineCandidateSubmission.RunSnapshot run = run();
        when(candidates.find(running)).thenReturn(Optional.of(run));
        when(candidates.poll(running, Path.of("/tmp/snapshot"), false)).thenReturn(
                RollingPackagePlanCandidateOrchestrator.Poll.failed(null, run,
                        "ROLLING_PACKAGE_ZERO_SUBMISSION", "no submission", "REMOTE_COMPLETED"));

        service.pollGenerating();

        verify(plans).failSuggestion(running, "ROLLING_PACKAGE_ZERO_SUBMISSION",
                "no submission", "REMOTE_COMPLETED");
        verify(extractor, never()).extractJson(any(), any(), any(), any(), any(), any());
    }

    @Test
    void uncertainCandidateStopPersistsDisconnectedWithoutFailingOwner() {
        when(mapper.listGeneratingTaskPackagePlanRevisions()).thenReturn(List.of(running));
        when(mapper.findTaskPackagePlanRevision("plan-1")).thenReturn(Optional.of(running));
        MachineCandidateSubmission.RunSnapshot run = run();
        when(candidates.find(running)).thenReturn(Optional.of(run));
        when(candidates.poll(running, Path.of("/tmp/snapshot"), false)).thenReturn(
                RollingPackagePlanCandidateOrchestrator.Poll.disconnected(
                        null, run, "OPENCODE_ABORT_UNCONFIRMED", "uncertain"));

        service.pollGenerating();

        verify(plans).disconnectSuggestion(running, "OPENCODE_ABORT_UNCONFIRMED", "uncertain");
        verify(plans, never()).failSuggestion(any(), any(), any(), any());
    }

    @Test
    void persistedAcceptedCandidateResumesFromDisconnectedOwnerAndSettlesWithoutLegacyExtraction() {
        TaskPackagePlanRevisionRow disconnected = row("remote-1", "DISCONNECTED", 3);
        MachineCandidateSubmission.RunSnapshot acceptedRun = new MachineCandidateSubmission.RunSnapshot(
                "candidate-run", MachineCandidateSubmission.CandidateScope.task("task-1"),
                MachineCandidateSubmission.CandidateOwnerRef.taskPackagePlanRevision("plan-1"),
                MachineCandidateKind.ROLLING_PACKAGE_PLAN_V1, "ROLLING_PACKAGE_PLAN_V1",
                4, 1, MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP,
                "ROLLING_PACKAGE_PLAN_V1", "generation-1", "remote-1",
                MachineCandidateRunState.ACCEPTED, 3, 1, "attempt-1", 1);
        RollingPackagePlanAcceptedResultRow accepted = accepted();
        when(mapper.listGeneratingTaskPackagePlanRevisions()).thenReturn(List.of(disconnected));
        when(mapper.findTaskPackagePlanRevision("plan-1")).thenReturn(Optional.of(disconnected));
        when(candidates.find(disconnected)).thenReturn(Optional.of(acceptedRun));
        when(candidates.poll(disconnected, Path.of("/tmp/snapshot"), false)).thenReturn(
                RollingPackagePlanCandidateOrchestrator.Poll.accepted(
                        null, acceptedRun, "ALREADY_ABSENT"));
        when(mapper.findRollingPackagePlanAcceptedResult("candidate-run")).thenReturn(Optional.of(accepted));

        service.pollGenerating();

        verify(plans).completeCandidateSuggestion(disconnected, accepted, "ALREADY_ABSENT");
        verify(extractor, never()).extractJson(any(), any(), any(), any(), any(), any());
        verify(candidates, never()).eligibility();
    }

    private RollingPackagePlanAcceptedResultRow accepted() {
        return new RollingPackagePlanAcceptedResultRow("candidate-run", "plan-1", 4, 1,
                "ROLLING_PACKAGE_PLAN_V1", "{}", "[]", "{}", "a".repeat(64),
                null, "now", "now", 0);
    }

    private TaskPackagePlanRevisionRow row(String remoteId, String remoteState, long version) {
        return new TaskPackagePlanRevisionRow("plan-1", "task-1", "designer-1", "requirement-1",
                4, "GENERATING", "AI", "[]", "{}", remoteId, remoteState,
                null, null, "checkpoint-1", 7, "package-run-2", 3,
                "2026-09-02T00:00:00Z", "2026-09-02T00:00:00Z", null, null, version);
    }

    private MachineCandidateSubmission.RunSnapshot run() {
        return new MachineCandidateSubmission.RunSnapshot("candidate-run",
                MachineCandidateSubmission.CandidateScope.task("task-1"),
                MachineCandidateSubmission.CandidateOwnerRef.taskPackagePlanRevision("plan-1"),
                MachineCandidateKind.ROLLING_PACKAGE_PLAN_V1, "ROLLING_PACKAGE_PLAN_V1",
                4, 1, MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP,
                "ROLLING_PACKAGE_PLAN_V1", "generation-1", "remote-1",
                MachineCandidateRunState.OPEN, 3, 0, null, 0);
    }
}
