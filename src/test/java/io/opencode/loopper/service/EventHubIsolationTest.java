package io.opencode.loopper.service;

import io.opencode.loopper.persistence.TaskEventRow;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class EventHubIsolationTest {
    @Test
    void taskHubRemovesOneFailedSubscriberWithoutBlockingOthers() {
        TaskEventHub hub = new TaskEventHub();
        AtomicInteger failedCalls = new AtomicInteger();
        AtomicInteger received = new AtomicInteger();
        hub.subscribe("task-1", event -> {
            failedCalls.incrementAndGet();
            throw new IllegalStateException("stale SSE AsyncContext");
        });
        hub.subscribe("task-1", event -> received.incrementAndGet());

        assertThatNoException().isThrownBy(() -> hub.publish(event(1)));
        hub.publish(event(2));

        assertThat(failedCalls).hasValue(1);
        assertThat(received).hasValue(2);
    }

    @Test
    void designerHubTreatsLiveDeliveryAsBestEffort() {
        DesignerEventHub hub = new DesignerEventHub();
        AtomicInteger failedCalls = new AtomicInteger();
        AtomicInteger received = new AtomicInteger();
        hub.subscribe("designer-1", event -> {
            failedCalls.incrementAndGet();
            throw new IllegalStateException("stale SSE AsyncContext");
        });
        hub.subscribe("designer-1", event -> received.incrementAndGet());

        assertThatNoException().isThrownBy(() -> hub.publish(
                "designer-1", "STATUS", "RUNNING", "RUNNING", true, "", "working"));
        hub.publish("designer-1", "STATUS", "RUNNING", "RUNNING", true, "", "still working");

        assertThat(failedCalls).hasValue(1);
        assertThat(received).hasValue(2);
    }

    private TaskEventRow event(long sequence) {
        return new TaskEventRow("event-" + sequence, "task-1", sequence, "session.started", "{}",
                "2026-08-11T00:00:00Z");
    }
}
