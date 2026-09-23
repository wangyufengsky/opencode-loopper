package io.opencode.loopper.service.knowledge;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeDirectoryPagesTest {
    @TempDir Path root;
    @Test void fourPagesAndReplaysUseOneScanWithBoundedLifetimeAndExactScope() {
        Clock clock = mock(Clock.class); when(clock.millis()).thenReturn(1000L);
        var pages = new KnowledgeDirectoryPages(clock); var scans = new AtomicInteger();
        var entries = java.util.stream.IntStream.range(0, 151).mapToObj(i -> new KnowledgeFiles.Entry("%03d.java".formatted(i), "file", false, 1)).toList();
        java.util.function.Supplier<KnowledgeFiles.Listing> load = () -> { scans.incrementAndGet(); return new KnowledgeFiles.Listing(entries, null, false, ""); };
        var first = pages.page("source", null, load); String cursor = first.nextCursor();
        var second = pages.page("source", cursor, load);
        assertThat(pages.page("source", cursor, load)).isEqualTo(second);
        var third = pages.page("source", second.nextCursor(), load);
        assertThat(pages.page("source", third.nextCursor(), load).items()).hasSize(1);
        assertThat(scans).hasValue(1);
        assertThat(pages.page("source", KnowledgeDirectoryPages.resume(first, 30), load).items().getFirst().path()).isEqualTo("030.java");
        assertThatThrownBy(() -> pages.page("other-source", cursor, load)).hasMessageContaining("范围");
        when(clock.millis()).thenReturn(301001L);
        assertThatThrownBy(() -> pages.page("source", cursor, load)).hasMessageContaining("过期");
        pages.page("source", null, load); assertThat(scans).hasValue(2);
    }
    @Test void cachedMetadataStaysStableButFreshBrowseSeesChangesAndSymlinkReplacementIsDenied() throws Exception {
        root = root.toRealPath();
        Path directory = Files.createDirectory(root.resolve("docs"));
        for (int i = 0; i < 61; i++) Files.writeString(directory.resolve("%03d.md".formatted(i)), "text");
        var first = KnowledgeFiles.list(root.toString(), "docs", true, "", null, true);
        Files.writeString(directory.resolve("999.md"), "new");
        var second = KnowledgeFiles.list(root.toString(), "docs", true, "", first.nextCursor(), true);
        assertThat(second.items()).hasSize(11);
        var fresh = KnowledgeFiles.list(root.toString(), "docs", true, "", null, true);
        assertThat(KnowledgeFiles.list(root.toString(), "docs", true, "", fresh.nextCursor(), true).items()).hasSize(12);
        assertThatThrownBy(() -> KnowledgeFiles.list(root.toString(), "docs", true, "other", first.nextCursor(), true)).hasMessageContaining("范围");
        Files.move(directory, root.resolve("original")); Files.createSymbolicLink(directory, root.resolve("original"));
        assertThatThrownBy(() -> KnowledgeFiles.list(root.toString(), "docs", true, "", first.nextCursor(), true)).isInstanceOf(RuntimeException.class);
        assertThat(new tools.jackson.databind.ObjectMapper().writeValueAsString(first)).doesNotContain("resumeCursor");
    }
}
