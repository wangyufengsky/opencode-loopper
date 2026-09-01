package io.opencode.loopper.service;

import io.opencode.loopper.domain.MachineCandidateRunState;
import io.opencode.loopper.persistence.LoopSpecCompilationRow;

/** Exact owner checkpoints that keep an open v7 candidate run recoverable after transport uncertainty. */
final class AcceptanceCandidateOwnerCheckpoint {
    static final String CORRECTION_MARKER = "ACCEPTANCE_CORRECTION_WAITING_INPUT_PENDING";
    static final String CORRECTION_STOP_REQUESTED = "CORRECTION_STOP_REQUESTED";
    static final String CORRECTION_ABORT_DISPATCHED = "CORRECTION_ABORT_DISPATCHED";
    private AcceptanceCandidateOwnerCheckpoint() { }

    static boolean openVersionMatches(long frozenVersion, LoopSpecCompilationRow owner) {
        if (owner.version() == frozenVersion) return true;
        return owner.version() == frozenVersion + 1
                && "RUNNING".equals(owner.state())
                && "DISCONNECTED".equals(owner.externalSessionState())
                && owner.lastErrorCode() != null && !owner.lastErrorCode().isBlank();
    }

    static boolean correctionStopRecoveryMatches(
            MachineCandidateSubmission.RunSnapshot run, LoopSpecCompilationRow owner) {
        if (run == null || owner == null || !"RUNNING".equals(owner.state())
                || !isCorrectionStopMarker(owner)
                || !safeCorrectionReason(owner.lastErrorDetail())) return false;
        long baseVersion = correctionBaseVersion(run);
        if (owner.version() == baseVersion + 1
                && CORRECTION_STOP_REQUESTED.equals(owner.externalSessionState())) {
            return recoverableCorrectionState(run.state());
        }
        if (owner.version() == baseVersion + 2
                && CORRECTION_ABORT_DISPATCHED.equals(owner.externalSessionState())) {
            return recoverableCorrectionState(run.state());
        }
        return owner.version() == baseVersion + 3
                && run.state().terminal()
                && CandidateSessionTerminationProof.persisted(owner.externalSessionState());
    }

    static boolean correctionStopPreProofMatches(
            MachineCandidateSubmission.RunSnapshot run, LoopSpecCompilationRow owner) {
        if (!correctionStopRecoveryMatches(run, owner)) return false;
        return CORRECTION_STOP_REQUESTED.equals(owner.externalSessionState())
                || CORRECTION_ABORT_DISPATCHED.equals(owner.externalSessionState());
    }

    static long correctionProofVersion(MachineCandidateSubmission.RunSnapshot run) {
        return correctionBaseVersion(run) + 3;
    }

    static boolean isCorrectionStopMarker(LoopSpecCompilationRow owner) {
        return owner != null && CORRECTION_MARKER.equals(owner.lastErrorCode());
    }

    static boolean settledCorrectionStopMarker(LoopSpecCompilationRow owner) {
        return owner != null && "RUNNING".equals(owner.state()) && isCorrectionStopMarker(owner)
                && safeCorrectionReason(owner.lastErrorDetail())
                && CandidateSessionTerminationProof.persisted(owner.externalSessionState());
    }

    private static boolean safeCorrectionReason(String detail) {
        return "BUDGET_EXHAUSTED".equals(detail) || "LOOKUP_UNSUPPORTED".equals(detail);
    }

    private static long correctionBaseVersion(MachineCandidateSubmission.RunSnapshot run) {
        return run.ownerVersion() + (run.state() == MachineCandidateRunState.ACCEPTED ? 1 : 0);
    }

    private static boolean recoverableCorrectionState(MachineCandidateRunState state) {
        return state == MachineCandidateRunState.OPEN || state != null && state.terminal();
    }
}
