package io.opencode.loopper.service;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/** In-memory live snapshots for one local Designer UI; persisted messages remain authoritative. */
@Component
public class DesignerEventHub {
    private final ConcurrentHashMap<String, CopyOnWriteArraySet<Consumer<DesignerEvent>>> subscribers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> sequences = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DesignerEvent> latest = new ConcurrentHashMap<>();

    public DesignerEvent publish(String sessionId, String type, String state, String remoteState,
                                 boolean runtimeConnected, String content, String detail) {
        long sequence = sequences.computeIfAbsent(sessionId, ignored -> new AtomicLong()).incrementAndGet();
        DesignerEvent event = new DesignerEvent(sequence, sessionId, type, state, remoteState,
                runtimeConnected, content == null ? "" : content, detail == null ? "" : detail, Instant.now().toString());
        latest.put(sessionId, event);
        subscribers.getOrDefault(sessionId, new CopyOnWriteArraySet<>()).forEach(consumer -> consumer.accept(event));
        return event;
    }

    public DesignerEvent latest(String sessionId) { return latest.get(sessionId); }

    public AutoCloseable subscribe(String sessionId, Consumer<DesignerEvent> consumer) {
        subscribers.computeIfAbsent(sessionId, ignored -> new CopyOnWriteArraySet<>()).add(consumer);
        return () -> subscribers.getOrDefault(sessionId, new CopyOnWriteArraySet<>()).remove(consumer);
    }

    public record DesignerEvent(long sequence, String sessionId, String type, String state, String remoteState,
                                boolean runtimeConnected, String content, String detail, String at) { }
}
