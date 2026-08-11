package io.opencode.loopper.api;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SseEmitterLifecycleTest {
    @Test
    void ioFailureClosesTheSubscriptionWithoutAnotherAsyncCompletion() {
        SseEmitterLifecycle lifecycle = new SseEmitterLifecycle();
        AtomicInteger closes = new AtomicInteger();
        AtomicInteger sends = new AtomicInteger();
        lifecycle.attach(closes::incrementAndGet);

        boolean delivered = lifecycle.send(() -> {
            sends.incrementAndGet();
            throw new IOException("browser disconnected");
        });
        boolean retried = lifecycle.send(sends::incrementAndGet);

        assertThat(delivered).isFalse();
        assertThat(retried).isFalse();
        assertThat(lifecycle.closed()).isTrue();
        assertThat(sends).hasValue(1);
        assertThat(closes).hasValue(1);
    }

    @Test
    void tomcatAsyncContextFailureClosesTheSubscriptionIdempotently() {
        SseEmitterLifecycle lifecycle = new SseEmitterLifecycle();
        AtomicInteger closes = new AtomicInteger();
        lifecycle.attach(closes::incrementAndGet);

        boolean delivered = lifecycle.send(() -> {
            throw new IllegalStateException("AsyncContext cannot be used after onError");
        });
        lifecycle.close();

        assertThat(delivered).isFalse();
        assertThat(lifecycle.closed()).isTrue();
        assertThat(closes).hasValue(1);
    }

    @Test
    void attachingAfterContainerErrorStillClosesTheLateSubscription() {
        SseEmitterLifecycle lifecycle = new SseEmitterLifecycle();
        AtomicInteger closes = new AtomicInteger();

        lifecycle.close();
        lifecycle.attach(closes::incrementAndGet);

        assertThat(closes).hasValue(1);
    }
}
