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

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.persistence.AcceptanceCandidateHandoffCleanupRemoteRow;
import io.opencode.loopper.persistence.AcceptanceCandidateLegacyHandoffRow;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import tools.jackson.databind.ObjectMapper;

class AcceptanceCandidateLegacyHandoffCancellationTest {
    @Test
    void zeroExactMatchesAfterCreateCheckpointCannotCompleteDesignerCancellation() {
        Fixture fixture = fixture();
        when(fixture.openCode.findSessionsByExactTitle(fixture.plan))
                .thenReturn(new OpenCodeClient.SessionLookup(true, List.of()));

        var result = fixture.coordinator.reconcileDesignerCancellation("designer-1");

        assertThat(result.ready()).isFalse();
        assertThat(result.stoppedSessions()).isZero();
        verify(fixture.handoffs).recordFailure("handoff-1", "LEGACY_CREATE",
                "OPENCODE_SESSION_CREATION_RESULT_UNKNOWN",
                "Designer 已停止，但创建请求结果尚未精确回查到远端");
        verify(fixture.handoffs).fenceCreate("handoff-1");
        verify(fixture.candidates, never()).stopUnopened(any());
        verify(fixture.openCode, never()).createSession(any());
        verify(fixture.handoffs, never()).promptBudgetAvailable(anyString(), anyString());
    }

    @Test
    void singletonFullAttestationIsPersistedBeforeAbortAndPositiveProofCompletesCancellation() {
        Fixture fixture = fixture();
        OpenCodeClient.SessionAttestation attestation = attestation(fixture.plan, "legacy-1");
        AcceptanceCandidateLegacyHandoffRow persisted = row("legacy-1");
        when(fixture.openCode.findSessionsByExactTitle(fixture.plan))
                .thenReturn(new OpenCodeClient.SessionLookup(true, List.of(attestation)));
        when(fixture.handoffs.recordLegacyCreated("handoff-1", attestation, fixture.claim))
                .thenReturn(persisted);
        when(fixture.candidates.stopUnopened(any())).thenReturn(new DesignerAcceptanceCandidateOrchestrator.StopResult(
                true, "ABORT_ACKNOWLEDGED", null, null));

        var result = fixture.coordinator.reconcileDesignerCancellation("designer-1");

        assertThat(result.ready()).isTrue();
        assertThat(result.stoppedSessions()).isEqualTo(1);
        assertThat(result.proofs()).containsEntry("legacy-1", "ABORT_ACKNOWLEDGED");
        InOrder ordered = inOrder(fixture.handoffs, fixture.candidates);
        ordered.verify(fixture.handoffs).validateCancellationMatch("handoff-1", attestation, fixture.claim);
        ordered.verify(fixture.candidates).bindLegacy(attestation);
        ordered.verify(fixture.handoffs).recordLegacyCreated("handoff-1", attestation, fixture.claim);
        ordered.verify(fixture.handoffs).requireCancellationCreateClaim("handoff-1", fixture.claim);
        ordered.verify(fixture.candidates).stopUnopened(any());
        ordered.verify(fixture.handoffs).completeLegacyCleanup("handoff-1", "ABORT_ACKNOWLEDGED");
        verify(fixture.openCode, never()).createSession(any());
    }

