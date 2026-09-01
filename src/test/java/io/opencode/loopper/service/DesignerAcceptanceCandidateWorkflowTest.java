package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opencode.loopper.domain.MachineCandidateOutcome;
import io.opencode.loopper.domain.MachineCandidateRunState;
import io.opencode.loopper.persistence.DesignAcceptancePlanningRow;
import io.opencode.loopper.persistence.DesignRequirementRevisionRow;
import io.opencode.loopper.persistence.DesignWorkPackageRow;
import io.opencode.loopper.persistence.AcceptanceCandidateLegacyHandoffRow;
import io.opencode.loopper.persistence.AcceptanceCandidateInternalLaunchRow;
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.LoopSpecCompilationRow;
import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.time.Duration;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import static org.mockito.Mockito.inOrder;

class DesignerAcceptanceCandidateWorkflowTest {

    @Test
    void settledInternalLaunchDispatchesOneInitialPromptBeforePromotingTheExactOwner() {
        DesignerAcceptanceWorkflow acceptance = mock(DesignerAcceptanceWorkflow.class);
        DesignerAcceptanceCandidateOrchestrator candidates = mock(DesignerAcceptanceCandidateOrchestrator.class);
        ProjectService projects = mock(ProjectService.class);
        DesignerModelPromptTransport prompts = mock(DesignerModelPromptTransport.class);
        CandidatePromptDispatchService dispatches = mock(CandidatePromptDispatchService.class);
        AcceptanceCandidateLegacyHandoffCoordinator legacyHandoffs =
                mock(AcceptanceCandidateLegacyHandoffCoordinator.class);
        AcceptanceCandidateInternalLaunchPreparer preparer = mock(AcceptanceCandidateInternalLaunchPreparer.class);
        AcceptanceCandidateInternalLaunchCoordinator launches = mock(AcceptanceCandidateInternalLaunchCoordinator.class);
        DesignerAcceptanceCandidateWorkflow workflow = new DesignerAcceptanceCandidateWorkflow(
                acceptance, candidates, null, legacyHandoffs, projects, prompts, dispatches, preparer, launches, null);
        LoopSpecCompilationRow compilation = mock(LoopSpecCompilationRow.class);
        when(compilation.id()).thenReturn("compilation-1");
        when(compilation.workPackageId()).thenReturn("WP-1");
        when(compilation.state()).thenReturn("RUNNING");
        when(compilation.externalSessionId()).thenReturn("remote-1");
        when(compilation.externalSessionState()).thenReturn("CANDIDATE_PROMPT_PENDING");
        when(compilation.version()).thenReturn(4L);
        DesignerSessionRow designer = mock(DesignerSessionRow.class);
        when(designer.id()).thenReturn("designer-1");
        when(designer.projectId()).thenReturn("project-1");
        when(designer.state()).thenReturn("RUNNING");
        when(designer.externalSessionState()).thenReturn("PENDING");
        DesignAcceptancePlanningRow planning = mock(DesignAcceptancePlanningRow.class);
        when(planning.contractVersion()).thenReturn(DesignerAcceptancePlanning.CONTRACT_VERSION_V7);
        DesignerAcceptanceWorkflow.RoutingResult routing = mock(DesignerAcceptanceWorkflow.RoutingResult.class);
        DesignRequirementRevisionRow revision = mock(DesignRequirementRevisionRow.class);
        DesignWorkPackageRow workPackage = mock(DesignWorkPackageRow.class);
        when(workPackage.packageId()).thenReturn("WP-1");
        OpenCodeClient.OpenCodeModel model = new OpenCodeClient.OpenCodeModel("provider", "model", false);
        OpenCodeClient.OpenCodeSession remote = new OpenCodeClient.OpenCodeSession(
                "remote-1", Path.of("/tmp/project"), "generation-1", "internal-1");
        MachineCandidateSubmission.RunSnapshot run = mock(MachineCandidateSubmission.RunSnapshot.class);
        when(run.runId()).thenReturn("run-1");
        when(run.attemptsUsed()).thenReturn(0);
        when(run.submissionChannel()).thenReturn(MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP);
        AcceptanceCandidateInternalLaunchRow launch = mock(AcceptanceCandidateInternalLaunchRow.class);
        when(launch.id()).thenReturn("launch-1");
        when(launch.state()).thenReturn("SETTLED");
        var prepared = new AcceptanceCandidateInternalLaunchPreparer.Prepared(launch, null);
        var settled = AcceptanceCandidateInternalLaunchCoordinator.Result.settled(launch, remote, run);
        var start = new DesignerAcceptanceCandidateOrchestrator.Start(remote, run, "initial", "launch-1");
        when(acceptance.find("compilation-1")).thenReturn(Optional.of(planning));
        when(acceptance.frozenRoute("compilation-1")).thenReturn(routing);
        when(projects.get("project-1")).thenReturn(
                new ProjectRow("project-1", "project", "/tmp/project", null, "now", "now", 1, 0));
        when(candidates.decide(planning, routing)).thenReturn(new AcceptanceClosedChoiceCandidateCoordinator.Decision(
                AcceptanceClosedChoiceCandidateCoordinator.Action.OPEN_INTERNAL_MCP, "OPEN"));
        when(legacyHandoffs.exists("compilation-1")).thenReturn(false);
        when(preparer.prepareFrozen(compilation, designer, workPackage, planning, routing, model, true))
                .thenReturn(Optional.of(prepared));
        when(launches.advance("compilation-1")).thenReturn(settled);
        when(candidates.settledInternal(settled, planning, routing)).thenReturn(start);
        OpenCodeClient.PromptRequest request = new OpenCodeClient.PromptRequest(
                "initial", null, null, new OpenCodeClient.ResponseFormat.Text(),
                CandidatePromptDispatchService.initialMessageId("run-1"), List.of());
        when(prompts.prepare(eq("initial"), eq("TEXT_MARKER"), any(), eq("designer-1"),
                eq("WP-1"), eq(CandidatePromptDispatchService.initialMessageId("run-1"))))
                .thenReturn(new DesignerModelPromptTransport.PreparedPrompt(request, "a".repeat(64)));
        when(dispatches.advanceInitial(eq(run), eq("launch-1"), eq(remote), eq(request),
                any(), any(), eq("acceptance-initial:compilation-1"), any()))
                .thenReturn(CandidatePromptDispatchService.Result.acknowledged());
        when(candidates.poll(any(), eq(planning), eq(routing), eq(Path.of("/tmp/project")), eq(false)))
                .thenReturn(DesignerAcceptanceCandidateOrchestrator.Poll.none());
        AtomicReference<LoopSpecCompilationRow> owner = new AtomicReference<>(compilation);
        AtomicReference<DesignerSessionRow> designerOwner = new AtomicReference<>(designer);
        DesignerAcceptanceCandidateWorkflow.MarkCandidateRunning mark =
                mock(DesignerAcceptanceCandidateWorkflow.MarkCandidateRunning.class);
        when(mark.apply(compilation, "launch-1", run)).thenAnswer(ignored -> owner.get());
        DesignerAcceptanceCandidateWorkflow.UpdateDesignerProjection projection =
                mock(DesignerAcceptanceCandidateWorkflow.UpdateDesignerProjection.class);
        when(projection.apply(any(), any(), any(), any(), any(), anyInt(), anyInt(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        DesignerAcceptanceCandidateWorkflow.Port port = new DesignerAcceptanceCandidateWorkflow.Port(
                ignored -> revision, (ignored, packageId) -> workPackage, null, null, null, null,
                null, mock(DesignerAcceptanceCandidateWorkflow.Publish.class), ignored -> revision,
                ignored -> designerOwner.get(), ignored -> owner.get(), null, projection, null, null, mark);

        assertThat(workflow.poll(port, compilation, designer, model, false)).isTrue();

        InOrder order = inOrder(preparer, launches, candidates, prompts, dispatches, mark);
        order.verify(preparer).prepareFrozen(compilation, designer, workPackage, planning, routing, model, true);
        order.verify(launches).advance("compilation-1");
        order.verify(candidates).settledInternal(settled, planning, routing);
        order.verify(prompts).prepare(eq("initial"), eq("TEXT_MARKER"), any(), eq("designer-1"),
                eq("WP-1"), eq(CandidatePromptDispatchService.initialMessageId("run-1")));
        order.verify(dispatches).advanceInitial(eq(run), eq("launch-1"), eq(remote), eq(request),
                any(), any(), eq("acceptance-initial:compilation-1"), any());
        order.verify(mark).apply(compilation, "launch-1", run);
    }

    @Test
    void staleLaunchDoesNotRedispatchLegacyAfterTheOwnerAlreadyAdvanced() {
        DesignerAcceptanceWorkflow acceptance = mock(DesignerAcceptanceWorkflow.class);
        DesignerAcceptanceCandidateOrchestrator candidates = mock(DesignerAcceptanceCandidateOrchestrator.class);
        ProjectService projects = mock(ProjectService.class);
        AcceptanceCandidateLegacyHandoffCoordinator legacyHandoffs =
                mock(AcceptanceCandidateLegacyHandoffCoordinator.class);
        AcceptanceCandidateInternalLaunchPreparer preparer = mock(AcceptanceCandidateInternalLaunchPreparer.class);
        AcceptanceCandidateInternalLaunchCoordinator launches = mock(AcceptanceCandidateInternalLaunchCoordinator.class);
        DesignerAcceptanceCandidateWorkflow workflow = new DesignerAcceptanceCandidateWorkflow(
                acceptance, candidates, null, legacyHandoffs, projects, null, mock(CandidatePromptDispatchService.class),
                preparer, launches, null);
        LoopSpecCompilationRow compilation = mock(LoopSpecCompilationRow.class);
        when(compilation.id()).thenReturn("compilation-1");
        when(compilation.workPackageId()).thenReturn("WP-1");
        when(compilation.state()).thenReturn("RUNNING");
        when(compilation.externalSessionId()).thenReturn("legacy-1");
        DesignerSessionRow designer = mock(DesignerSessionRow.class);
        when(designer.id()).thenReturn("designer-1");
        when(designer.projectId()).thenReturn("project-1");
        DesignAcceptancePlanningRow planning = mock(DesignAcceptancePlanningRow.class);
        when(planning.contractVersion()).thenReturn(DesignerAcceptancePlanning.CONTRACT_VERSION_V7);
        DesignerAcceptanceWorkflow.RoutingResult routing = mock(DesignerAcceptanceWorkflow.RoutingResult.class);
        DesignRequirementRevisionRow revision = mock(DesignRequirementRevisionRow.class);
        DesignWorkPackageRow workPackage = mock(DesignWorkPackageRow.class);
        AcceptanceCandidateInternalLaunchRow stale = mock(AcceptanceCandidateInternalLaunchRow.class);
        when(stale.id()).thenReturn("launch-1");
        when(stale.state()).thenReturn("STALE");
        when(stale.lastErrorCode()).thenReturn("OPENCODE_EXACT_LOOKUP_UNSUPPORTED");
        OpenCodeClient.OpenCodeModel model = new OpenCodeClient.OpenCodeModel("provider", "model", false);
        when(acceptance.find("compilation-1")).thenReturn(Optional.of(planning));
        when(acceptance.frozenRoute("compilation-1")).thenReturn(routing);
        when(projects.get("project-1")).thenReturn(
                new ProjectRow("project-1", "project", "/tmp/project", null, "now", "now", 1, 0));
        when(legacyHandoffs.exists("compilation-1")).thenReturn(true);
        when(preparer.prepareFrozen(compilation, designer, workPackage, planning, routing, model, false))
                .thenReturn(Optional.of(new AcceptanceCandidateInternalLaunchPreparer.Prepared(stale, null)));
        when(candidates.poll(compilation, planning, routing, Path.of("/tmp/project"), false))
                .thenReturn(DesignerAcceptanceCandidateOrchestrator.Poll.none());
        DesignerAcceptanceCandidateWorkflow.DispatchLegacy dispatchLegacy =
                mock(DesignerAcceptanceCandidateWorkflow.DispatchLegacy.class);
        DesignerAcceptanceCandidateWorkflow.Port port = new DesignerAcceptanceCandidateWorkflow.Port(
                ignored -> revision, (ignored, packageId) -> workPackage, null, null, dispatchLegacy,
                null, null, null, ignored -> revision, ignored -> designer, ignored -> compilation,
                null, null, null, null, null);

        assertThat(workflow.poll(port, compilation, designer, model, false)).isFalse();

        verifyNoInteractions(launches, dispatchLegacy);
    }

    @Test
    void startLegacyWaitsForPromptSettlementBeforePersistingOwnerProof() {
        DesignerAcceptanceWorkflow acceptance = mock(DesignerAcceptanceWorkflow.class);
        DesignerAcceptanceCandidateOrchestrator candidates = mock(DesignerAcceptanceCandidateOrchestrator.class);
        AcceptanceCandidateProofService proofs = mock(AcceptanceCandidateProofService.class);
        ProjectService projects = mock(ProjectService.class);
        CandidatePromptDispatchService dispatches = mock(CandidatePromptDispatchService.class);
        DesignerAcceptanceCandidateWorkflow workflow = new DesignerAcceptanceCandidateWorkflow(
                acceptance, candidates, proofs, null, projects, null, dispatches, null, null, null);
        LoopSpecCompilationRow compilation = mock(LoopSpecCompilationRow.class);
        when(compilation.id()).thenReturn("compilation-1");
        when(compilation.workPackageId()).thenReturn("WP-1");
        DesignerSessionRow designer = mock(DesignerSessionRow.class);
        when(designer.id()).thenReturn("designer-1");
        when(designer.projectId()).thenReturn("project-1");
        DesignAcceptancePlanningRow planning = mock(DesignAcceptancePlanningRow.class);
        when(planning.contractVersion()).thenReturn(DesignerAcceptancePlanning.CONTRACT_VERSION_V7);
        DesignerAcceptanceWorkflow.RoutingResult routing = mock(DesignerAcceptanceWorkflow.RoutingResult.class);
        MachineCandidateSubmission.RunSnapshot run = mock(MachineCandidateSubmission.RunSnapshot.class);
        when(run.runId()).thenReturn("run-1");
        when(run.state()).thenReturn(MachineCandidateRunState.OPEN);
        OpenCodeClient.OpenCodeSession remote = new OpenCodeClient.OpenCodeSession(
                "remote-1", Path.of("/tmp/project"));
        when(acceptance.find("compilation-1")).thenReturn(Optional.of(planning));
        when(acceptance.frozenRoute("compilation-1")).thenReturn(routing);
        when(projects.get("project-1")).thenReturn(
                new ProjectRow("project-1", "project", "/tmp/project", null, "now", "now", 1, 0));
        when(candidates.poll(compilation, planning, routing, Path.of("/tmp/project"), false))
                .thenReturn(DesignerAcceptanceCandidateOrchestrator.Poll.startLegacy(
                        remote, run, "REMOTE_COMPLETED"));
        when(dispatches.completeForRun("run-1", "REMOTE_COMPLETED")).thenReturn(false);
        DesignRequirementRevisionRow revision = mock(DesignRequirementRevisionRow.class);
        DesignWorkPackageRow workPackage = mock(DesignWorkPackageRow.class);
        DesignerAcceptanceCandidateWorkflow.Port port = new DesignerAcceptanceCandidateWorkflow.Port(
                ignored -> revision, (ignored, packageId) -> workPackage, null, null,
                null, null, null, null, null, null, null, null, null, null);

        assertThat(workflow.poll(port, compilation, designer, null, false)).isTrue();

        verify(dispatches).completeForRun("run-1", "REMOTE_COMPLETED");
        verifyNoInteractions(proofs);
    }

    @Test
    void stoppedRejectedCorrectionRecoversItsWaitingInputIntentOnTheNextPoll() {
        DesignerAcceptanceWorkflow acceptance = mock(DesignerAcceptanceWorkflow.class);
        DesignerAcceptanceCandidateOrchestrator candidates = mock(DesignerAcceptanceCandidateOrchestrator.class);
        ProjectService projects = mock(ProjectService.class);
        DesignerModelPromptTransport prompts = mock(DesignerModelPromptTransport.class);
        CandidatePromptDispatchService dispatches = mock(CandidatePromptDispatchService.class);
        DesignerAcceptanceCandidateWorkflow workflow = new DesignerAcceptanceCandidateWorkflow(
                acceptance, candidates, null, null, projects, prompts, dispatches, null, null, null);
        LoopSpecCompilationRow compilation = mock(LoopSpecCompilationRow.class);
        when(compilation.id()).thenReturn("compilation-1");
        when(compilation.workPackageId()).thenReturn("WP-1");
        when(compilation.state()).thenReturn("RUNNING");
        when(compilation.externalSessionId()).thenReturn("remote-1");
        when(compilation.externalSessionState()).thenReturn("CANDIDATE_RUNNING");
        DesignerSessionRow designer = mock(DesignerSessionRow.class);
        when(designer.id()).thenReturn("designer-1");
        when(designer.projectId()).thenReturn("project-1");
        DesignAcceptancePlanningRow planning = mock(DesignAcceptancePlanningRow.class);
        when(planning.contractVersion()).thenReturn(DesignerAcceptancePlanning.CONTRACT_VERSION_V7);
        DesignerAcceptanceWorkflow.RoutingResult routing = mock(DesignerAcceptanceWorkflow.RoutingResult.class);
        DesignRequirementRevisionRow revision = mock(DesignRequirementRevisionRow.class);
        DesignWorkPackageRow workPackage = mock(DesignWorkPackageRow.class);
        when(workPackage.packageId()).thenReturn("WP-1");
        MachineCandidateSubmission.RunSnapshot run = mock(MachineCandidateSubmission.RunSnapshot.class);
        when(run.runId()).thenReturn("run-1");
        when(run.state()).thenReturn(MachineCandidateRunState.OPEN);
        MachineCandidateSubmission.SubmissionResult submission = new MachineCandidateSubmission.SubmissionResult(
                "run-1", MachineCandidateOutcome.REJECTED, MachineCandidateRunState.OPEN,
                1, 0, true, List.of(new MachineCandidateSubmission.Problem("INVALID", null, "invalid")),
                null, 1, null);
        OpenCodeClient.OpenCodeSession remote = new OpenCodeClient.OpenCodeSession(
                "remote-1", Path.of("/tmp/project"));
        when(acceptance.find("compilation-1")).thenReturn(Optional.of(planning));
        when(acceptance.frozenRoute("compilation-1")).thenReturn(routing);
        when(projects.get("project-1")).thenReturn(
                new ProjectRow("project-1", "project", "/tmp/project", null, "now", "now", 1, 0));
        when(candidates.poll(compilation, planning, routing, Path.of("/tmp/project"), false))
                .thenReturn(DesignerAcceptanceCandidateOrchestrator.Poll.rejected(
                        remote, run, submission, "repair"));
        OpenCodeClient.PromptRequest request = OpenCodeClient.PromptRequest.text("repair");
        when(prompts.prepare(anyString(), anyString(), any(), anyString(), anyString(), anyString()))
                .thenReturn(new DesignerModelPromptTransport.PreparedPrompt(request, "a".repeat(64)));
        when(dispatches.advance(any(), any(), any(), any(), any(), any(), any(), anyString(), any()))
                .thenReturn(CandidatePromptDispatchService.Result.budgetExhausted());
        when(dispatches.prepareRunTermination(anyString(), any())).thenReturn(false, true);
        when(candidates.stopOpened(remote, run)).thenReturn(
                new DesignerAcceptanceCandidateOrchestrator.StopResult(
                        true, "ABORT_ACKNOWLEDGED", null, null));
        LoopSpecCompilationRow marker = mock(LoopSpecCompilationRow.class);
        when(marker.id()).thenReturn("compilation-1");
        when(marker.workPackageId()).thenReturn("WP-1");
        when(marker.state()).thenReturn("RUNNING");
        when(marker.externalSessionId()).thenReturn("remote-1");
        when(marker.externalSessionState()).thenReturn("CORRECTION_STOP_REQUESTED");
        when(marker.lastErrorCode()).thenReturn("ACCEPTANCE_CORRECTION_WAITING_INPUT_PENDING");
        when(marker.lastErrorDetail()).thenReturn("BUDGET_EXHAUSTED");
        LoopSpecCompilationRow proved = mock(LoopSpecCompilationRow.class);
        when(proved.id()).thenReturn("compilation-1");
        when(proved.workPackageId()).thenReturn("WP-1");
        when(proved.state()).thenReturn("RUNNING");
        when(proved.externalSessionId()).thenReturn("remote-1");
        when(proved.externalSessionState()).thenReturn("ABORT_ACKNOWLEDGED");
        when(proved.lastErrorCode()).thenReturn("ACCEPTANCE_CORRECTION_WAITING_INPUT_PENDING");
        when(proved.lastErrorDetail()).thenReturn("BUDGET_EXHAUSTED");
        LoopSpecCompilationRow aborting = mock(LoopSpecCompilationRow.class);
        when(aborting.id()).thenReturn("compilation-1");
        when(aborting.workPackageId()).thenReturn("WP-1");
        when(aborting.state()).thenReturn("RUNNING");
        when(aborting.externalSessionId()).thenReturn("remote-1");
        when(aborting.externalSessionState()).thenReturn("CORRECTION_ABORT_DISPATCHED");
        when(aborting.lastErrorCode()).thenReturn("ACCEPTANCE_CORRECTION_WAITING_INPUT_PENDING");
        when(aborting.lastErrorDetail()).thenReturn("BUDGET_EXHAUSTED");
        AtomicReference<LoopSpecCompilationRow> current = new AtomicReference<>(compilation);
        DesignerAcceptanceCandidateWorkflow.UpdateCompilation update = (row, state, remoteId, remoteState,
                repairCount, code, detail, projectId) -> {
            LoopSpecCompilationRow updated = "ABORT_ACKNOWLEDGED".equals(remoteState) ? proved
                    : "CORRECTION_ABORT_DISPATCHED".equals(remoteState) ? aborting : marker;
            current.set(updated);
            return updated;
        };
        when(candidates.stopOpened(remote, run)).thenAnswer(ignored -> {
            assertThat(current.get()).isSameAs(aborting);
            return new DesignerAcceptanceCandidateOrchestrator.StopResult(
                    true, "ABORT_ACKNOWLEDGED", null, null);
        });
        when(candidates.correctionStopTarget(marker, Path.of("/tmp/project"))).thenReturn(
                new DesignerAcceptanceCandidateOrchestrator.CorrectionStopTarget(remote, run));
        when(dispatches.rejectedProblems("run-1")).thenReturn(submission.problems());
        DesignerAcceptanceCandidateWorkflow.WaitForInput waitForInput =
                mock(DesignerAcceptanceCandidateWorkflow.WaitForInput.class);
        DesignerAcceptanceCandidateWorkflow.Port port = new DesignerAcceptanceCandidateWorkflow.Port(
                ignored -> revision, (ignored, packageId) -> workPackage, null, waitForInput,
                null, null, null, null, null, null, ignored -> current.get(), update, null, null);
        when(dispatches.settleForRun(eq("run-1"), eq("ABORT_ACKNOWLEDGED"), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    invocation.<Runnable>getArgument(2).run();
                    return true;
                });

        assertThat(workflow.poll(port, compilation, designer, null, false)).isTrue();
        verify(candidates, never()).stopOpened(remote, run);
        verify(waitForInput, never()).apply(any(), any(), any(), anyString(), anyString(), any(), any(), anyString());

        assertThat(workflow.poll(port, marker, designer, null, false)).isTrue();

        verify(candidates).stopOpened(remote, run);
        verify(dispatches).settleForRun(eq("run-1"), eq("ABORT_ACKNOWLEDGED"), any(Runnable.class));
        verify(waitForInput).apply(proved, designer, workPackage, "WORK_PACKAGE_MODEL_CALL_LIMIT",
                "候选修正提示无法安全派发：BUDGET_EXHAUSTED", submission.problems(), run,
                "ABORT_ACKNOWLEDGED");
    }

    @Test
    void persistedCorrectionMarkerRecoversAfterTheRunWasAlreadyOwnerClosed() {
        DesignerAcceptanceWorkflow acceptance = mock(DesignerAcceptanceWorkflow.class);
        DesignerAcceptanceCandidateOrchestrator candidates = mock(DesignerAcceptanceCandidateOrchestrator.class);
        ProjectService projects = mock(ProjectService.class);
        CandidatePromptDispatchService dispatches = mock(CandidatePromptDispatchService.class);
        DesignerAcceptanceCandidateWorkflow workflow = new DesignerAcceptanceCandidateWorkflow(
                acceptance, candidates, null, null, projects, null, dispatches, null, null, null);
        LoopSpecCompilationRow marker = mock(LoopSpecCompilationRow.class);
        when(marker.id()).thenReturn("compilation-1");
        when(marker.workPackageId()).thenReturn("WP-1");
        when(marker.state()).thenReturn("RUNNING");
        when(marker.externalSessionId()).thenReturn("remote-1");
        when(marker.externalSessionState()).thenReturn("CORRECTION_STOP_REQUESTED");
        when(marker.lastErrorCode()).thenReturn(DesignerAcceptanceCandidateWorkflow.CORRECTION_WAITING_INPUT_PENDING);
        when(marker.lastErrorDetail()).thenReturn("LOOKUP_UNSUPPORTED");
        LoopSpecCompilationRow proved = mock(LoopSpecCompilationRow.class);
        when(proved.id()).thenReturn("compilation-1");
        when(proved.externalSessionState()).thenReturn("REMOTE_COMPLETED");
        when(proved.lastErrorCode()).thenReturn(DesignerAcceptanceCandidateWorkflow.CORRECTION_WAITING_INPUT_PENDING);
        when(proved.lastErrorDetail()).thenReturn("LOOKUP_UNSUPPORTED");
        LoopSpecCompilationRow aborting = mock(LoopSpecCompilationRow.class);
        when(aborting.id()).thenReturn("compilation-1");
        when(aborting.externalSessionState()).thenReturn("CORRECTION_ABORT_DISPATCHED");
        when(aborting.lastErrorCode()).thenReturn(DesignerAcceptanceCandidateWorkflow.CORRECTION_WAITING_INPUT_PENDING);
        when(aborting.lastErrorDetail()).thenReturn("LOOKUP_UNSUPPORTED");
        DesignerSessionRow designer = mock(DesignerSessionRow.class);
        when(designer.id()).thenReturn("designer-1");
        when(designer.projectId()).thenReturn("project-1");
        DesignWorkPackageRow workPackage = mock(DesignWorkPackageRow.class);
        DesignAcceptancePlanningRow planning = mock(DesignAcceptancePlanningRow.class);
        when(planning.contractVersion()).thenReturn(DesignerAcceptancePlanning.CONTRACT_VERSION_V7);
        DesignerAcceptanceWorkflow.RoutingResult routing = mock(DesignerAcceptanceWorkflow.RoutingResult.class);
        MachineCandidateSubmission.RunSnapshot run = mock(MachineCandidateSubmission.RunSnapshot.class);
        when(run.runId()).thenReturn("run-1");
        when(run.state()).thenReturn(MachineCandidateRunState.CLOSED);
        when(run.closeReason()).thenReturn(MachineCandidateSubmission.CandidateCloseReason.OWNER_REQUESTED);
        List<MachineCandidateSubmission.Problem> problems = List.of(
                new MachineCandidateSubmission.Problem("INVALID", "/facts/0", "invalid"));
        OpenCodeClient.OpenCodeSession remote = new OpenCodeClient.OpenCodeSession(
                "remote-1", Path.of("/tmp/project"));
        when(acceptance.find("compilation-1")).thenReturn(Optional.of(planning));
        when(acceptance.frozenRoute("compilation-1")).thenReturn(routing);
        when(projects.get("project-1")).thenReturn(
                new ProjectRow("project-1", "project", "/tmp/project", null, "now", "now", 1, 0));
        when(candidates.correctionStopTarget(marker, Path.of("/tmp/project"))).thenReturn(
                new DesignerAcceptanceCandidateOrchestrator.CorrectionStopTarget(remote, run));
        when(dispatches.prepareRunTermination(anyString(), any())).thenReturn(true);
        when(dispatches.rejectedProblems("run-1")).thenReturn(problems);
        DesignerAcceptanceCandidateWorkflow.WaitForInput waitForInput =
                mock(DesignerAcceptanceCandidateWorkflow.WaitForInput.class);
        AtomicReference<LoopSpecCompilationRow> current = new AtomicReference<>(marker);
        when(candidates.observeStopped(remote, run)).thenAnswer(ignored -> {
            assertThat(current.get()).isSameAs(aborting);
            return new DesignerAcceptanceCandidateOrchestrator.StopResult(
                    true, "REMOTE_COMPLETED", null, null);
        });
        when(dispatches.settleForRun(eq("run-1"), eq("REMOTE_COMPLETED"), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    invocation.<Runnable>getArgument(2).run();
                    return true;
                });
        DesignerAcceptanceCandidateWorkflow.Port port = new DesignerAcceptanceCandidateWorkflow.Port(
                null, (ignored, packageId) -> workPackage, null, waitForInput, null, null, null, null,
                null, null, ignored -> current.get(),
                (row, state, remoteId, remoteState, repairCount, code, detail, projectId) -> {
                    LoopSpecCompilationRow updated = "CORRECTION_ABORT_DISPATCHED".equals(remoteState)
                            ? aborting : proved;
                    current.set(updated);
                    return updated;
                },
                null, null);

        assertThat(workflow.poll(port, marker, designer, null, false)).isTrue();

        verify(candidates, never()).poll(any(), any(), any(), any(), anyBoolean());
        verify(candidates, never()).stopOpened(any(), any());
        verify(candidates, never()).stopUnopened(any());
        verify(candidates).observeStopped(remote, run);
        verify(waitForInput).apply(proved, designer, workPackage, "DESIGN_INCOMPLETE",
                "候选修正提示无法安全派发：LOOKUP_UNSUPPORTED", problems, run, "REMOTE_COMPLETED");
    }

    @ParameterizedTest
    @EnumSource(value = MachineCandidateRunState.class, names = {"OPEN", "CLOSED"})
    void abortDispatchedCheckpointNeverRepeatsAbortAfterRestart(MachineCandidateRunState runState) {
        DesignerAcceptanceWorkflow acceptance = mock(DesignerAcceptanceWorkflow.class);
        DesignerAcceptanceCandidateOrchestrator candidates = mock(DesignerAcceptanceCandidateOrchestrator.class);
        ProjectService projects = mock(ProjectService.class);
        CandidatePromptDispatchService dispatches = mock(CandidatePromptDispatchService.class);
        DesignerAcceptanceCandidateWorkflow workflow = new DesignerAcceptanceCandidateWorkflow(
                acceptance, candidates, null, null, projects, null, dispatches, null, null, null);
        LoopSpecCompilationRow marker = mock(LoopSpecCompilationRow.class);
        when(marker.id()).thenReturn("compilation-1");
        when(marker.workPackageId()).thenReturn("WP-1");
        when(marker.externalSessionState()).thenReturn("CORRECTION_ABORT_DISPATCHED");
        when(marker.lastErrorCode()).thenReturn(DesignerAcceptanceCandidateWorkflow.CORRECTION_WAITING_INPUT_PENDING);
        when(marker.lastErrorDetail()).thenReturn("BUDGET_EXHAUSTED");
        LoopSpecCompilationRow proved = mock(LoopSpecCompilationRow.class);
        when(proved.externalSessionState()).thenReturn("REMOTE_COMPLETED");
        when(proved.lastErrorCode()).thenReturn(DesignerAcceptanceCandidateWorkflow.CORRECTION_WAITING_INPUT_PENDING);
        when(proved.lastErrorDetail()).thenReturn("BUDGET_EXHAUSTED");
        DesignerSessionRow designer = mock(DesignerSessionRow.class);
        when(designer.id()).thenReturn("designer-1");
        when(designer.projectId()).thenReturn("project-1");
        DesignWorkPackageRow workPackage = mock(DesignWorkPackageRow.class);
        DesignAcceptancePlanningRow planning = mock(DesignAcceptancePlanningRow.class);
        when(planning.contractVersion()).thenReturn(DesignerAcceptancePlanning.CONTRACT_VERSION_V7);
        MachineCandidateSubmission.RunSnapshot run = mock(MachineCandidateSubmission.RunSnapshot.class);
        when(run.runId()).thenReturn("run-1");
        when(run.state()).thenReturn(runState);
        if (runState == MachineCandidateRunState.CLOSED) {
            when(run.closeReason()).thenReturn(MachineCandidateSubmission.CandidateCloseReason.OWNER_REQUESTED);
        }
        OpenCodeClient.OpenCodeSession remote = new OpenCodeClient.OpenCodeSession(
                "remote-1", Path.of("/tmp/project"));
        when(acceptance.find("compilation-1")).thenReturn(Optional.of(planning));
        when(acceptance.frozenRoute("compilation-1")).thenReturn(
                mock(DesignerAcceptanceWorkflow.RoutingResult.class));
        when(projects.get("project-1")).thenReturn(
                new ProjectRow("project-1", "project", "/tmp/project", null, "now", "now", 1, 0));
        when(candidates.correctionStopTarget(marker, Path.of("/tmp/project"))).thenReturn(
                new DesignerAcceptanceCandidateOrchestrator.CorrectionStopTarget(remote, run));
        AtomicInteger abortRequests = new AtomicInteger(1);
        when(candidates.stopOpened(any(), any())).thenAnswer(ignored -> {
            abortRequests.incrementAndGet();
            return new DesignerAcceptanceCandidateOrchestrator.StopResult(false, null, null, null);
        });
        when(candidates.observeStopped(remote, run)).thenReturn(
                new DesignerAcceptanceCandidateOrchestrator.StopResult(
                        true, "REMOTE_COMPLETED", null, null));
        when(dispatches.prepareRunTermination(anyString(), any())).thenReturn(true);
        when(dispatches.rejectedProblems("run-1")).thenReturn(List.of());
        when(dispatches.settleForRun(eq("run-1"), eq("REMOTE_COMPLETED"), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    invocation.<Runnable>getArgument(2).run();
                    return true;
                });
        AtomicReference<LoopSpecCompilationRow> current = new AtomicReference<>(marker);
        DesignerAcceptanceCandidateWorkflow.WaitForInput waitForInput =
                mock(DesignerAcceptanceCandidateWorkflow.WaitForInput.class);
        DesignerAcceptanceCandidateWorkflow.Port port = new DesignerAcceptanceCandidateWorkflow.Port(
                null, (ignored, packageId) -> workPackage, null, waitForInput, null, null, null, null,
                null, null, ignored -> current.get(),
                (row, state, remoteId, remoteState, repairCount, code, detail, projectId) -> {
                    current.set(proved);
                    return proved;
                }, null, null);

        assertThat(workflow.poll(port, marker, designer, null, false)).isTrue();

        assertThat(abortRequests).hasValue(1);
        verify(candidates, never()).stopOpened(any(), any());
        verify(candidates).observeStopped(remote, run);
        verify(waitForInput).apply(proved, designer, workPackage, "WORK_PACKAGE_MODEL_CALL_LIMIT",
                "候选修正提示无法安全派发：BUDGET_EXHAUSTED", List.of(), run, "REMOTE_COMPLETED");
    }

    @Test
    void proofedAcceptedRaceRecoversThroughNormalAcceptedRouteWithoutObservingOrAbortingAgain() {
        DesignerAcceptanceWorkflow acceptance = mock(DesignerAcceptanceWorkflow.class);
        DesignerAcceptanceCandidateOrchestrator candidates = mock(DesignerAcceptanceCandidateOrchestrator.class);
        AcceptanceCandidateProofService proofs = mock(AcceptanceCandidateProofService.class);
        ProjectService projects = mock(ProjectService.class);
        CandidatePromptDispatchService dispatches = mock(CandidatePromptDispatchService.class);
        DesignerAcceptanceCandidateWorkflow workflow = new DesignerAcceptanceCandidateWorkflow(
                acceptance, candidates, proofs, null, projects, null, dispatches, null, null, null);
        LoopSpecCompilationRow marker = correctionMarker("REMOTE_COMPLETED", "BUDGET_EXHAUSTED");
        LoopSpecCompilationRow proofed = mock(LoopSpecCompilationRow.class);
        DesignerSessionRow designer = designer();
        DesignWorkPackageRow workPackage = mock(DesignWorkPackageRow.class);
        DesignAcceptancePlanningRow planning = v7Planning();
        DesignerAcceptanceWorkflow.RoutingResult routing = mock(DesignerAcceptanceWorkflow.RoutingResult.class);
        MachineCandidateSubmission.RunSnapshot accepted = mock(MachineCandidateSubmission.RunSnapshot.class);
        when(accepted.runId()).thenReturn("run-1");
        when(accepted.state()).thenReturn(MachineCandidateRunState.ACCEPTED);
        OpenCodeClient.OpenCodeSession remote = new OpenCodeClient.OpenCodeSession(
                "remote-1", Path.of("/tmp/project"));
        DesignerAcceptanceCandidateOrchestrator.StopResult race =
                DesignerAcceptanceCandidateOrchestrator.StopResult.terminalRace(
                        accepted, "REMOTE_COMPLETED");
        when(acceptance.find("compilation-1")).thenReturn(Optional.of(planning));
        when(acceptance.frozenRoute("compilation-1")).thenReturn(routing);
        when(projects.get("project-1")).thenReturn(project());
        when(candidates.correctionStopTarget(marker, Path.of("/tmp/project"))).thenReturn(
                new DesignerAcceptanceCandidateOrchestrator.CorrectionStopTarget(remote, accepted));
        when(candidates.routeTerminalAfterStop("compilation-1", remote, race)).thenReturn(
                DesignerAcceptanceCandidateOrchestrator.Poll.accepted(remote, accepted, "REMOTE_COMPLETED"));
        when(dispatches.prepareRunTermination(anyString(), any())).thenReturn(true);
        AtomicBoolean insideSettlement = new AtomicBoolean();
        when(proofs.persist(accepted, "REMOTE_COMPLETED")).thenAnswer(ignored -> {
            assertThat(insideSettlement).isTrue();
            return proofed;
        });
        when(dispatches.settleForRun(eq("run-1"), eq("REMOTE_COMPLETED"), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    insideSettlement.set(true);
                    invocation.<Runnable>getArgument(2).run();
                    insideSettlement.set(false);
                    return true;
                });
        DesignerAcceptanceCandidateWorkflow.CompleteAccepted complete =
                mock(DesignerAcceptanceCandidateWorkflow.CompleteAccepted.class);
        DesignerAcceptanceCandidateWorkflow.WaitForInput waitForInput =
                mock(DesignerAcceptanceCandidateWorkflow.WaitForInput.class);
        DesignerAcceptanceCandidateWorkflow.Port port = new DesignerAcceptanceCandidateWorkflow.Port(
                null, (ignored, packageId) -> workPackage, complete, waitForInput, null, null, null, null,
                null, null, ignored -> marker, null, null, null);

        assertThat(workflow.poll(port, marker, designer, null, false)).isTrue();

        verify(candidates, never()).observeStopped(any(), any());
        verify(candidates, never()).stopOpened(any(), any());
        verify(proofs).persist(accepted, "REMOTE_COMPLETED");
        verify(complete).apply(proofed, designer, workPackage, remote, accepted, "REMOTE_COMPLETED");
        verifyNoInteractions(waitForInput);
    }

    @Test
    void abortDispatchedWaitingRaceObservesOnlyThenUsesNormalWaitingInputRoute() {
        DesignerAcceptanceWorkflow acceptance = mock(DesignerAcceptanceWorkflow.class);
        DesignerAcceptanceCandidateOrchestrator candidates = mock(DesignerAcceptanceCandidateOrchestrator.class);
        AcceptanceCandidateProofService proofs = mock(AcceptanceCandidateProofService.class);
        ProjectService projects = mock(ProjectService.class);
        CandidatePromptDispatchService dispatches = mock(CandidatePromptDispatchService.class);
        DesignerAcceptanceCandidateWorkflow workflow = new DesignerAcceptanceCandidateWorkflow(
                acceptance, candidates, proofs, null, projects, null, dispatches, null, null, null);
        LoopSpecCompilationRow marker = correctionMarker("CORRECTION_ABORT_DISPATCHED", "LOOKUP_UNSUPPORTED");
        LoopSpecCompilationRow proofed = mock(LoopSpecCompilationRow.class);
        DesignerSessionRow designer = designer();
        DesignWorkPackageRow workPackage = mock(DesignWorkPackageRow.class);
        DesignAcceptancePlanningRow planning = v7Planning();
        DesignerAcceptanceWorkflow.RoutingResult routing = mock(DesignerAcceptanceWorkflow.RoutingResult.class);
        MachineCandidateSubmission.RunSnapshot waiting = mock(MachineCandidateSubmission.RunSnapshot.class);
        when(waiting.runId()).thenReturn("run-1");
        when(waiting.state()).thenReturn(MachineCandidateRunState.WAITING_INPUT);
        List<MachineCandidateSubmission.Problem> problems = List.of(
                new MachineCandidateSubmission.Problem("INVALID", "/facts/0", "invalid"));
        MachineCandidateSubmission.SubmissionResult terminal = new MachineCandidateSubmission.SubmissionResult(
                "run-1", MachineCandidateOutcome.WAITING_INPUT, MachineCandidateRunState.WAITING_INPUT,
                2, 0, false, problems, null, 2, "{}");
        OpenCodeClient.OpenCodeSession remote = new OpenCodeClient.OpenCodeSession(
                "remote-1", Path.of("/tmp/project"));
        DesignerAcceptanceCandidateOrchestrator.StopResult race =
                DesignerAcceptanceCandidateOrchestrator.StopResult.terminalRace(
                        waiting, "REMOTE_COMPLETED");
        when(acceptance.find("compilation-1")).thenReturn(Optional.of(planning));
        when(acceptance.frozenRoute("compilation-1")).thenReturn(routing);
        when(projects.get("project-1")).thenReturn(project());
        when(candidates.correctionStopTarget(marker, Path.of("/tmp/project"))).thenReturn(
                new DesignerAcceptanceCandidateOrchestrator.CorrectionStopTarget(remote, waiting));
        when(candidates.observeStopped(remote, waiting)).thenReturn(race);
        when(candidates.routeTerminalAfterStop("compilation-1", remote, race)).thenReturn(
                DesignerAcceptanceCandidateOrchestrator.Poll.waiting(
                        remote, waiting, terminal, "REMOTE_COMPLETED"));
        when(dispatches.prepareRunTermination(anyString(), any())).thenReturn(true);
        when(proofs.persist(waiting, "REMOTE_COMPLETED")).thenReturn(proofed);
        when(dispatches.settleForRun(eq("run-1"), eq("REMOTE_COMPLETED"), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    invocation.<Runnable>getArgument(2).run();
                    return true;
                });
        DesignerAcceptanceCandidateWorkflow.WaitForInput waitForInput =
                mock(DesignerAcceptanceCandidateWorkflow.WaitForInput.class);
        DesignerAcceptanceCandidateWorkflow.Port port = new DesignerAcceptanceCandidateWorkflow.Port(
                null, (ignored, packageId) -> workPackage, null, waitForInput, null, null, null, null,
                null, null, ignored -> marker, null, null, null);

        assertThat(workflow.poll(port, marker, designer, null, false)).isTrue();

        verify(candidates, never()).stopOpened(any(), any());
        verify(candidates).observeStopped(remote, waiting);
        verify(proofs).persist(waiting, "REMOTE_COMPLETED");
        verify(waitForInput).apply(proofed, designer, workPackage,
                "ACCEPTANCE_CANDIDATE_WAITING_INPUT", "INVALID /facts/0: invalid",
                problems, waiting, "REMOTE_COMPLETED");
    }

    @Test
    void abortDispatchedUnconfirmedObservationKeepsTheCorrectionMarkerFailClosed() {
        DesignerAcceptanceWorkflow acceptance = mock(DesignerAcceptanceWorkflow.class);
        DesignerAcceptanceCandidateOrchestrator candidates = mock(DesignerAcceptanceCandidateOrchestrator.class);
        AcceptanceCandidateProofService proofs = mock(AcceptanceCandidateProofService.class);
        ProjectService projects = mock(ProjectService.class);
        CandidatePromptDispatchService dispatches = mock(CandidatePromptDispatchService.class);
        DesignerAcceptanceCandidateWorkflow workflow = new DesignerAcceptanceCandidateWorkflow(
                acceptance, candidates, proofs, null, projects, null, dispatches, null, null, null);
        LoopSpecCompilationRow marker = correctionMarker(
                "CORRECTION_ABORT_DISPATCHED", "BUDGET_EXHAUSTED");
        DesignerSessionRow designer = designer();
        DesignAcceptancePlanningRow planning = v7Planning();
        MachineCandidateSubmission.RunSnapshot open = mock(MachineCandidateSubmission.RunSnapshot.class);
        when(open.runId()).thenReturn("run-1");
        when(open.state()).thenReturn(MachineCandidateRunState.OPEN);
        OpenCodeClient.OpenCodeSession remote = new OpenCodeClient.OpenCodeSession(
                "remote-1", Path.of("/tmp/project"));
        when(acceptance.find("compilation-1")).thenReturn(Optional.of(planning));
        when(acceptance.frozenRoute("compilation-1")).thenReturn(
                mock(DesignerAcceptanceWorkflow.RoutingResult.class));
        when(projects.get("project-1")).thenReturn(project());
        when(candidates.correctionStopTarget(marker, Path.of("/tmp/project"))).thenReturn(
                new DesignerAcceptanceCandidateOrchestrator.CorrectionStopTarget(remote, open));
        when(candidates.observeStopped(remote, open)).thenReturn(
                DesignerAcceptanceCandidateOrchestrator.StopResult.unconfirmed(
                        "OPENCODE_ACCEPTANCE_CANDIDATE_STOP_UNCONFIRMED", "still running"));
        when(dispatches.prepareRunTermination(anyString(), any())).thenReturn(true);
        DesignerAcceptanceCandidateWorkflow.Port port = new DesignerAcceptanceCandidateWorkflow.Port(
                null, (ignored, packageId) -> mock(DesignWorkPackageRow.class), null, null,
                null, null, null, null, null, null, ignored -> marker, null, null, null);

        assertThat(workflow.poll(port, marker, designer, null, false)).isTrue();

        verify(candidates, never()).stopOpened(any(), any());
        verify(candidates).observeStopped(remote, open);
        verify(dispatches, never()).settleForRun(anyString(), anyString(), any());
        verifyNoInteractions(proofs);
    }

    @Test
    void resultUnknownProjectionIsDurableAndIdempotentWithoutClosingTheCandidateOwner() {
        DesignerAcceptanceCandidateWorkflow workflow = new DesignerAcceptanceCandidateWorkflow(
                null, null, null, null, null, null, null, null, null, null);
        AtomicReference<LoopSpecCompilationRow> compilation = new AtomicReference<>(new LoopSpecCompilationRow(
                "compilation-1", "designer-1", 3, "RUNNING", "remote-1", "CANDIDATE_RUNNING", 0,
                "message-1", 1, null, null, "now", "now", 4));
        AtomicReference<DesignerSessionRow> designer = new AtomicReference<>(new DesignerSessionRow(
                "designer-1", "project-1", "RUNNING", "READ_ONLY", "now", "now", 7,
                "remote-1", "CANDIDATE_RUNNING", null, "COMPILING", 3, 0, 1, "WP-1"));
        AtomicInteger compilationWrites = new AtomicInteger();
        AtomicInteger designerWrites = new AtomicInteger();
        DesignerAcceptanceCandidateWorkflow.Port port = new DesignerAcceptanceCandidateWorkflow.Port(
                null, null, null, null, null, null, null, null, null,
                ignored -> designer.get(), ignored -> compilation.get(),
                (row, state, remoteId, remoteState, repairCount, code, detail, projectId) -> {
                    compilationWrites.incrementAndGet();
                    LoopSpecCompilationRow updated = new LoopSpecCompilationRow(
                            row.id(), row.designerSessionId(), row.designRevision(), state.name(), remoteId,
                            remoteState, repairCount, row.sourceDesignMessageId(), row.sourceDraftVersion(),
                            code, detail, row.createdAt(), "later", row.version() + 1);
                    compilation.set(updated);
                    return updated;
                },
                (row, state, phase, remoteId, remoteState, designRevision, redesignCount,
                        requirementRevision, packageId) -> {
                    designerWrites.incrementAndGet();
                    DesignerSessionRow updated = new DesignerSessionRow(
                            row.id(), row.projectId(), state.name(), row.accessMode(), row.createdAt(), "later",
                            row.version() + 1, remoteId, remoteState, row.loopDraftId(), phase.name(),
                            designRevision, redesignCount, requirementRevision, packageId);
                    designer.set(updated);
                    return updated;
                }, null);
        OpenCodeClient.OpenCodeSession remote = new OpenCodeClient.OpenCodeSession(
                "remote-1", Path.of("/tmp/project"));

        workflow.disconnectHandoff(port, compilation.get(), designer.get(), remote,
                "OPENCODE_PROMPT_RESULT_UNKNOWN", "POST acknowledgement is still unknown");
        workflow.disconnectHandoff(port, compilation.get(), designer.get(), remote,
                "OPENCODE_PROMPT_RESULT_UNKNOWN", "POST acknowledgement is still unknown");

        assertThat(compilationWrites).hasValue(1);
        assertThat(designerWrites).hasValue(1);
        assertThat(compilation.get().state()).isEqualTo("RUNNING");
        assertThat(compilation.get().externalSessionState()).isEqualTo("DISCONNECTED");
        assertThat(compilation.get().lastErrorCode()).isEqualTo("OPENCODE_PROMPT_RESULT_UNKNOWN");
        assertThat(designer.get().state()).isEqualTo("RUNNING");
        assertThat(designer.get().externalSessionState()).isEqualTo("DISCONNECTED");
    }

    private static LoopSpecCompilationRow correctionMarker(String remoteState, String reason) {
        LoopSpecCompilationRow marker = mock(LoopSpecCompilationRow.class);
        when(marker.id()).thenReturn("compilation-1");
        when(marker.workPackageId()).thenReturn("WP-1");
        when(marker.state()).thenReturn("RUNNING");
        when(marker.externalSessionId()).thenReturn("remote-1");
        when(marker.externalSessionState()).thenReturn(remoteState);
        when(marker.lastErrorCode()).thenReturn(
                DesignerAcceptanceCandidateWorkflow.CORRECTION_WAITING_INPUT_PENDING);
        when(marker.lastErrorDetail()).thenReturn(reason);
        return marker;
    }

    private static DesignerSessionRow designer() {
        DesignerSessionRow designer = mock(DesignerSessionRow.class);
        when(designer.id()).thenReturn("designer-1");
        when(designer.projectId()).thenReturn("project-1");
        return designer;
    }

    private static DesignAcceptancePlanningRow v7Planning() {
        DesignAcceptancePlanningRow planning = mock(DesignAcceptancePlanningRow.class);
        when(planning.contractVersion()).thenReturn(DesignerAcceptancePlanning.CONTRACT_VERSION_V7);
        return planning;
    }

    private static ProjectRow project() {
        return new ProjectRow("project-1", "project", "/tmp/project", null, "now", "now", 1, 0);
    }

    @Test
    void handoffIoLeaseAlwaysOutlivesTheConfiguredRequestTimeout() {
        LoopperProperties properties = new LoopperProperties();
        properties.getOpenCode().setConnectTimeout(Duration.ofSeconds(5));
        properties.getOpenCode().setRequestTimeout(Duration.ofSeconds(30));

        Duration ttl = AcceptanceCandidateLegacyHandoffCoordinator.claimTtl(properties);

        assertThat(ttl).isGreaterThan(Duration.ofSeconds(70));
        assertThat(ttl).isGreaterThanOrEqualTo(Duration.ofSeconds(85));
    }

    @Test
    void settledLegacyHandoffIsTerminalAndCannotBeAdvancedAgainAfterRestart() {
        LoopperProperties properties = new LoopperProperties();
        AcceptanceCandidateLegacyHandoffService handoffs = mock(AcceptanceCandidateLegacyHandoffService.class);
        AcceptanceCandidateLegacyHandoffRow settled = mock(AcceptanceCandidateLegacyHandoffRow.class);
        when(settled.state()).thenReturn("SETTLED");
        when(handoffs.find("compilation-1")).thenReturn(Optional.of(settled));
        AcceptanceCandidateLegacyHandoffCoordinator coordinator =
                new AcceptanceCandidateLegacyHandoffCoordinator(
                        handoffs, null, null, null, null, null, null, properties);

        assertThat(coordinator.requiresAdvance("compilation-1")).isFalse();
        assertThat(coordinator.terminal("compilation-1")).get()
                .extracting(AcceptanceCandidateLegacyHandoffCoordinator.Terminal::state)
                .isEqualTo("SETTLED");
    }

    @Test
    void staleTerminalReroutesCurrentCompilationWithoutFailingItsNewOwner() {
        AcceptanceCandidateLegacyHandoffCoordinator handoffs =
                mock(AcceptanceCandidateLegacyHandoffCoordinator.class);
        DesignerAcceptanceCandidateWorkflow workflow = new DesignerAcceptanceCandidateWorkflow(
                null, null, null, handoffs, null, null, mock(CandidatePromptDispatchService.class), null, null, null);
        LoopSpecCompilationRow current = new LoopSpecCompilationRow(
                "compilation-1", "designer-1", 3, "RUNNING", "new-owner-remote", "RUNNING", 0,
                "message-1", 1, null, null, "now", "now", 12,
                "WP-1", 0, null, "PLANNING", null, 0);
        DesignerSessionRow session = new DesignerSessionRow(
                "designer-1", "project-1", "RUNNING", "READ_ONLY", "now", "now", 9,
                "new-owner-remote", "RUNNING", null, "COMPILING", 3, 0, 1, "WP-1");
        AtomicBoolean failed = new AtomicBoolean();
        DesignerAcceptanceCandidateWorkflow.Port port = new DesignerAcceptanceCandidateWorkflow.Port(
                null, null, null, null, null, null, null, null, null, null,
                ignored -> current, null, null,
                (row, owner, code, detail, stopRemote) -> failed.set(true));
        when(handoffs.terminal(current.id())).thenReturn(Optional.of(
                new AcceptanceCandidateLegacyHandoffCoordinator.Terminal("STALE", null, null)));

        boolean handled = workflow.advanceLegacyHandoffIfRequired(port, current, session, null);

        assertThat(handled).as("STALE belongs to the retired handoff, so the current owner must be rerouted")
                .isFalse();
        assertThat(failed).as("the retired handoff must not fail the replacement owner").isFalse();
    }
}
