package io.opencode.loopper.service;

import io.opencode.loopper.runtime.OpenCodeClient;

/** Closed positive evidence set proving one candidate Session can no longer consume work. */
enum CandidateSessionTerminationProof {
    REMOTE_COMPLETED,
    ABORT_ACKNOWLEDGED,
    ALREADY_ABSENT;

    static boolean persisted(String state) {
        if (state == null) return false;
        for (CandidateSessionTerminationProof proof : values()) {
            if (proof.name().equals(state)) return true;
        }
        return false;
    }

    static CandidateSessionTerminationProof from(OpenCodeClient.AbortConfirmation confirmation) {
        if (confirmation == null) throw new ConflictException(
                "OPENCODE_ACCEPTANCE_CANDIDATE_STOP_UNCONFIRMED",
                "OpenCode abort did not return a positive acknowledgement");
        return confirmation == OpenCodeClient.AbortConfirmation.ALREADY_ABSENT
                ? ALREADY_ABSENT : ABORT_ACKNOWLEDGED;
    }
}
