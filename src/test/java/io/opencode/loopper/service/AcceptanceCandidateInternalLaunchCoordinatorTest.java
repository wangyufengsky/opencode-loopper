package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;

class AcceptanceCandidateInternalLaunchCoordinatorTest {
    @TempDir static Path workspace;
    private AcceptanceCandidateInternalLaunchService launches;
    private AcceptanceCandidateInternalLaunchCleanupLedger cleanup;
    private AcceptanceCandidateInternalLaunchSettlementService settlements;
    private CandidateRuntimeBindingService bindings;
    private OpenCodeClient openCode;
    private AcceptanceCandidateInternalLaunchCoordinator coordinator;
    private OpenCodeClient.SessionCreationPlan plan;

    @BeforeEach
    void setUp() {
        launches = mock(AcceptanceCandidateInternalLaunchService.class);
        cleanup = mock(AcceptanceCandidateInternalLaunchCleanupLedger.class);
        settlements = mock(AcceptanceCandidateInternalLaunchSettlementService.class);
        bindings = mock(CandidateRuntimeBindingService.class);
        openCode = mock(OpenCodeClient.class);
        coordinator = new AcceptanceCandidateInternalLaunchCoordinator(
                launches, cleanup, settlements, bindings, openCode, Duration.ofMinutes(2));
        plan = plan();
        when(launches.plan(any())).thenReturn(plan);
        when(launches.cleanup(anyString())).thenReturn(List.of());
    }

    @Test
    void readinessPrecedesLookupAndOnlyUnsupportedPreCheckpointFallsBack() {
        AcceptanceCandidateInternalLaunchRow prepared = row("PREPARED", false, null, false);
        AcceptanceCandidateInternalLaunchRow claimed = row("PREPARED", false, null, true);
        var claim = claim(1);
        when(launches.requireForCompilation("cmp")).thenReturn(prepared);
        when(launches.claimCreate(anyString(), anyString(), any(), any())).thenReturn(claim);
        when(launches.require("launch")).thenReturn(claimed);
        when(openCode.findSessionsByExactTitle(plan)).thenReturn(new OpenCodeClient.SessionLookup(false, List.of()));
        AcceptanceCandidateInternalLaunchRow stale = row("STALE", false, null, false);
        when(launches.mechanicalLegacyFallback("launch", claim)).thenReturn(stale);

        var result = coordinator.advance("cmp");

        assertThat(result.status()).isEqualTo(
                AcceptanceCandidateInternalLaunchCoordinator.Status.LEGACY_FALLBACK);
        assertThat(result.launch().state()).isEqualTo("STALE");
        InOrder order = inOrder(openCode);
        order.verify(openCode).requireCandidateSessionReady(plan);
        order.verify(openCode).findSessionsByExactTitle(plan);
        verify(openCode, never()).createSession(any(OpenCodeClient.SessionCreationPlan.class));
        verify(launches).mechanicalLegacyFallback("launch", claim);
    }

    @Test
    void readinessOrAttestationDriftNeverFallsBackOrCreates() {
        AcceptanceCandidateInternalLaunchRow prepared = row("PREPARED", false, null, false);
        AcceptanceCandidateInternalLaunchRow claimed = row("PREPARED", false, null, true);
        when(launches.requireForCompilation("cmp")).thenReturn(prepared);
        when(launches.claimCreate(anyString(), anyString(), any(), any())).thenReturn(claim(1));
        when(launches.require("launch")).thenReturn(claimed);
        when(launches.releaseCreateClaim(anyString(), any(), anyString(), anyString(), anyString()))
                .thenReturn(prepared);
        org.mockito.Mockito.doThrow(new ConflictException("RUNTIME_IDENTITY_DRIFT", "drift"))
                .when(openCode).requireCandidateSessionReady(plan);

        var result = coordinator.advance("cmp");

        assertThat(result.status()).isEqualTo(AcceptanceCandidateInternalLaunchCoordinator.Status.BLOCKED);
        assertThat(result.code()).isEqualTo("RUNTIME_IDENTITY_DRIFT");
        verify(openCode, never()).findSessionsByExactTitle(any(OpenCodeClient.SessionCreationPlan.class));
        verify(openCode, never()).createSession(any(OpenCodeClient.SessionCreationPlan.class));
    }

