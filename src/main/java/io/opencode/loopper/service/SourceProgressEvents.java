package io.opencode.loopper.service;

import io.opencode.loopper.persistence.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/** SSE is an invalidation signal; it cannot mutate a task or supply success facts. */
@Component
public final class SourceProgressEvents {
    private final SourceTemplateMapper runs;
    private final SourceTemplateModelMapper models;
    private final BestEffortEventSubscribers<String, String> subscribers = new BestEffortEventSubscribers<>();
    private final ConcurrentHashMap<String, String> previous = new ConcurrentHashMap<>();
    public SourceProgressEvents(SourceTemplateMapper runs, SourceTemplateModelMapper models) { this.runs = runs; this.models = models; }
    public AutoCloseable subscribe(String id, Consumer<String> listener) { return subscribers.subscribe(id, listener); }
    public void publish(String id) {
        try {
            var run = runs.find(id).orElseThrow();
            String fingerprint = run.version() + ":" + models.active(id).stream().map(m -> m.id() + ":" + m.version()).toList();
            if (!fingerprint.equals(previous.put(id, fingerprint))) subscribers.publish(id, id);
            if (previous.size() > 4096) previous.clear();
        } catch (RuntimeException unavailable) { /* A later REST refresh remains authoritative. */ }
    }
}
