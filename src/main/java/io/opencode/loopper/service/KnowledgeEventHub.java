package io.opencode.loopper.service;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/** Best-effort invalidation events; persisted knowledge state is always authoritative. */
@Component
public class KnowledgeEventHub {
    private final BestEffortEventSubscribers<String, Event> subscribers = new BestEffortEventSubscribers<>();
    private final AtomicLong sequence = new AtomicLong();
    public record Event(long sequence, String conversationId, String type, String at) { }
    public void publish(String id, String type) { subscribers.publish(id, new Event(sequence.incrementAndGet(), id, type, Instant.now().toString())); }
    public AutoCloseable subscribe(String id, Consumer<Event> consumer) { return subscribers.subscribe(id, consumer); }
}