    @Test
    void ambiguityRegistersEveryIdentityBeforeAbortingAndAnyUnconfirmedRemoteKeepsStopping() {
        Fixture fixture = fixture();
        OpenCodeClient.SessionAttestation first = attestation(fixture.plan, "legacy-1");
        OpenCodeClient.SessionAttestation second = attestation(fixture.plan, "legacy-2");
        AcceptanceCandidateHandoffCleanupRemoteRow firstCleanup = cleanup("legacy-1");
        AcceptanceCandidateHandoffCleanupRemoteRow secondCleanup = cleanup("legacy-2");
        AtomicInteger listCalls = new AtomicInteger();
        when(fixture.openCode.findSessionsByExactTitle(fixture.plan))
                .thenReturn(new OpenCodeClient.SessionLookup(true, List.of(first, second)));
        when(fixture.handoffs.cleanupRemotes("handoff-1")).thenAnswer(ignored ->
                listCalls.incrementAndGet() == 1 ? List.of() : List.of(firstCleanup, secondCleanup));
        when(fixture.candidates.stopUnopened(any())).thenAnswer(invocation -> {
            OpenCodeClient.OpenCodeSession remote = invocation.getArgument(0);
            return remote.id().equals("legacy-1")
                    ? new DesignerAcceptanceCandidateOrchestrator.StopResult(
                            false, null, "STOP_UNCERTAIN", "abort result unknown")
                    : new DesignerAcceptanceCandidateOrchestrator.StopResult(
                            true, "ABORT_ACKNOWLEDGED", null, null);
        });

        var result = fixture.coordinator.reconcileDesignerCancellation("designer-1");

        assertThat(result.ready()).isFalse();
        assertThat(result.stoppedSessions()).isEqualTo(1);
        assertThat(result.failedSessions()).isEqualTo(1);
        InOrder ordered = inOrder(fixture.handoffs, fixture.cleanupLedger, fixture.candidates);
        ordered.verify(fixture.handoffs).registerAmbiguity(anyString(), any());
        ordered.verify(fixture.cleanupLedger).claimStop(
                org.mockito.ArgumentMatchers.eq("handoff-1"), org.mockito.ArgumentMatchers.eq("legacy-1"),
                anyString(), any(Instant.class), any());
        ordered.verify(fixture.candidates).stopUnopened(any());
        ordered.verify(fixture.cleanupLedger).claimStop(
                org.mockito.ArgumentMatchers.eq("handoff-1"), org.mockito.ArgumentMatchers.eq("legacy-2"),
                anyString(), any(Instant.class), any());
        ordered.verify(fixture.candidates).stopUnopened(any());
        verify(fixture.cleanupLedger).disconnected(
                "handoff-1", "legacy-1", fixture.stopClaim,
                "OPENCODE_ACCEPTANCE_LEGACY_STOP_UNCONFIRMED",
                "abort result unknown");
        verify(fixture.cleanupLedger).stopped(
                "handoff-1", "legacy-2", fixture.stopClaim, "ABORT_ACKNOWLEDGED");
        verify(fixture.handoffs, never()).completeRecoveredUnknownCancellation("handoff-1");
    }

