package io.opencode.loopper.service;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.domain.RetryCause;
import java.time.Duration;
import java.util.Locale;

/** Deterministic retry classification, backoff, and budget policy. */
final class TaskRetryPolicy {
    private final LoopperProperties properties;

    TaskRetryPolicy(LoopperProperties properties) {
        this.properties = properties;
    }

    int delaySeconds(RetryCause cause, int ordinal) {
        LoopperProperties.RetryWait policy = properties.getRetryWait();
        Duration base = switch (cause) {
            case RATE_LIMIT -> policy.getRateLimitBase();
            case SESSION -> policy.getSessionBase();
            case VERIFICATION -> policy.getVerificationBase();
        };
        Duration maximum = switch (cause) {
            case RATE_LIMIT -> policy.getRateLimitMax();
            case SESSION -> policy.getSessionMax();
            case VERIFICATION -> policy.getVerificationMax();
        };
        long delay = Math.max(0, base.toSeconds());
        long cap = Math.max(delay, maximum.toSeconds());
        for (int index = 1; index < ordinal && delay < cap; index++) {
            delay = Math.min(cap, delay * 2);
        }
        return Math.toIntExact(delay);
    }

    boolean rateLimited(String code, String message) {
        String combined = ((code == null ? "" : code) + " " + (message == null ? "" : message))
                .toLowerCase(Locale.ROOT);
        return combined.contains("429") || combined.contains("too frequent")
                || combined.contains("rate limit") || combined.contains("rate_limit");
    }

    int sessionErrorLimit(LoopSpec spec) {
        return Math.min(spec.limits().sessionErrorLimit(), properties.getSessionErrorLimit());
    }
}
