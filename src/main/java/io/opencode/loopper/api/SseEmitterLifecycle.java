package io.opencode.loopper.api;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Keeps one SSE subscription detached from an async response after the container reports an error. */
final class SseEmitterLifecycle {
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<AutoCloseable> subscription = new AtomicReference<>();

    void attach(AutoCloseable value) {
        if (!subscription.compareAndSet(null, value)) {
            closeQuietly(value);
            throw new IllegalStateException("SSE subscription is already attached");
        }
        if (closed.get()) closeAttached();
    }

    boolean send(SendAction action) {
        if (closed.get()) return false;
        try {
            action.send();
            return true;
        } catch (IOException | IllegalStateException disconnected) {
            close();
            return false;
        }
    }

    void close() {
        closed.set(true);
        closeAttached();
    }

    boolean closed() { return closed.get(); }

    private void closeAttached() { closeQuietly(subscription.getAndSet(null)); }

    private void closeQuietly(AutoCloseable value) {
        if (value == null) return;
        try { value.close(); } catch (Exception ignored) { }
    }

    @FunctionalInterface
    interface SendAction { void send() throws IOException; }
}
