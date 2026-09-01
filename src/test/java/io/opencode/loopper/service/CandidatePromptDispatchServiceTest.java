package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.CandidatePromptDispatchKind;
import io.opencode.loopper.domain.CandidatePromptDispatchState;
import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.domain.MachineCandidateOutcome;
import io.opencode.loopper.domain.MachineCandidateRunState;
import io.opencode.loopper.persistence.CandidatePromptDispatchRow;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

class CandidatePromptDispatchServiceTest {
    @Test
    void messageIdentityIsStablePerRejectedAttemptAndChangesAcrossAttempts() {
        String first = CandidatePromptDispatchService.messageId("run-1", 1);

        assertThat(CandidatePromptDispatchService.messageId("run-1", 1)).isEqualTo(first);
        assertThat(CandidatePromptDispatchService.messageId("run-1", 2)).isNotEqualTo(first);
        assertThat(first).startsWith("loopper-candidate-prompt-");
    }

    @Test
    void initialMessageIdentityIsStableAndSeparatedFromEveryCorrection() {
        String initial = CandidatePromptDispatchService.initialMessageId("run-1");

        assertThat(CandidatePromptDispatchService.initialMessageId("run-1")).isEqualTo(initial);
        assertThat(initial).isNotEqualTo(CandidatePromptDispatchService.messageId("run-1", 1));
        assertThat(initial).startsWith("loopper-candidate-prompt-");
    }

