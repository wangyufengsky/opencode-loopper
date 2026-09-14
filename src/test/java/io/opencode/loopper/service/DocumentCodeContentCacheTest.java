package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DocumentCodeContentCacheTest {
    @Test void sharedSnapshotBlobIsLoadedOnceWhileOtherRunsAndSnapshotsRemainSeparate() {
        var cache = new DocumentCodeContentCache(100, 10); var calls = new AtomicInteger();
        java.util.function.Supplier<String> read = () -> "body-" + calls.incrementAndGet();
        assertThat(cache.read("run", "tree", "blob", read)).isEqualTo("body-1");
        assertThat(cache.read("run", "tree", "blob", read)).isEqualTo("body-1");
        assertThat(cache.read("other-run", "tree", "blob", read)).isEqualTo("body-2");
        assertThat(cache.read("run", "new-tree", "blob", read)).isEqualTo("body-3");
    }
    @Test void memoryBoundEvictsLeastRecentlyUsedAndDoesNotRetainFailures() {
        var cache = new DocumentCodeContentCache(6, 2);
        cache.read("run", "tree", "one", () -> "abc"); cache.read("run", "tree", "two", () -> "def");
        assertThat(cache.read("run", "tree", "one", () -> { throw new AssertionError(); })).isEqualTo("abc");
        cache.read("run", "tree", "three", () -> "ghi");
        assertThat(cache.read("run", "tree", "two", () -> "new")).isEqualTo("new");
        assertThatThrownBy(() -> cache.read("run", "tree", "failed", () -> { throw new IllegalStateException("unavailable"); })).isInstanceOf(IllegalStateException.class);
        assertThat(cache.read("run", "tree", "failed", () -> "ok")).isEqualTo("ok");
    }
}
