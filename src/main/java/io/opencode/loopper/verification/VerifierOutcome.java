package io.opencode.loopper.verification;

import io.opencode.loopper.domain.VerificationState;
import java.util.Map;
public record VerifierOutcome(String type, VerificationState state, String summary, Map<String, Object> evidence) { }
