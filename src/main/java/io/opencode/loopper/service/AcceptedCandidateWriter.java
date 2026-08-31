package io.opencode.loopper.service;

import io.opencode.loopper.domain.MachineCandidateKind;

/**
 * Database-only accepted-candidate seam. It runs inside the same short transaction as the
 * ACCEPTED attempt and lifecycle transition; implementations must not call model, network, process, or filesystem I/O.
 */
public interface AcceptedCandidateWriter {
    boolean supports(MachineCandidateKind kind);
    void write(CandidatePolicy.Context context, String canonicalCandidateJson, String canonicalResultSha256);
}