    @Test
    void uncertainCreatePostIsNeverResentAndZeroRecoveryStaysDisconnected() {
        AcceptanceCandidateInternalLaunchRow prepared = row("PREPARED", false, null, false);
        AcceptanceCandidateInternalLaunchRow claimedPrepared = row("PREPARED", false, null, true);
        AcceptanceCandidateInternalLaunchRow creating = row("CREATING", true, null, true);
        AcceptanceCandidateInternalLaunchRow disconnected = row("DISCONNECTED", true, null, false);
        AcceptanceCandidateInternalLaunchRow claimedDisconnected = row("DISCONNECTED", true, null, true);
        when(launches.requireForCompilation("cmp")).thenReturn(prepared, disconnected);
        when(launches.claimCreate(anyString(), anyString(), any(), any()))
                .thenReturn(claim(1), claim(2));
        when(launches.require("launch")).thenReturn(claimedPrepared, claimedDisconnected);
        when(openCode.findSessionsByExactTitle(plan))
                .thenReturn(new OpenCodeClient.SessionLookup(true, List.of()));
        when(launches.markCreateDispatchStarted("launch", claim(1)))
                .thenReturn(new AcceptanceCandidateInternalLaunchService.DispatchCheckpoint(creating, true));
        when(openCode.createSession(plan)).thenThrow(new RuntimeException("socket reset after POST"));
        when(launches.disconnected(anyString(), any(), anyString(), anyString(), any()))
                .thenReturn(disconnected);

        assertThat(coordinator.advance("cmp").status())
                .isEqualTo(AcceptanceCandidateInternalLaunchCoordinator.Status.PENDING);
        assertThat(coordinator.advance("cmp").status())
                .isEqualTo(AcceptanceCandidateInternalLaunchCoordinator.Status.PENDING);
        verify(openCode, times(1)).createSession(plan);
        verify(openCode, times(2)).findSessionsByExactTitle(plan);
    }

    @Test
    void oneStrictMatchIsAdoptedWithoutCreateAndSettledFromFrozenRunId() {
        AcceptanceCandidateInternalLaunchRow prepared = row("PREPARED", false, null, false);
        AcceptanceCandidateInternalLaunchRow claimed = row("PREPARED", false, null, true);
        AcceptanceCandidateInternalLaunchRow creating = row("CREATING", true, null, true);
        AcceptanceCandidateInternalLaunchRow created = row("CREATED", true, "remote-1", true);
        AcceptanceCandidateInternalLaunchRow settled = row("SETTLED", true, "remote-1", false);
        OpenCodeClient.SessionAttestation attestation = attestation("remote-1", plan);
        var claim = claim(1);
        var run = run("remote-1");
        when(launches.requireForCompilation("cmp")).thenReturn(prepared);
        when(launches.claimCreate(anyString(), anyString(), any(), any())).thenReturn(claim);
        when(launches.require("launch")).thenReturn(claimed, creating, created);
        when(openCode.findSessionsByExactTitle(plan))
                .thenReturn(new OpenCodeClient.SessionLookup(true, List.of(attestation)));
        when(launches.markCreateDispatchStarted("launch", claim))
                .thenReturn(new AcceptanceCandidateInternalLaunchService.DispatchCheckpoint(creating, true));
        when(launches.created("launch", claim, attestation)).thenReturn(created);
        when(settlements.settle("launch"))
                .thenReturn(new AcceptanceCandidateInternalLaunchSettlementService.Settlement(settled, run));

        var result = coordinator.advance("cmp");

        assertThat(result.status()).isEqualTo(AcceptanceCandidateInternalLaunchCoordinator.Status.SETTLED);
        assertThat(result.run().runId()).isEqualTo("run");
        verify(bindings).bindInternalAttested(attestation, plan);
        verify(openCode, never()).createSession(any(OpenCodeClient.SessionCreationPlan.class));
        verify(openCode, never()).promptAsync(any(), anyString());
    }

