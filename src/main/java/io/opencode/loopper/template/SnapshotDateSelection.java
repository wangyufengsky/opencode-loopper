package io.opencode.loopper.template;

import java.time.Instant;
import java.util.List;

/** Time selection follows first-parent topology; timestamps never prune traversal. */
public final class SnapshotDateSelection {
    private SnapshotDateSelection() { }
    public record Commit(String sha, Instant time) { }
    public record Selection(String baseline, String target, boolean nonMonotonic) { }
    public static Selection select(List<Commit> newestFirst, TemplateDateRange dates) {
        String before = null, after = null; boolean anomaly = false; Instant previous = null;
        for (var commit : newestFirst) {
            if (previous != null && commit.time().isAfter(previous)) anomaly = true;
            previous = commit.time();
            if (before == null && commit.time().isBefore(dates.startInclusive())) before = commit.sha();
            if (after == null && commit.time().isBefore(dates.endExclusive())) after = commit.sha();
        }
        if (before == null || after == null) throw new IllegalArgumentException("日期边界之前没有可用版本，请调整日期或选择全面审查");
        return new Selection(before, after, anomaly);
    }
}
