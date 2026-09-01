package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.domain.AcceptanceCandidateInternalParentAction;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.AcceptanceCandidateInternalLaunchRow;
import io.opencode.loopper.persistence.AcceptanceCandidateInternalTerminationIntentRow;
import io.opencode.loopper.persistence.LoopperAcceptanceCandidateTerminationMapper;
import java.util.List;
import java.util.Optional;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.Test;

class AcceptanceCandidateInternalTerminationIntentStoreTest {
    private final LoopperAcceptanceCandidateTerminationMapper mapper =
            mock(LoopperAcceptanceCandidateTerminationMapper.class);
    private final LifecycleTransitionService lifecycle = mock(LifecycleTransitionService.class);
    private final AcceptanceCandidateInternalTerminationIntentStore store =
            new AcceptanceCandidateInternalTerminationIntentStore(mapper, lifecycle);

    @Test
    void exposesDurableRecoveryAndOwnershipQueriesWithoutReconstructingIntent() {
        AcceptanceCandidateInternalTerminationIntentRow ready = intent("READY", 7, true);
        AcceptanceCandidateInternalLaunchRow launch = mock(AcceptanceCandidateInternalLaunchRow.class);
        when(mapper.listRecoverableAcceptanceCandidateInternalTerminationIntents()).thenReturn(List.of(ready));
        when(mapper.existsActiveAcceptanceCandidateInternalTerminationIntentForDesigner("designer"))
                .thenReturn(true);
        when(mapper.listTerminableAcceptanceCandidateInternalLaunchesForDesigner("designer"))
                .thenReturn(List.of(launch));
        when(mapper.existsAcceptanceCandidateInternalTrackedExternalSession("remote")).thenReturn(true);

        assertThat(store.recoverable()).containsExactly(ready);
        assertThat(store.hasActiveForDesigner("designer")).isTrue();
        assertThat(store.terminableLaunchesForDesigner("designer")).containsExactly(launch);
        assertThat(store.ownsExternalSession("remote")).isTrue();
    }

    @Test
    void completesReadyIntentThroughDedicatedVersionedFinishTransition() {
        AcceptanceCandidateInternalTerminationIntentRow ready = intent("READY", 7, false);
        AcceptanceCandidateInternalTerminationIntentRow completed = intent("COMPLETED", 8, false);
        when(mapper.completeAcceptanceCandidateInternalTerminationIntent(
                eq("intent"), eq(7L), anyString(), anyString())).thenReturn(1);
        when(mapper.findAcceptanceCandidateInternalTerminationIntent("intent"))
                .thenReturn(Optional.of(completed));
        doAnswer(invocation -> {
            invocation.<IntSupplier>getArgument(6).getAsInt();
            return null;
        }).when(lifecycle).transition(any(), eq("READY"), eq("COMPLETED"),
                eq(LifecycleEvent.FINISH), anyString(), any(), any(), any());

        assertThat(store.complete(ready)).isEqualTo(completed);
        verify(mapper).completeAcceptanceCandidateInternalTerminationIntent(
                eq("intent"), eq(7L), anyString(), anyString());
    }

    @Test
    void idempotentCreateReturnsOnlyTheExactActiveCompilationIntent() {
        AcceptanceCandidateInternalTerminationIntentRow requested = initialFailure("RESULT_UNKNOWN");
        when(mapper.findActiveAcceptanceCandidateInternalTerminationIntentForCompilation("compilation"))
                .thenReturn(Optional.of(requested));

        assertThat(store.createIdempotent(requested)).isSameAs(requested);
        verify(lifecycle, never()).create(any(), anyString(), any(), any(), any());

        AcceptanceCandidateInternalTerminationIntentRow drifted = initialFailure("LOOKUP_UNSUPPORTED");
        assertThatThrownBy(() -> store.createIdempotent(drifted))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void promotesInitialFailureToUserCancellationWithoutReconstructingTheIntent() {
        AcceptanceCandidateInternalTerminationIntentRow initial = initialFailure("RESULT_UNKNOWN");
        AcceptanceCandidateInternalTerminationIntentRow promoted = new AcceptanceCandidateInternalTerminationIntentRow(
                initial.id(), initial.launchId(), initial.designerSessionId(), initial.compilationId(),
                initial.candidateRunId(), initial.kind(), initial.targetState(), true, initial.reasonCode(),
                "DESIGNER_CANCEL", initial.state(), initial.anchorDesignerVersion(),
                initial.anchorRequirementRevisionId(), initial.anchorDiscussionRevision(), initial.readyAt(),
                initial.completedAt(), initial.lastErrorCode(), initial.lastErrorDetail(), initial.createdAt(),
                "promoted", 1);
        when(mapper.promoteAcceptanceCandidateInitialTerminationParentAction(
                eq(initial.id()), eq(0L), eq("DESIGNER_CANCEL"), eq(true), anyString())).thenReturn(1);
        when(mapper.findAcceptanceCandidateInternalTerminationIntent(initial.id()))
                .thenReturn(Optional.of(promoted));
        doAnswer(invocation -> {
            invocation.<IntSupplier>getArgument(0).getAsInt();
            return null;
        }).when(lifecycle).mutateWithoutTransition(any(), any());

        assertThat(store.promoteInitialParentAction(
                initial, AcceptanceCandidateInternalParentAction.DESIGNER_CANCEL, true))
                .isEqualTo(promoted);
        verify(mapper).promoteAcceptanceCandidateInitialTerminationParentAction(
                eq(initial.id()), eq(0L), eq("DESIGNER_CANCEL"), eq(true), anyString());
    }

    private AcceptanceCandidateInternalTerminationIntentRow intent(
            String state, long version, boolean archiveWhenComplete) {
        return new AcceptanceCandidateInternalTerminationIntentRow(
                "intent", "launch", "designer", "compilation", "run",
                "DESIGNER_CANCEL", "CANCELLED", archiveWhenComplete, null, "DESIGNER_CANCEL", state, 3,
                null, null, "ready", "COMPLETED".equals(state) ? "completed" : null,
                null, null, "created", "updated", version);
    }

    private AcceptanceCandidateInternalTerminationIntentRow initialFailure(String reason) {
        return new AcceptanceCandidateInternalTerminationIntentRow(
                "initial-intent", "launch", "designer", "compilation", "run",
                "INITIAL_PROMPT_FAILURE", "FAILED_STOPPED", false, reason, "NONE", "REQUESTED", 3,
                "revision", 4, null, null, null, null, "created", "updated", 0);
    }
}
