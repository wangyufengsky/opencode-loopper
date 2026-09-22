package io.opencode.loopper.service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

/** In-memory live delivery that never lets one disconnected consumer affect authoritative state. */
public final class BestEffortEventSubscribers<K, E> {
    private final ConcurrentHashMap<K, CopyOnWriteArraySet<Consumer<E>>> subscribers = new ConcurrentHashMap<>();

    public AutoCloseable subscribe(K key, Consumer<E> consumer) {
        subscribers.computeIfAbsent(key, ignored -> new CopyOnWriteArraySet<>()).add(consumer);
        return () -> remove(key, consumer);
    }

    public void publish(K key, E event) {
        CopyOnWriteArraySet<Consumer<E>> current = subscribers.get(key);
        if (current == null) return;
        for (Consumer<E> consumer : current) {
            try {
                consumer.accept(event);
            } catch (RuntimeException disconnected) {
                remove(key, consumer);
            }
        }
    }

    private void remove(K key, Consumer<E> consumer) {
        CopyOnWriteArraySet<Consumer<E>> current = subscribers.get(key);
        if (current == null) return;
        current.remove(consumer);
        if (current.isEmpty()) subscribers.remove(key, current);
    }
}
