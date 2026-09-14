package io.opencode.loopper.service;

import io.opencode.loopper.persistence.DocumentProgressMapper;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/** Events invalidate projections only. Reconnecting clients always read the current REST overview. */
@Component
public final class DocumentProgressEvents {
    private final DocumentProgressMapper reads;
    private final BestEffortEventSubscribers<String, String> subscribers = new BestEffortEventSubscribers<>();
    private final ConcurrentHashMap<String, DocumentProgressMapper.Progress> previous = new ConcurrentHashMap<>();
    public DocumentProgressEvents(DocumentProgressMapper reads) { this.reads = reads; }
    public AutoCloseable subscribe(String id, Consumer<String> consumer) { return subscribers.subscribe(id, consumer); }
    public void publish(String id) {
        try {
            var current = reads.progress(id);
            if (current != null && !current.equals(previous.put(id, current))) subscribers.publish(id, id);
            if (previous.size() > 4096) previous.clear();
        } catch (RuntimeException unavailable) { /* REST remains authoritative; publication never fails execution. */ }
    }
}