    @Test
    void channelLaunchMismatchFailsBeforeBudgetPersistenceOrRemoteIo() {
        CandidatePromptDispatchStore store = mock(CandidatePromptDispatchStore.class);
        CandidatePromptDispatchService service = service(store);
        CorrectionFixture internal = correctionFixture(
                "run-internal", MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP);
        CorrectionFixture legacy = correctionFixture(
                "run-legacy", MachineCandidateSubmission.SubmissionChannel.IN_PROCESS_LEGACY);
        AtomicInteger budgets = new AtomicInteger();
        AtomicInteger lookups = new AtomicInteger();
        AtomicInteger posts = new AtomicInteger();
        CandidatePromptDispatchService.BudgetReservation budget = () -> {
            budgets.incrementAndGet();
            return true;
        };
        CandidatePromptDispatchService.PromptIo io = countingIo(lookups, posts);

        assertThatThrownBy(() -> service.advance(
                internal.run(), internal.rejected(), (String) null, internal.remote(), internal.request(),
                budget, io, "worker", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires internalLaunchId");
        assertThatThrownBy(() -> service.advance(
                internal.run(), internal.rejected(), "  ", internal.remote(), internal.request(),
                budget, io, "worker", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires internalLaunchId");
        assertThatThrownBy(() -> service.advance(
                internal.run(), internal.rejected(), internal.remote(), internal.request(),
                budget, io, "worker", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved for IN_PROCESS_LEGACY");
        assertThatThrownBy(() -> service.advance(
                legacy.run(), legacy.rejected(), "launch-not-allowed", legacy.remote(), legacy.request(),
                budget, io, "worker", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbids internalLaunchId");
        assertThatThrownBy(() -> service.advanceInitial(
                internal.run(), (String) null, internal.remote(), internal.request(),
                budget, io, "worker", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires internalLaunchId");
        assertThatThrownBy(() -> service.advanceInitial(
                internal.run(), internal.remote(), internal.request(),
                budget, io, "worker", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved for IN_PROCESS_LEGACY");
        assertThatThrownBy(() -> service.advanceInitial(
                legacy.run(), "launch-not-allowed", legacy.remote(), legacy.request(),
                budget, io, "worker", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbids internalLaunchId");

        assertThat(budgets).hasValue(0);
        assertThat(lookups).hasValue(0);
        assertThat(posts).hasValue(0);
        verifyNoInteractions(store);
    }

    @Test
    void exactLaunchForInternalAndNullLaunchForLegacyReachPersistence() {
        CandidatePromptDispatchStore store = mock(CandidatePromptDispatchStore.class);
        CandidatePromptDispatchService service = service(store);
        CorrectionFixture internal = correctionFixture(
                "run-internal-ok", MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP);
        CorrectionFixture legacy = correctionFixture(
                "run-legacy-ok", MachineCandidateSubmission.SubmissionChannel.IN_PROCESS_LEGACY);
        when(store.reserve(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            CandidatePromptDispatchStore.Command command = invocation.getArgument(0);
            return new CandidatePromptDispatchStore.Reservation(acknowledged(command), null);
        });
        AtomicInteger budgets = new AtomicInteger();
        AtomicInteger lookups = new AtomicInteger();
        AtomicInteger posts = new AtomicInteger();

        CandidatePromptDispatchService.Result internalResult = service.advance(
                internal.run(), internal.rejected(), "launch-internal-ok", internal.remote(), internal.request(),
                () -> budgets.incrementAndGet() > 0, countingIo(lookups, posts), "worker", Instant.now());
        CandidatePromptDispatchService.Result legacyResult = service.advance(
                legacy.run(), legacy.rejected(), legacy.remote(), legacy.request(),
                () -> budgets.incrementAndGet() > 0, countingIo(lookups, posts), "worker", Instant.now());

        assertThat(internalResult.status()).isEqualTo(CandidatePromptDispatchService.Status.ACKNOWLEDGED);
        assertThat(legacyResult.status()).isEqualTo(CandidatePromptDispatchService.Status.ACKNOWLEDGED);
        verify(store, times(2)).reserve(any(), any(), any(), any(), any());
        assertThat(budgets).hasValue(0);
        assertThat(lookups).hasValue(0);
        assertThat(posts).hasValue(0);
    }

    @Test
    void durablePostBoundaryCannotBeFencedBeforeItsSinglePost() {
        CandidatePromptDispatchStore store = mock(CandidatePromptDispatchStore.class);
        LoopperProperties properties = new LoopperProperties();
        CandidatePromptDispatchCoordinator coordinator = new CandidatePromptDispatchCoordinator(
                store, new CandidatePromptDispatchBarrier(), properties);
        MachineCandidateSubmission.RunSnapshot run = mock(MachineCandidateSubmission.RunSnapshot.class);
        MachineCandidateSubmission.SubmissionResult rejected = mock(
                MachineCandidateSubmission.SubmissionResult.class);
        OpenCodeClient.OpenCodeSession remote = new OpenCodeClient.OpenCodeSession(
                "remote-1", Path.of("/tmp/project"));
        OpenCodeClient.PromptRequest request = new OpenCodeClient.PromptRequest(
                "repair", null, null, new OpenCodeClient.ResponseFormat.Text(),
                CandidatePromptDispatchService.messageId("run-1", 1), List.of());
        CandidatePromptDispatchRow row = new CandidatePromptDispatchRow(
                "dispatch-1", "run-1", "launch-1", CandidatePromptDispatchKind.CORRECTION.name(), 1,
                "remote-1", "generation-1", request.messageId(), "{}", "a".repeat(64),
                CandidatePromptDispatchState.PROMPTING.name(), true, "now",
                "worker", "claim-1", "2099-01-01T00:00:00Z", 1,
                false, null, false, null, null, null, null, null, "now", "now", 0);
        CandidatePromptDispatchStore.Claim claim = new CandidatePromptDispatchStore.Claim(true, "claim-1", 1);
        when(run.runId()).thenReturn("run-1");
        when(run.submissionChannel()).thenReturn(
                MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP);
        when(run.state()).thenReturn(io.opencode.loopper.domain.MachineCandidateRunState.OPEN);
        when(run.attemptsUsed()).thenReturn(1);
        when(run.externalSessionId()).thenReturn("remote-1");
        when(rejected.runId()).thenReturn("run-1");
        when(rejected.attemptOrdinal()).thenReturn(1);
        when(rejected.outcome()).thenReturn(io.opencode.loopper.domain.MachineCandidateOutcome.REJECTED);
        when(rejected.retryable()).thenReturn(true);
        when(store.reserve(any(), any(), any(), any(), any()))
                .thenReturn(new CandidatePromptDispatchStore.Reservation(row, claim));
        when(store.get("dispatch-1")).thenReturn(row);
        when(store.markDispatchStarted("dispatch-1", claim)).thenReturn(true);
        doThrow(new ConflictException("CANDIDATE_PROMPT_DISPATCH_STALE", "cancelled after mark"))
                .when(store).requireClaim("dispatch-1", claim);
        when(store.acknowledge("dispatch-1", claim)).thenReturn(true);
        AtomicInteger lookups = new AtomicInteger();
        AtomicInteger posts = new AtomicInteger();
        CandidatePromptDispatchService.PromptIo io = new CandidatePromptDispatchService.PromptIo() {
            @Override public OpenCodeClient.MessageLookup lookup(OpenCodeClient.OpenCodeSession ignored,
                    OpenCodeClient.PromptRequest expected, String sha256) {
                return lookups.incrementAndGet() == 1
                        ? new OpenCodeClient.MessageLookup(true, false)
                        : new OpenCodeClient.MessageLookup(true, true, "a".repeat(64));
            }
            @Override public void dispatch(OpenCodeClient.OpenCodeSession ignored,
                    OpenCodeClient.PromptRequest expected) { posts.incrementAndGet(); }
        };

        CandidatePromptDispatchService.Result result = coordinator.advanceCorrection(
                run, rejected, "launch-1", remote, request, () -> true, io, "worker", Instant.now());

        assertThat(result.status()).isEqualTo(CandidatePromptDispatchService.Status.ACKNOWLEDGED);
        assertThat(posts).hasValue(1);
    }

    private static CandidatePromptDispatchService service(CandidatePromptDispatchStore store) {
        CandidatePromptDispatchCoordinator coordinator = new CandidatePromptDispatchCoordinator(
                store, new CandidatePromptDispatchBarrier(), new LoopperProperties());
        return new CandidatePromptDispatchService(coordinator, mock(PlatformTransactionManager.class));
    }

    private static CorrectionFixture correctionFixture(
            String runId, MachineCandidateSubmission.SubmissionChannel channel) {
        MachineCandidateSubmission.RunSnapshot run = new MachineCandidateSubmission.RunSnapshot(
                runId, MachineCandidateSubmission.CandidateScope.designerSession("session-1"),
                MachineCandidateSubmission.CandidateOwnerRef.loopSpecCompilation("compilation-1"),
                MachineCandidateKind.ACCEPTANCE_CLOSED_CHOICE_V7,
                AcceptanceClosedChoiceCandidateCoordinator.WORKFLOW_STEP, 1, 5, channel,
                AcceptanceClosedChoiceCandidateCoordinator.CONTRACT_VERSION,
                "generation-1", "remote-1", MachineCandidateRunState.OPEN, 2, 1, null, 0);
        MachineCandidateSubmission.SubmissionResult rejected = new MachineCandidateSubmission.SubmissionResult(
                runId, MachineCandidateOutcome.REJECTED, MachineCandidateRunState.OPEN,
                1, 1, true, List.of(), null, 1, "{}");
        OpenCodeClient.OpenCodeSession remote = new OpenCodeClient.OpenCodeSession(
                "remote-1", Path.of("/tmp/project"));
        OpenCodeClient.PromptRequest request = new OpenCodeClient.PromptRequest(
                "repair", null, null, new OpenCodeClient.ResponseFormat.Text(),
                CandidatePromptDispatchService.messageId(runId, 1), List.of());
        return new CorrectionFixture(run, rejected, remote, request);
    }

    private static CandidatePromptDispatchService.PromptIo countingIo(
            AtomicInteger lookups, AtomicInteger posts) {
        return new CandidatePromptDispatchService.PromptIo() {
            @Override public OpenCodeClient.MessageLookup lookup(OpenCodeClient.OpenCodeSession remote,
                    OpenCodeClient.PromptRequest request, String requestSha256) {
                lookups.incrementAndGet();
                return new OpenCodeClient.MessageLookup(true, false);
            }
            @Override public void dispatch(OpenCodeClient.OpenCodeSession remote,
                    OpenCodeClient.PromptRequest request) {
                posts.incrementAndGet();
            }
        };
    }

    private static CandidatePromptDispatchRow acknowledged(CandidatePromptDispatchStore.Command command) {
        return new CandidatePromptDispatchRow(
                "dispatch-" + command.run().runId(), command.run().runId(), command.internalLaunchId(),
                command.kind().name(), command.sourceAttemptOrdinal(), command.run().externalSessionId(),
                command.run().runtimeGenerationId(), command.request().messageId(), "{}", "a".repeat(64),
                CandidatePromptDispatchState.ACKNOWLEDGED.name(), true, "now",
                null, null, null, 1, true, "now", true, "now",
                null, null, null, null, "now", "now", 0);
    }

    private record CorrectionFixture(
            MachineCandidateSubmission.RunSnapshot run,
            MachineCandidateSubmission.SubmissionResult rejected,
            OpenCodeClient.OpenCodeSession remote,
            OpenCodeClient.PromptRequest request) { }
}
