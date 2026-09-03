package io.opencode.loopper.runtime;

import io.opencode.loopper.domain.SessionFailure;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Coordinates accounting and ordinary cleanup; explicit cancellation releases only its own turn. */
final class OpenCodeSessionCommandGate {
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    <T> T command(OpenCodeClient.OpenCodeSession remote, String messageId, Supplier<T> action) {
        return run(remote, messageId, action);
    }

    <T> T abort(OpenCodeClient.OpenCodeSession remote, Supplier<T> action) {
        return run(remote, null, action);
    }

    void cancelled(OpenCodeClient.OpenCodeSession remote, String messageId) {
        Entry entry = entries.get(remote.id());
        if (entry == null) return;
        synchronized (entry) {
            if (messageId.equals(entry.messageId)) { entry.messageId = null; entry.notifyAll(); }
        }
    }

    private <T> T run(OpenCodeClient.OpenCodeSession remote, String messageId, Supplier<T> action) {
        // Cleanup callers may omit generation; the HTTP adapter resolves the durable binding.
        String key = remote.id();
        Entry entry = entries.compute(key, (ignored, current) -> {
            if (current == null) current = new Entry();
            current.users++;
            return current;
        });
        boolean acquired = false;
        try {
            synchronized (entry) {
                while (entry.messageId != null || entry.aborting) entry.wait();
                if (messageId == null) entry.aborting = true;
                else entry.messageId = messageId;
                acquired = true;
            }
            return action.get();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new SessionFailure("OPENCODE_COMMAND_WAIT_INTERRUPTED", "等待统计交接时被中断");
        } finally {
            if (acquired) synchronized (entry) {
                if (messageId == null) entry.aborting = false;
                else if (messageId.equals(entry.messageId)) entry.messageId = null;
                entry.notifyAll();
            }
            entries.computeIfPresent(key, (ignored, current) -> --current.users == 0 ? null : current);
        }
    }

    private static final class Entry {
        String messageId;
        boolean aborting;
        int users;
    }
}
