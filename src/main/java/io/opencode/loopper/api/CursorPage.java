package io.opencode.loopper.api;

import java.util.List;
import java.util.Map;

/** Stable keyset page used by local read-only projections. */
public record CursorPage<T>(List<T> items, String nextCursor, Map<String, Long> facets) {
    public CursorPage {
        items = items == null ? List.of() : List.copyOf(items);
        facets = facets == null ? Map.of() : Map.copyOf(facets);
    }

    public CursorPage(List<T> items, String nextCursor) {
        this(items, nextCursor, Map.of());
    }
}
