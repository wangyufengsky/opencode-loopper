package io.opencode.loopper.service.knowledge;

import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;
import static io.opencode.loopper.service.knowledge.KnowledgeFiles.*;

/** Bounded, short-lived directory metadata snapshots; source contents are always read and checked afresh. */
final class KnowledgeDirectoryPages {
    private static final String PREFIX = "@listing:";
    private final Clock clock;
    private final LinkedHashMap<String, Snapshot> snapshots = new LinkedHashMap<>();
    private record Snapshot(Object scope, List<Entry> entries, boolean incomplete, String detail, long expires, int chars) { }
    KnowledgeDirectoryPages() { this(Clock.systemUTC()); }
    KnowledgeDirectoryPages(Clock clock) { this.clock = clock; }
    Listing page(Object scope, String cursor, Supplier<Listing> scan) {
        if (cursor != null && cursor.startsWith(PREFIX)) return cached(scope, cursor);
        var listing = scan.get(); // Filesystem work must not hold the cache lock.
        var entries = listing.items().stream().sorted(Comparator.comparing(Entry::path)).toList();
        int start = 0; // Accept pre-upgrade path cursors, but new pages use snapshot identities.
        if (cursor != null && !cursor.isBlank()) while (start < entries.size() && entries.get(start).path().compareTo(cursor) <= 0) start++;
        int chars = entries.stream().mapToInt(e -> e.path().length() + e.name().length()).sum();
        if (chars > 4_000_000) throw KnowledgeSources.bad("目录条目过多，请缩小到子目录后重试");
        synchronized (this) {
            expire();
            while (!snapshots.isEmpty() && (snapshots.size() >= 16 || snapshots.values().stream().mapToInt(Snapshot::chars).sum() + chars > 4_000_000)) snapshots.remove(snapshots.keySet().iterator().next());
            String id = UUID.randomUUID().toString();
            var saved = new Snapshot(scope, entries, listing.incomplete(), listing.detail(), clock.millis() + 300_000, chars);
            snapshots.put(id, saved); return slice(id, saved, start);
        }
    }
    private synchronized Listing cached(Object scope, String cursor) {
        expire(); var parts = cursor.substring(PREFIX.length()).split(":", -1);
        if (parts.length != 2) throw expired();
        var saved = snapshots.get(parts[0]);
        if (saved == null || !saved.scope().equals(scope)) throw expired();
        try { return slice(parts[0], saved, Integer.parseInt(parts[1])); }
        catch (NumberFormatException invalid) { throw expired(); }
    }
    private void expire() { snapshots.values().removeIf(s -> s.expires() <= clock.millis()); }
    private Listing slice(String id, Snapshot saved, int start) {
        if (start < 0 || start > saved.entries().size()) throw expired();
        int end = Math.min(start + 50, saved.entries().size());
        return new Listing(saved.entries().subList(start, end), end < saved.entries().size() ? PREFIX + id + ":" + end : null,
                saved.incomplete(), saved.detail(), PREFIX + id + ":" + start);
    }
    static String resume(Listing listing, int processed) {
        if (processed < 0 || processed > listing.items().size()) throw expired();
        if (processed == listing.items().size()) return listing.nextCursor();
        String cursor = listing.resumeCursor();
        if (cursor == null) return processed == 0 ? null : listing.items().get(processed - 1).path();
        int colon = cursor.lastIndexOf(':');
        return cursor.substring(0, colon + 1) + (Integer.parseInt(cursor.substring(colon + 1)) + processed);
    }
    private static RuntimeException expired() { return KnowledgeSources.bad("目录分页已过期或资料范围发生变化，请从第一页重新检索"); }
}
