package io.opencode.loopper.service;

import io.opencode.loopper.persistence.LoopSpecCompilationRow;

/** Exact owner checkpoints that keep an open v7 candidate run recoverable after transport uncertainty. */
final class AcceptanceCandidateOwnerCheckpoint {
    private AcceptanceCandidateOwnerCheckpoint() { }

    static boolean openVersionMatches(long frozenVersion, LoopSpecCompilationRow owner) {
        if (owner.version() == frozenVersion) return true;
        return owner.version() == frozenVersion + 1
                && "RUNNING".equals(owner.state())
                && "DISCONNECTED".equals(owner.externalSessionState())
                && owner.lastErrorCode() != null && !owner.lastErrorCode().isBlank();
    }
}
