package io.opencode.loopper.service;

import java.util.function.Consumer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Global invalidations only; reconnecting browsers recover from the persisted call list. */
@Component
public class StoryAccountingEventHub {
    private final BestEffortEventSubscribers<String, String> subscribers = new BestEffortEventSubscribers<>();

    public AutoCloseable subscribe(Consumer<String> consumer) { return subscribers.subscribe("accounting", consumer); }

    public void changed(String callId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { subscribers.publish("accounting", callId); }
            });
        } else subscribers.publish("accounting", callId);
    }

    /** SSE comments keep idle connections detectable without database reads or browser refreshes. */
    @Scheduled(fixedDelay = 25_000)
    public void heartbeat() { subscribers.publish("accounting", ""); }
}
