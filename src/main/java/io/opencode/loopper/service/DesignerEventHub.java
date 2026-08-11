package io.opencode.loopper.service;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/** In-memory live snapshots for one local Designer UI; persisted messages remain authoritative. */
@Component
public class DesignerEventHub {
    private final BestEffortEventSubscribers<String, DesignerEvent> subscribers = new BestEffortEventSubscribers<>();
    private final ConcurrentHashMap<String, AtomicLong> sequences = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DesignerEvent> latest = new ConcurrentHashMap<>();

    public DesignerEvent publish(String sessionId, String type, String state, String remoteState,
                                 boolean runtimeConnected, String content, String detail) {
        long sequence = sequences.computeIfAbsent(sessionId, ignored -> new AtomicLong()).incrementAndGet();
        DesignerEvent event = new DesignerEvent(sequence, sessionId, type, state, remoteState,
                runtimeConnected, content == null ? "" : content, detail == null ? "" : detail, Instant.now().toString());
        latest.put(sessionId, event);
        subscribers.publish(sessionId, event);
        return event;
    }

    public DesignerEvent latest(String sessionId) { return latest.get(sessionId); }

    public AutoCloseable subscribe(String sessionId, Consumer<DesignerEvent> consumer) {
        return subscribers.subscribe(sessionId, consumer);
    }

    public record DesignerEvent(long sequence, String sessionId, String type, String state, String remoteState,
                                boolean runtimeConnected, String content, String detail, String at) { }
}