    @Test
    void bindingFailureRegistersTheAttestedRemoteForCleanup() {
        AcceptanceCandidateInternalLaunchRow prepared = row("PREPARED", false, null, false);
        AcceptanceCandidateInternalLaunchRow claimed = row("PREPARED", false, null, true);
        AcceptanceCandidateInternalLaunchRow creating = row("CREATING", true, null, true);
        AcceptanceCandidateInternalLaunchRow stopping = row("STOPPING", true, null, false);
        AcceptanceCandidateInternalLaunchRow failed = row("FAILED_STOPPED", true, null, false);
        OpenCodeClient.SessionAttestation attestation = attestation("remote-1", plan);
        var claim = claim(1);
        var stopped = List.of(cleanup("remote-1", "STOPPED", true));
        when(launches.requireForCompilation("cmp")).thenReturn(prepared);
        when(launches.claimCreate(anyString(), anyString(), any(), any())).thenReturn(claim);
        when(launches.require("launch")).thenReturn(claimed, creating, stopping, stopping);
        when(openCode.findSessionsByExactTitle(plan))
                .thenReturn(new OpenCodeClient.SessionLookup(true, List.of(attestation)));
        when(launches.markCreateDispatchStarted("launch", claim))
                .thenReturn(new AcceptanceCandidateInternalLaunchService.DispatchCheckpoint(creating, true));
        org.mockito.Mockito.doThrow(new ConflictException("CANDIDATE_RUNTIME_GENERATION_STALE", "drift"))
                .when(bindings).bindInternalAttested(attestation, plan);
        when(launches.registerCleanup("launch", claim, List.of(attestation),
                "OWNER_REVALIDATION", "CANDIDATE_RUNTIME_GENERATION_STALE", "drift"))
                .thenReturn(stopped);
        when(cleanup.list("launch")).thenReturn(stopped);
        when(launches.finishAfterCleanup("launch")).thenReturn(failed);

        var result = coordinator.advance("cmp");

        assertThat(result.status()).isEqualTo(
                AcceptanceCandidateInternalLaunchCoordinator.Status.FAILED_STOPPED);
        verify(launches).registerCleanup("launch", claim, List.of(attestation),
                "OWNER_REVALIDATION", "CANDIDATE_RUNTIME_GENERATION_STALE", "drift");
        verify(launches, never()).created(anyString(), any(), any());
    }

    @Test
    void multipleMatchesAreAllRegisteredAndNeverLaterAdoptedAsSingleton() {
        AcceptanceCandidateInternalLaunchRow prepared = row("PREPARED", false, null, false);
        AcceptanceCandidateInternalLaunchRow claimed = row("PREPARED", false, null, true);
        AcceptanceCandidateInternalLaunchRow stopping = row("STOPPING", false, null, false);
        AcceptanceCandidateInternalLaunchRow failed = row("FAILED_STOPPED", false, null, false);
        var first = attestation("remote-1", plan);
        var second = attestation("remote-2", plan);
        List<AcceptanceCandidateInternalLaunchCleanupRemoteRow> stopped = List.of(
                cleanup("remote-1", "STOPPED", true), cleanup("remote-2", "STOPPED", true));
        when(launches.requireForCompilation("cmp")).thenReturn(prepared, stopping);
        when(launches.claimCreate(anyString(), anyString(), any(), any())).thenReturn(claim(1));
        when(launches.require("launch")).thenReturn(claimed, stopping, stopping);
        when(openCode.findSessionsByExactTitle(plan))
                .thenReturn(new OpenCodeClient.SessionLookup(true, List.of(first, second)),
                        new OpenCodeClient.SessionLookup(true, List.of(first)));
        when(launches.registerAmbiguity("launch", claim(1), List.of(first, second))).thenReturn(stopped);
        when(cleanup.list("launch")).thenReturn(stopped);
        when(launches.finishAfterCleanup("launch")).thenReturn(failed);

        assertThat(coordinator.advance("cmp").status())
                .isEqualTo(AcceptanceCandidateInternalLaunchCoordinator.Status.FAILED_STOPPED);
        assertThat(coordinator.advance("cmp").status())
                .isEqualTo(AcceptanceCandidateInternalLaunchCoordinator.Status.FAILED_STOPPED);
        verify(openCode, times(1)).findSessionsByExactTitle(plan);
        verify(bindings, times(2)).bindInternalAttested(any(), org.mockito.Mockito.eq(plan));
        verify(launches, never()).created(anyString(), any(), any());
    }

