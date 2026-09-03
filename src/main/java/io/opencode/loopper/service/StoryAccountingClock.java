package io.opencode.loopper.service;

import io.opencode.loopper.persistence.StoryBindingMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;

/** Statistics waiting is not business execution time. Parallel waits are counted as a union, never twice. */
final class StoryAccountingClock {
    private StoryAccountingClock() { }
    static Instant sessionNow(StoryBindingMapper mapper, String remoteId, String since) {
        return adjusted(mapper, remoteId, null, since, Instant.now());
    }
    static Instant taskNow(StoryBindingMapper mapper, String taskId, String since) {
        return adjusted(mapper, null, taskId, since, Instant.now());
    }
    static Instant adjusted(StoryBindingMapper mapper, String remoteId, String taskId, String since, Instant now) {
        if (since == null || (remoteId == null && taskId == null)) return now;
        var rows = mapper.storyAccountingWaits(remoteId, taskId);
        if (rows == null || rows.isEmpty()) return now;
        Instant floor = Instant.parse(since);
        var ranges = new ArrayList<Instant[]>();
        for (var row : rows) {
            Instant start = Instant.parse(row.startedAt());
            Instant end = row.finishedAt() == null ? now : Instant.parse(row.finishedAt());
            if (start.isBefore(floor)) start = floor;
            if (end.isAfter(now)) end = now;
            if (end.isAfter(start)) ranges.add(new Instant[] { start, end });
        }
        ranges.sort(Comparator.comparing(pair -> pair[0]));
        Duration waiting = Duration.ZERO;
        Instant start = null, end = null;
        for (var range : ranges) {
            if (end == null || range[0].isAfter(end)) {
                if (end != null) waiting = waiting.plus(Duration.between(start, end));
                start = range[0]; end = range[1];
            } else if (range[1].isAfter(end)) end = range[1];
        }
        if (end != null) waiting = waiting.plus(Duration.between(start, end));
        return now.minus(waiting);
    }
}