    @Test
    void concurrentCancellationClaimAllowsOnlyOneExactLookupAndAbort() throws Exception {
        Fixture fixture = fixture();
        CountDownLatch lookupEntered = new CountDownLatch(1);
        CountDownLatch releaseLookup = new CountDownLatch(1);
        AtomicInteger claims = new AtomicInteger();
        when(fixture.handoffs.claimCreate(anyString(), anyString(), any(Instant.class), any()))
                .thenAnswer(ignored -> claims.getAndIncrement() == 0
                        ? fixture.claim : AcceptanceCandidateLegacyHandoffService.Claim.unavailable(7, "CLAIMED"));
        OpenCodeClient.SessionAttestation attestation = attestation(fixture.plan, "legacy-1");
        when(fixture.openCode.findSessionsByExactTitle(fixture.plan)).thenAnswer(ignored -> {
            lookupEntered.countDown();
            assertThat(releaseLookup.await(5, TimeUnit.SECONDS)).isTrue();
            return new OpenCodeClient.SessionLookup(true, List.of(attestation));
        });
        AcceptanceCandidateLegacyHandoffRow persisted = row("legacy-1");
        when(fixture.handoffs.recordLegacyCreated("handoff-1", attestation, fixture.claim))
                .thenReturn(persisted);
        when(fixture.candidates.stopUnopened(any())).thenReturn(new DesignerAcceptanceCandidateOrchestrator.StopResult(
                true, "ABORT_ACKNOWLEDGED", null, null));

        CompletableFuture<?> first = CompletableFuture.runAsync(
                () -> fixture.coordinator.reconcileDesignerCancellation("designer-1"));
        assertThat(lookupEntered.await(5, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<?> second = CompletableFuture.runAsync(
                () -> fixture.coordinator.reconcileDesignerCancellation("designer-1"));
        second.get(5, TimeUnit.SECONDS);
        releaseLookup.countDown();
        first.get(5, TimeUnit.SECONDS);

        verify(fixture.openCode, times(1)).findSessionsByExactTitle(fixture.plan);
        verify(fixture.candidates, times(1)).stopUnopened(any());
        verify(fixture.openCode, never()).createSession(any());
    }

    private static Fixture fixture() {
        AcceptanceCandidateLegacyHandoffService handoffs = mock(AcceptanceCandidateLegacyHandoffService.class);
        AcceptanceCandidateHandoffCleanupLedger cleanupLedger = mock(AcceptanceCandidateHandoffCleanupLedger.class);
        DesignerAcceptanceCandidateOrchestrator candidates = mock(DesignerAcceptanceCandidateOrchestrator.class);
        OpenCodeClient openCode = mock(OpenCodeClient.class);
        OpenCodeClient.SessionCreationPlan plan = plan();
        AcceptanceCandidateLegacyHandoffRow row = row(null);
        AcceptanceCandidateLegacyHandoffService.Claim claim =
                new AcceptanceCandidateLegacyHandoffService.Claim(true, "claim-token", 7,
                        Instant.now().plusSeconds(30).toString());
        when(handoffs.activeForDesigner("designer-1")).thenReturn(List.of(row));
        when(handoffs.creationPlan(row)).thenReturn(plan);
        when(handoffs.cleanupRemotes("handoff-1")).thenReturn(List.of());
        when(handoffs.claimCreate(anyString(), anyString(), any(Instant.class), any())).thenReturn(claim);
        AcceptanceCandidateHandoffCleanupLedger.StopClaim stopClaim =
                new AcceptanceCandidateHandoffCleanupLedger.StopClaim(
                        true, "cleanup-worker", "cleanup-token", 3,
                        Instant.now().plusSeconds(30).toString(), null);
        when(cleanupLedger.claimStop(anyString(), anyString(), anyString(), any(Instant.class), any()))
                .thenReturn(stopClaim);
        AcceptanceCandidateLegacyHandoffCoordinator coordinator = new AcceptanceCandidateLegacyHandoffCoordinator(
                handoffs, cleanupLedger, candidates, openCode, mock(DesignerAttachmentContext.class), new ObjectMapper(),
                mock(ProjectService.class), new LoopperProperties());
        return new Fixture(handoffs, cleanupLedger, candidates, openCode, coordinator, plan, claim, stopClaim);
    }

    private static AcceptanceCandidateLegacyHandoffRow row(String legacyId) {
        AcceptanceCandidateLegacyHandoffRow row = mock(AcceptanceCandidateLegacyHandoffRow.class);
        when(row.id()).thenReturn("handoff-1");
        when(row.designerSessionId()).thenReturn("designer-1");
        when(row.state()).thenReturn("STOPPING_LEGACY");
        when(row.createDispatchAttempted()).thenReturn(true);
        when(row.legacyExternalSessionId()).thenReturn(legacyId);
        when(row.successorCanonicalDirectory()).thenReturn("/tmp/p");
        when(row.successorManaged()).thenReturn(true);
        when(row.successorRuntimeGenerationId()).thenReturn("generation-1");
        when(row.successorInternalMcpServer()).thenReturn("mcp-1");
        return row;
    }

    private static AcceptanceCandidateHandoffCleanupRemoteRow cleanup(String remoteId) {
        return new AcceptanceCandidateHandoffCleanupRemoteRow(
                "handoff-1", remoteId, "generation-1", "b".repeat(64), "c".repeat(64), "d".repeat(64),
                "DISCOVERED", null, null, null, null, null, 0, null, null, "now", "now", 0);
    }

    private static OpenCodeClient.SessionCreationPlan plan() {
        Path directory = Path.of("/tmp/p");
        String credential = "c".repeat(43);
        String title = "legacy [loopper-create:" + credential + "]";
        String endpoint = "b".repeat(64);
        String policyDigest = OpenCodeClient.permissionPolicyDigest(List.of());
        String requestDigest = OpenCodeClient.sessionCreationRequestSha256(directory, title, "generation-1", true,
                "mcp-1", endpoint, null, OpenCodeClient.SessionProfile.COMPILER_BINDING_NO_TOOLS,
                policyDigest, credential);
        return new OpenCodeClient.SessionCreationPlan(directory, title, "generation-1", true, "mcp-1", endpoint,
                null, OpenCodeClient.SessionProfile.COMPILER_BINDING_NO_TOOLS, List.of(), policyDigest,
                credential, requestDigest);
    }

    private static OpenCodeClient.SessionAttestation attestation(
            OpenCodeClient.SessionCreationPlan plan, String remoteId) {
        return new OpenCodeClient.SessionAttestation(remoteId, plan.canonicalDirectory(), plan.exactTitle(),
                plan.runtimeGenerationId(), plan.managed(), plan.internalMcpServer(), plan.endpointFingerprint(),
                plan.model(), plan.profile(), plan.permissionPolicy(), plan.permissionPolicyDigest(),
                plan.creationCredential(), plan.createRequestSha256(),
                OpenCodeClient.SessionAttestationKind.LOCAL_REQUEST_ATTESTED);
    }

    private record Fixture(AcceptanceCandidateLegacyHandoffService handoffs,
                           AcceptanceCandidateHandoffCleanupLedger cleanupLedger,
                           DesignerAcceptanceCandidateOrchestrator candidates,
                           OpenCodeClient openCode,
                           AcceptanceCandidateLegacyHandoffCoordinator coordinator,
                           OpenCodeClient.SessionCreationPlan plan,
                           AcceptanceCandidateLegacyHandoffService.Claim claim,
                           AcceptanceCandidateHandoffCleanupLedger.StopClaim stopClaim) { }
}