    @Test
    void cleanupStopPostIsAtMostOnceAndUnknownResultRemainsDisconnected() {
        AcceptanceCandidateInternalLaunchRow stopping = row("STOPPING", true, null, false);
        var discovered = cleanup("remote-1", "DISCOVERED", false);
        var stoppingFresh = cleanup("remote-1", "STOPPING", false);
        var disconnected = cleanup("remote-1", "DISCONNECTED", true);
        var stoppingCheckpointed = cleanup("remote-1", "STOPPING", true);
        when(launches.requireForCompilation("cmp")).thenReturn(stopping);
        when(cleanup.list("launch")).thenReturn(
                List.of(discovered), List.of(stoppingFresh),
                List.of(disconnected), List.of(stoppingCheckpointed));
        when(cleanup.claimStop(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(stopClaim(1), stopClaim(2));
        when(cleanup.markStopDispatchStarted("launch", "remote-1", stopClaim(1)))
                .thenReturn(new AcceptanceCandidateInternalLaunchCleanupLedger.StopCheckpoint(
                        stoppingCheckpointed, true));
        when(launches.require("launch")).thenReturn(stopping);
        when(openCode.abortWithConfirmation(any())).thenThrow(new RuntimeException("unknown abort result"));
        when(openCode.sessionStatus(any())).thenReturn(new OpenCodeClient.SessionStatus("RUNNING"));

        assertThat(coordinator.advance("cmp").status())
                .isEqualTo(AcceptanceCandidateInternalLaunchCoordinator.Status.CLEANUP_PENDING);
        assertThat(coordinator.advance("cmp").status())
                .isEqualTo(AcceptanceCandidateInternalLaunchCoordinator.Status.CLEANUP_PENDING);
        verify(openCode, times(1)).abortWithConfirmation(any());
        verify(openCode, times(1)).sessionStatus(any());
        verify(cleanup, times(2)).disconnected(anyString(), anyString(), any(), anyString(), any());
    }

    private OpenCodeClient.SessionCreationPlan plan() {
        Path directory = workspace.toAbsolutePath().normalize();
        String credential = "A".repeat(43);
        String title = OpenCodeClient.recoveryTitle("Acceptance internal", credential);
        List<OpenCodeClient.SessionPermissionRule> policy = List.of(
                new OpenCodeClient.SessionPermissionRule("*", "*", "deny"),
                new OpenCodeClient.SessionPermissionRule("external_directory", "*", "deny"),
                new OpenCodeClient.SessionPermissionRule(
                        "loopper_internal_submit_candidate", "*", "allow"));
        String policyDigest = OpenCodeClient.permissionPolicyDigest(policy);
        String request = OpenCodeClient.sessionCreationRequestSha256(
                directory, title, "generation-1", true, "loopper_internal", "a".repeat(64),
                null, OpenCodeClient.SessionProfile.ACCEPTANCE_CLOSED_CHOICE_CANDIDATE_NO_TOOLS,
                policyDigest, credential);
        return new OpenCodeClient.SessionCreationPlan(
                directory, title, "generation-1", true, "loopper_internal", "a".repeat(64),
                null, OpenCodeClient.SessionProfile.ACCEPTANCE_CLOSED_CHOICE_CANDIDATE_NO_TOOLS,
                policy, policyDigest, credential, request);
    }

    private OpenCodeClient.SessionAttestation attestation(
            String remoteId, OpenCodeClient.SessionCreationPlan frozen) {
        return new OpenCodeClient.SessionAttestation(
                remoteId, frozen.canonicalDirectory(), frozen.exactTitle(), frozen.runtimeGenerationId(),
                frozen.managed(), frozen.internalMcpServer(), frozen.endpointFingerprint(), frozen.model(),
                frozen.profile(), frozen.permissionPolicy(), frozen.permissionPolicyDigest(),
                frozen.creationCredential(), frozen.createRequestSha256(),
                OpenCodeClient.SessionAttestationKind.LOCAL_REQUEST_ATTESTED);
    }

    private AcceptanceCandidateInternalLaunchRow row(
            String state, boolean dispatched, String remoteId, boolean claimed) {
        return new AcceptanceCandidateInternalLaunchRow(
                "launch", "cmp", "designer", "WP-1", 1, "message", 0, "d".repeat(64),
                3, "AI_DISAMBIGUATION_V6", "{}", "e".repeat(64), "{}", "f".repeat(64),
                "run", "ACCEPTANCE_CLOSED_CHOICE_V7", "ACCEPTANCE_CLOSED_CHOICE_V7", state,
                4, "SETTLED".equals(state) ? 5L : null, "SETTLED".equals(state) ? "now" : null,
                plan.exactTitle(), plan.canonicalDirectory().toString(), plan.runtimeGenerationId(), true,
                plan.internalMcpServer(), plan.endpointFingerprint(), null, null, null, plan.profile().name(),
                "[]", plan.permissionPolicyDigest(), plan.createRequestSha256(), plan.creationCredential(),
                "LOCAL_REQUEST_ATTESTED", claimed ? "worker" : null, claimed ? "token" : null,
                claimed ? "2099-01-01T00:00:00Z" : null, claimed ? 1 : 0, dispatched,
                dispatched ? "dispatch" : null, remoteId, remoteId == null ? null : "attested",
                null, null, null, null, null, "created", "updated", 1);
    }

    private AcceptanceCandidateInternalLaunchCleanupRemoteRow cleanup(
            String remoteId, String state, boolean dispatched) {
        boolean stopping = "STOPPING".equals(state);
        boolean stopped = "STOPPED".equals(state);
        return new AcceptanceCandidateInternalLaunchCleanupRemoteRow(
                "launch", remoteId, "generation-1", "a".repeat(64), "b".repeat(64), "c".repeat(64),
                "LAUNCH_AMBIGUITY", null,
                state, stopped ? "ABORT_ACKNOWLEDGED" : null, stopped ? "proof" : null,
                stopping ? "stopper" : null, stopping ? "stop-token" : null,
                stopping ? "2099-01-01T00:00:00Z" : null, stopping ? 1 : 0,
                dispatched, dispatched ? "stop-dispatch" : null, null, null, "created", "updated", 1);
    }

    private AcceptanceCandidateInternalLaunchService.CreateClaim claim(long fence) {
        return new AcceptanceCandidateInternalLaunchService.CreateClaim(
                true, "worker", "token", fence, "2099-01-01T00:00:00Z", null);
    }

    private AcceptanceCandidateInternalLaunchCleanupLedger.StopClaim stopClaim(long fence) {
        return new AcceptanceCandidateInternalLaunchCleanupLedger.StopClaim(
                true, "stopper", "stop-token", fence, "2099-01-01T00:00:00Z", null);
    }

    private MachineCandidateSubmission.RunSnapshot run(String remoteId) {
        return new MachineCandidateSubmission.RunSnapshot(
                "run", MachineCandidateSubmission.CandidateScope.designerSession("designer"),
                MachineCandidateSubmission.CandidateOwnerRef.loopSpecCompilation("cmp"),
                MachineCandidateKind.ACCEPTANCE_CLOSED_CHOICE_V7, "ACCEPTANCE_CLOSED_CHOICE_V7", 1, 5,
                MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP,
                "ACCEPTANCE_CLOSED_CHOICE_V7", "generation-1", remoteId,
                MachineCandidateRunState.OPEN, 2, 0, null, 0);
    }
}
