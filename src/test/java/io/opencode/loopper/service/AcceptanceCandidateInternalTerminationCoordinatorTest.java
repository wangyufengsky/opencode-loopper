package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.domain.MachineCandidateRunState;
import io.opencode.loopper.persistence.AcceptanceCandidateInternalLaunchCleanupRemoteRow;
import io.opencode.loopper.persistence.AcceptanceCandidateInternalLaunchRow;
import io.opencode.loopper.persistence.AcceptanceCandidateInternalTerminationIntentRow;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class AcceptanceCandidateInternalTerminationCoordinatorTest {
    private AcceptanceCandidateInternalTerminationIntentStore intents;
    private AcceptanceCandidateInternalLaunchService launches;
    private AcceptanceCandidateInternalLaunchCleanupLedger cleanup;
    private AcceptanceCandidateInternalTerminationSettlementService settlement;
    private CandidatePromptDispatchService prompts;
    private CandidateRuntimeBindingService bindings;
    private OpenCodeClient openCode;
    private AcceptanceCandidateInternalTerminationCoordinator coordinator;

    @BeforeEach
    void setUp() {
        intents = mock(AcceptanceCandidateInternalTerminationIntentStore.class);
        launches = mock(AcceptanceCandidateInternalLaunchService.class);
        cleanup = mock(AcceptanceCandidateInternalLaunchCleanupLedger.class);
        settlement = mock(AcceptanceCandidateInternalTerminationSettlementService.class);
        prompts = mock(CandidatePromptDispatchService.class);
        bindings = mock(CandidateRuntimeBindingService.class);
        openCode = mock(OpenCodeClient.class);
        coordinator = new AcceptanceCandidateInternalTerminationCoordinator(
                intents, launches, cleanup, settlement, prompts, bindings, openCode, Duration.ofMinutes(2));
    }

    @Test
    void preparedTerminatesLocallyWithoutAnyRemoteBoundary() {
        AcceptanceCandidateInternalTerminationIntentRow requested = intent("REQUESTED");
        AcceptanceCandidateInternalTerminationIntentRow ready = intent("READY");
        when(intents.requireIntent("intent")).thenReturn(requested);
        when(launches.require("launch")).thenReturn(launch("PREPARED", null, false));
        when(settlement.finishWithoutRemote("intent")).thenReturn(ready);

        assertThat(coordinator.advance("intent").status())
                .isEqualTo(AcceptanceCandidateInternalTerminationCoordinator.Status.READY);
        verify(openCode, never()).findSessionsByExactTitle(any());
        verify(openCode, never()).abortWithConfirmation(any());
        verify(openCode, never()).createSession(any(OpenCodeClient.SessionCreationPlan.class));
    }

    @Test
    void uncertainCreateUsesExactLookupAndZeroMatchRemainsDisconnectedWithoutPost() {
        AcceptanceCandidateInternalTerminationIntentRow requested = intent("REQUESTED");
        AcceptanceCandidateInternalTerminationIntentRow disconnected = intent("DISCONNECTED");
        AcceptanceCandidateInternalLaunchRow launch = launch("CREATING", null, true);
        OpenCodeClient.SessionCreationPlan plan = plan();
        when(intents.requireIntent("intent")).thenReturn(requested);
        when(launches.require("launch")).thenReturn(launch);
        when(launches.plan(launch)).thenReturn(plan);
        when(openCode.findSessionsByExactTitle(plan)).thenReturn(new OpenCodeClient.SessionLookup(true, List.of()));
        when(settlement.disconnectCreateUnknown(eq("intent"), anyString(), anyString()))
                .thenReturn(disconnected);

        assertThat(coordinator.advance("intent").status())
                .isEqualTo(AcceptanceCandidateInternalTerminationCoordinator.Status.DISCONNECTED);
        verify(openCode, times(1)).requireCandidateSessionReady(plan);
        verify(openCode, times(1)).findSessionsByExactTitle(plan);
        verify(openCode, never()).createSession(any(OpenCodeClient.SessionCreationPlan.class));
        verify(openCode, never()).abortWithConfirmation(any());
    }

    @Test
    void multiMatchRegistersEveryAttestedRemoteAndNeverAdoptsOrPosts() {
        AcceptanceCandidateInternalTerminationIntentRow requested = intent("REQUESTED");
        AcceptanceCandidateInternalLaunchRow launch = launch("CREATING", null, true);
        OpenCodeClient.SessionCreationPlan plan = plan();
        OpenCodeClient.SessionAttestation first = attestation("remote-1", plan);
        OpenCodeClient.SessionAttestation second = attestation("remote-2", plan);
        List<AcceptanceCandidateInternalLaunchCleanupRemoteRow> remotes = List.of(
                cleanup("remote-1", "DISCOVERED", false, null),
                cleanup("remote-2", "DISCOVERED", false, null));
        when(intents.requireIntent("intent")).thenReturn(requested);
        when(launches.require("launch")).thenReturn(launch);
        when(launches.plan(launch)).thenReturn(plan);
        when(cleanup.list("launch")).thenReturn(List.of(), remotes, remotes);
        when(openCode.findSessionsByExactTitle(plan))
                .thenReturn(new OpenCodeClient.SessionLookup(true, List.of(first, second)));
        when(settlement.registerCleanup("intent", List.of(first, second))).thenReturn(remotes);
        when(cleanup.claimStop(eq("launch"), eq("remote-1"), anyString(), any(), any()))
                .thenReturn(AcceptanceCandidateInternalLaunchCleanupLedger.StopClaim.unavailable(0, "CLAIMED"));

        assertThat(coordinator.advance("intent").status())
                .isEqualTo(AcceptanceCandidateInternalTerminationCoordinator.Status.PENDING);
        verify(bindings).bindInternalAttested(first, plan);
        verify(bindings).bindInternalAttested(second, plan);
        verify(settlement).registerCleanup("intent", List.of(first, second));
        verify(openCode, never()).createSession(any(OpenCodeClient.SessionCreationPlan.class));
        verify(openCode, never()).abortWithConfirmation(any());
    }

    @Test
    void settledFencesPromptsBeforeAnyRemoteStop() {
        AcceptanceCandidateInternalTerminationIntentRow requested = intent("REQUESTED");
        when(intents.requireIntent("intent")).thenReturn(requested);
        when(launches.require("launch")).thenReturn(launch("SETTLED", "remote-1", true));
        when(prompts.prepareRunTermination(eq("run"), any())).thenReturn(false);

        assertThat(coordinator.advance("intent").code()).isEqualTo("CANDIDATE_PROMPT_IO_IN_FLIGHT");
        verify(cleanup, never()).claimStop(anyString(), anyString(), anyString(), any(), any());
        verify(openCode, never()).abortWithConfirmation(any());
    }

    @Test
    void settledRecoveryUsesExistingProofAndSettlesPromptRunOwnerLaunchAtomically() {
        AcceptanceCandidateInternalTerminationIntentRow requested = intent("REQUESTED");
        AcceptanceCandidateInternalTerminationIntentRow ready = intent("READY");
        AcceptanceCandidateInternalTerminationSettlementService.SettledRun terminalRace =
                new AcceptanceCandidateInternalTerminationSettlementService.SettledRun(
                        AcceptanceCandidateInternalTerminationSettlementService.RunOutcome.TERMINAL_RACE,
                        terminalRun(MachineCandidateRunState.ACCEPTED, null));
        AcceptanceCandidateInternalLaunchRow launch = launch("SETTLED", "remote-1", true);
        AcceptanceCandidateInternalLaunchCleanupRemoteRow stopped =
                cleanup("remote-1", "STOPPED", true, "ABORT_ACKNOWLEDGED");
        when(intents.requireIntent("intent")).thenReturn(requested, ready);
        when(launches.require("launch")).thenReturn(launch, launch);
        when(cleanup.list("launch")).thenReturn(List.of(stopped), List.of(stopped));
        when(prompts.prepareRunTermination(eq("run"), any())).thenReturn(true);
        when(settlement.finishSettled("intent", "ABORT_ACKNOWLEDGED")).thenReturn(terminalRace);
        when(prompts.settleForRun(eq("run"), eq("ABORT_ACKNOWLEDGED"), any())).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(2).run();
            return true;
        });

        AcceptanceCandidateInternalTerminationCoordinator.Result result = coordinator.advance("intent");

        assertThat(result.status()).isEqualTo(AcceptanceCandidateInternalTerminationCoordinator.Status.READY);
        assertThat(result.settledRun()).containsSame(terminalRace);
        InOrder order = inOrder(prompts, settlement);
        order.verify(prompts).prepareRunTermination(eq("run"), any());
        order.verify(prompts).settleForRun(eq("run"), eq("ABORT_ACKNOWLEDGED"), any());
        order.verify(settlement).finishSettled("intent", "ABORT_ACKNOWLEDGED");
        verify(openCode, never()).abortWithConfirmation(any());
    }

    @Test
    void readyRecoveryReturnsPersistedTerminalRaceWithoutRemoteIo() {
        AcceptanceCandidateInternalTerminationIntentRow ready = intent("READY");
        AcceptanceCandidateInternalTerminationSettlementService.SettledRun terminalRace =
                new AcceptanceCandidateInternalTerminationSettlementService.SettledRun(
                        AcceptanceCandidateInternalTerminationSettlementService.RunOutcome.TERMINAL_RACE,
                        terminalRun(MachineCandidateRunState.WAITING_INPUT, null));
        when(intents.requireIntent("intent")).thenReturn(ready);
        when(settlement.findSettledRun("intent")).thenReturn(java.util.Optional.of(terminalRace));

        AcceptanceCandidateInternalTerminationCoordinator.Result result = coordinator.advance("intent");

        assertThat(result.status()).isEqualTo(AcceptanceCandidateInternalTerminationCoordinator.Status.READY);
        assertThat(result.settledRun()).containsSame(terminalRace);
        verify(openCode, never()).abortWithConfirmation(any());
        verify(cleanup, never()).claimStop(anyString(), anyString(), anyString(), any(), any());
    }

    private MachineCandidateSubmission.RunSnapshot terminalRun(
            MachineCandidateRunState state, MachineCandidateSubmission.CandidateCloseReason reason) {
        return new MachineCandidateSubmission.RunSnapshot(
                "run", MachineCandidateSubmission.CandidateScope.designerSession("designer"),
                MachineCandidateSubmission.CandidateOwnerRef.loopSpecCompilation("compilation"),
                MachineCandidateKind.ACCEPTANCE_CLOSED_CHOICE_V7,
                AcceptanceClosedChoiceCandidateCoordinator.WORKFLOW_STEP, 1, 5,
                MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP,
                AcceptanceClosedChoiceCandidateCoordinator.CONTRACT_VERSION,
                "generation-1", "remote-1", state, 2, 1, "attempt-1", 1, reason);
    }

    private AcceptanceCandidateInternalTerminationIntentRow intent(String state) {
        return new AcceptanceCandidateInternalTerminationIntentRow(
                "intent", "launch", "designer", "compilation", "run",
                "DESIGNER_CANCEL", "CANCELLED", false, null, "DESIGNER_CANCEL", state, 3, null, null,
                "READY".equals(state) ? "ready" : null, null, null, null, "created", "updated", 0);
    }

    private AcceptanceCandidateInternalLaunchRow launch(String state, String remoteId, boolean dispatched) {
        return new AcceptanceCandidateInternalLaunchRow(
                "launch", "compilation", "designer", "WP-1", 1, "message", 0, "d".repeat(64),
                3, "AI_DISAMBIGUATION_V6", "{}", "e".repeat(64), "{}", "f".repeat(64), "run",
                "ACCEPTANCE_CLOSED_CHOICE_V7", "ACCEPTANCE_CLOSED_CHOICE_V7", state, 4,
                "SETTLED".equals(state) ? 5L : null, "SETTLED".equals(state) ? "settled" : null,
                "Acceptance termination", "/tmp/project", "generation-1", true,
                "loopper_internal_generation_1", "a".repeat(64), null, null, null,
                "ACCEPTANCE_CLOSED_CHOICE_CANDIDATE_NO_TOOLS", "[]", "1".repeat(64), "2".repeat(64),
                "A".repeat(43), "LOCAL_REQUEST_ATTESTED", null, null, null, 1, dispatched,
                dispatched ? "dispatch" : null, remoteId, remoteId == null ? null : "attested",
                null, null, null, null, null, "created", "updated", 0);
    }

    private OpenCodeClient.SessionCreationPlan plan() {
        String credential = "A".repeat(43);
        String title = OpenCodeClient.recoveryTitle("Acceptance termination", credential);
        List<OpenCodeClient.SessionPermissionRule> permissions = List.of();
        String permissionDigest = OpenCodeClient.permissionPolicyDigest(permissions);
        String requestDigest = OpenCodeClient.sessionCreationRequestSha256(
                Path.of("/tmp/project"), title, "generation-1", true,
                "loopper_internal_generation_1", "a".repeat(64), null,
                OpenCodeClient.SessionProfile.ACCEPTANCE_CLOSED_CHOICE_CANDIDATE_NO_TOOLS,
                permissionDigest, credential);
        return OpenCodeClient.SessionCreationPlan.fromPersisted(
                Path.of("/tmp/project"), title, "generation-1", true,
                "loopper_internal_generation_1", "a".repeat(64), null,
                OpenCodeClient.SessionProfile.ACCEPTANCE_CLOSED_CHOICE_CANDIDATE_NO_TOOLS,
                permissions, permissionDigest, credential, requestDigest);
    }

    private OpenCodeClient.SessionAttestation attestation(
            String remoteId, OpenCodeClient.SessionCreationPlan plan) {
        return new OpenCodeClient.SessionAttestation(remoteId, plan.canonicalDirectory(), plan.exactTitle(),
                plan.runtimeGenerationId(), plan.managed(), plan.internalMcpServer(), plan.endpointFingerprint(),
                plan.model(), plan.profile(), plan.permissionPolicy(), plan.permissionPolicyDigest(),
                plan.creationCredential(), plan.createRequestSha256(),
                OpenCodeClient.SessionAttestationKind.LOCAL_REQUEST_ATTESTED);
    }

    private AcceptanceCandidateInternalLaunchCleanupRemoteRow cleanup(
            String remoteId, String state, boolean dispatched, String proof) {
        return new AcceptanceCandidateInternalLaunchCleanupRemoteRow(
                "launch", remoteId, "generation-1", "a".repeat(64), "b".repeat(64), "c".repeat(64),
                "TERMINATION_INTENT", "intent", state, proof, proof == null ? null : "proof-at",
                null, null, null, 1, dispatched, dispatched ? "dispatch" : null,
                null, null, "created", "updated", 0);
    }
}
