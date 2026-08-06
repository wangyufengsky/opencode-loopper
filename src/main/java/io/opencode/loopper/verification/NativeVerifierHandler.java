package io.opencode.loopper.verification;

import io.opencode.loopper.domain.LoopSpec.VerifierSpec;

/** Deterministic verifier extension point. Implementations never execute user supplied code. */
interface NativeVerifierHandler {
    String type();
    VerifierOutcome verify(NativeVerifierContext context, VerifierSpec spec);
}
