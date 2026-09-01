package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opencode.loopper.domain.MachineCandidateRunState;
import io.opencode.loopper.persistence.LoopSpecCompilationRow;
import org.junit.jupiter.api.Test;

class AcceptanceCandidateOwnerCheckpointTest {
    @Test
    void exactCorrectionMarkerSurvivesRestartWhileRunIsOpen() {
        MachineCandidateSubmission.RunSnapshot run = run(MachineCandidateRunState.OPEN, null);
        LoopSpecCompilationRow marker = owner(8, "CORRECTION_STOP_REQUESTED", "BUDGET_EXHAUSTED");

        assertThat(AcceptanceCandidateOwnerCheckpoint.correctionStopRecoveryMatches(run, marker)).isTrue();
        assertThat(AcceptanceCandidateOwnerCheckpoint.correctionStopRecoveryMatches(
                run, owner(8, "CORRECTION_STOP_REQUESTED", "arbitrary detail"))).isFalse();
    }

    @Test
    void ownerClosedCorrectionAcceptsTheMarkerBeforeProofAndTheExactProofCheckpointAfterward() {
        MachineCandidateSubmission.RunSnapshot run = run(MachineCandidateRunState.CLOSED,
                MachineCandidateSubmission.CandidateCloseReason.OWNER_REQUESTED);

        assertThat(AcceptanceCandidateOwnerCheckpoint.correctionStopRecoveryMatches(
                run, owner(8, "CORRECTION_STOP_REQUESTED", "LOOKUP_UNSUPPORTED"))).isTrue();
        assertThat(AcceptanceCandidateOwnerCheckpoint.correctionStopRecoveryMatches(
                run, owner(9, "CORRECTION_ABORT_DISPATCHED", "LOOKUP_UNSUPPORTED"))).isTrue();
        LoopSpecCompilationRow proof = owner(10, "ABORT_ACKNOWLEDGED", "LOOKUP_UNSUPPORTED");
        assertThat(AcceptanceCandidateOwnerCheckpoint.correctionStopRecoveryMatches(run, proof)).isTrue();
        assertThat(AcceptanceCandidateOwnerCheckpoint.settledCorrectionStopMarker(proof)).isTrue();
        assertThat(AcceptanceCandidateOwnerCheckpoint.correctionStopRecoveryMatches(
                run, owner(10, "CANDIDATE_RUNNING", "LOOKUP_UNSUPPORTED"))).isFalse();
        assertThat(AcceptanceCandidateOwnerCheckpoint.settledCorrectionStopMarker(
                owner(10, "ABORT_ACKNOWLEDGED", "untrusted detail"))).isFalse();
    }

    @Test
    void acceptedRaceUsesItsOwnerWriteBeforeTheThreeCorrectionCheckpoints() {
        MachineCandidateSubmission.RunSnapshot accepted = run(MachineCandidateRunState.ACCEPTED, null);

        assertThat(AcceptanceCandidateOwnerCheckpoint.correctionStopRecoveryMatches(
                accepted, owner(9, "CORRECTION_STOP_REQUESTED", "BUDGET_EXHAUSTED"))).isTrue();
        assertThat(AcceptanceCandidateOwnerCheckpoint.correctionStopRecoveryMatches(
                accepted, owner(10, "CORRECTION_ABORT_DISPATCHED", "BUDGET_EXHAUSTED"))).isTrue();
        assertThat(AcceptanceCandidateOwnerCheckpoint.correctionStopRecoveryMatches(
                accepted, owner(11, "ABORT_ACKNOWLEDGED", "BUDGET_EXHAUSTED"))).isTrue();
        assertThat(AcceptanceCandidateOwnerCheckpoint.correctionStopRecoveryMatches(
                accepted, owner(10, "ABORT_ACKNOWLEDGED", "BUDGET_EXHAUSTED"))).isFalse();
    }

    @Test
    void waitingInputRaceUsesOnlyTheThreeCorrectionCheckpoints() {
        MachineCandidateSubmission.RunSnapshot waiting = run(MachineCandidateRunState.WAITING_INPUT, null);

        assertThat(AcceptanceCandidateOwnerCheckpoint.correctionStopRecoveryMatches(
                waiting, owner(8, "CORRECTION_STOP_REQUESTED", "LOOKUP_UNSUPPORTED"))).isTrue();
        assertThat(AcceptanceCandidateOwnerCheckpoint.correctionStopRecoveryMatches(
                waiting, owner(9, "CORRECTION_ABORT_DISPATCHED", "LOOKUP_UNSUPPORTED"))).isTrue();
        assertThat(AcceptanceCandidateOwnerCheckpoint.correctionStopRecoveryMatches(
                waiting, owner(10, "REMOTE_COMPLETED", "LOOKUP_UNSUPPORTED"))).isTrue();
        assertThat(AcceptanceCandidateOwnerCheckpoint.correctionStopRecoveryMatches(
                waiting, owner(11, "REMOTE_COMPLETED", "LOOKUP_UNSUPPORTED"))).isFalse();
    }

    private static MachineCandidateSubmission.RunSnapshot run(
            MachineCandidateRunState state, MachineCandidateSubmission.CandidateCloseReason closeReason) {
        MachineCandidateSubmission.RunSnapshot run = mock(MachineCandidateSubmission.RunSnapshot.class);
        when(run.ownerVersion()).thenReturn(7L);
        when(run.state()).thenReturn(state);
        when(run.closeReason()).thenReturn(closeReason);
        return run;
    }

    private static LoopSpecCompilationRow owner(long version, String remoteState, String reason) {
        LoopSpecCompilationRow owner = mock(LoopSpecCompilationRow.class);
        when(owner.version()).thenReturn(version);
        when(owner.state()).thenReturn("RUNNING");
        when(owner.externalSessionState()).thenReturn(remoteState);
        when(owner.lastErrorCode()).thenReturn("ACCEPTANCE_CORRECTION_WAITING_INPUT_PENDING");
        when(owner.lastErrorDetail()).thenReturn(reason);
        return owner;
    }
}
