package io.opencode.loopper.service;

import java.util.LinkedHashMap;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** Bounded immutable text reuse across analysis batches. Authorization and model-specific read receipts remain outside this cache. */
@Component
public final class DocumentCodeContentCache {
    private final int maxCharacters;
    private final int maxEntries;
    private int characters;
    private final LinkedHashMap<Key, String> entries = new LinkedHashMap<>(16, 0.75f, true);
    public DocumentCodeContentCache() { this(8 * 1024 * 1024, 128); }
    DocumentCodeContentCache(int maxCharacters, int maxEntries) { this.maxCharacters = maxCharacters; this.maxEntries = maxEntries; }
    public String read(String runId, String snapshotSha, String blobSha, Supplier<String> loader) {
        var key = new Key(runId, snapshotSha, blobSha);
        synchronized (entries) {
            var cached = entries.get(key); if (cached != null) return cached;
        }
        // Git I/O never holds the shared cache monitor or a database transaction.
        String loaded = loader.get();
        if (loaded.length() > maxCharacters) return loaded;
        synchronized (entries) {
            var concurrent = entries.get(key);
            if (concurrent != null) return concurrent;
            while (!entries.isEmpty() && (entries.size() >= maxEntries || characters + loaded.length() > maxCharacters)) {
                var first = entries.entrySet().iterator(); var old = first.next(); characters -= old.getValue().length(); first.remove();
            }
            entries.put(key, loaded); characters += loaded.length();
        }
        return loaded;
    }
    private record Key(String runId, String snapshotSha, String blobSha) { }
}
