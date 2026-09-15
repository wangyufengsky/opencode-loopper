package io.opencode.loopper.template;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class SnapshotDateSelectionTest {
    private final TemplateDateRange range = new TemplateDateRange(LocalDate.of(2026,9,1), LocalDate.of(2026,9,10));
    private SnapshotDateSelection.Commit c(String sha, String instant) { return new SnapshotDateSelection.Commit(sha, Instant.parse(instant)); }
    @Test void includesEndDayAndExcludesBothCutoffInstantsFromTheirSnapshots() {
        var selected = SnapshotDateSelection.select(List.of(c("outside", "2026-09-10T16:00:00Z"),
                c("end", "2026-09-10T15:59:59Z"), c("start", "2026-08-31T16:00:00Z"), c("base", "2026-08-31T15:59:59Z")), range);
        assertThat(selected.baseline()).isEqualTo("base"); assertThat(selected.target()).isEqualTo("end");
    }
    @Test void nonMonotonicDatesDoNotStopTraversal() {
        var selected = SnapshotDateSelection.select(List.of(c("target", "2026-09-09T00:00:00Z"),
                c("futureParent", "2026-09-15T00:00:00Z"), c("base", "2026-08-30T00:00:00Z")), range);
        assertThat(selected.nonMonotonic()).isTrue(); assertThat(selected.baseline()).isEqualTo("base");
    }
    @Test void daysWithoutChangesResolveToSameCommit() {
        var selected = SnapshotDateSelection.select(List.of(c("base", "2026-08-30T00:00:00Z")), range);
        assertThat(selected.baseline()).isEqualTo(selected.target());
    }
    @Test void missingBaselineNeverSilentlyExpandsScope() {
        assertThatThrownBy(() -> SnapshotDateSelection.select(List.of(c("first", "2026-09-02T00:00:00Z")), range)).hasMessageContaining("全面审查");
    }
    @Test void calendarBoundaryIncludesLeapDayAndYearEnd() {
        assertThat(new TemplateDateRange(LocalDate.of(2024,2,29), LocalDate.of(2024,2,29)).endExclusive()).isEqualTo(Instant.parse("2024-02-29T16:00:00Z"));
        assertThat(new TemplateDateRange(LocalDate.of(2026,12,31), LocalDate.of(2026,12,31)).endExclusive()).isEqualTo(Instant.parse("2026-12-31T16:00:00Z"));
    }
}
