package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.RetryCause;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class TaskRetryPolicyTest {
    @Test
    void doublesDelayUntilConfiguredCapWithoutJitter() {
        LoopperProperties properties = new LoopperProperties();
        properties.getRetryWait().setSessionBase(Duration.ofSeconds(10));
        properties.getRetryWait().setSessionMax(Duration.ofSeconds(60));
        TaskRetryPolicy policy = new TaskRetryPolicy(properties);

        assertThat(policy.delaySeconds(RetryCause.SESSION, 1)).isEqualTo(10);
        assertThat(policy.delaySeconds(RetryCause.SESSION, 4)).isEqualTo(60);
        assertThat(policy.delaySeconds(RetryCause.SESSION, 9)).isEqualTo(60);
    }

    @Test
    void recognizesCommonRateLimitSignals() {
        TaskRetryPolicy policy = new TaskRetryPolicy(new LoopperProperties());

        assertThat(policy.rateLimited("HTTP_429", null)).isTrue();
        assertThat(policy.rateLimited(null, "provider rate limit exceeded")).isTrue();
        assertThat(policy.rateLimited("SESSION_FAILED", "connection reset")).isFalse();
    }
}
