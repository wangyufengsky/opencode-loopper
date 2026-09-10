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

    /** Keep the entry alive across accounting waits so a stop cannot be forgotten before dispatch. */
    Prompt preparePrompt(OpenCodeClient.OpenCodeSession remote) {
        Entry entry = retain(remote.id());
        synchronized (entry) { return new Prompt(remote.id(), entry, entry.abortRevision); }
    }

    final class Prompt implements AutoCloseable {
        private final String key;
        private final Entry entry;
        private final long revision;
        private Prompt(String key, Entry entry, long revision) {
            this.key = key; this.entry = entry; this.revision = revision;
        }
        void dispatch(Runnable action) {
            try {
                synchronized (entry) {
                    while (entry.messageId != null || entry.aborting || entry.prompting) entry.wait();
                    if (entry.abortRevision != revision) throw new SessionFailure("OPENCODE_PROMPT_CANCELLED",
                            "业务提示等待期间会话已停止，取消本次旧提示");
                    entry.prompting = true;
                }
                try { action.run(); }
                finally { synchronized (entry) { entry.prompting = false; entry.notifyAll(); } }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new SessionFailure("OPENCODE_PROMPT_WAIT_INTERRUPTED", "业务提示等待期间被中断");
            }
        }
        @Override public void close() { release(key); }
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
        Entry entry = retain(key);
        boolean acquired = false;
        try {
            synchronized (entry) {
                if (messageId == null) entry.abortRevision++;
                while (entry.messageId != null || entry.aborting || entry.prompting) entry.wait();
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
            release(key);
        }
    }

    private Entry retain(String key) {
        return entries.compute(key, (ignored, current) -> {
            if (current == null) current = new Entry();
            current.users++;
            return current;
        });
    }

    private void release(String key) {
        entries.computeIfPresent(key, (ignored, current) -> --current.users == 0 ? null : current);
    }

    private static final class Entry {
        String messageId;
        boolean aborting;
        boolean prompting;
        long abortRevision;
        int users;
    }
}
