package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import io.opencode.loopper.persistence.StoryBindingMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class StoryAccountingClockTest {
    @Test void clipsAndUnionsParallelWaitsAndIncludesOpenWaitWithoutConsumingBusinessBudget() {
        var mapper = mock(StoryBindingMapper.class);
        when(mapper.storyAccountingWaits(null, "task")).thenReturn(List.of(
                new StoryBindingMapper.AccountingWait("2026-09-03T00:00:00Z", "2026-09-03T00:00:40Z"),
                new StoryBindingMapper.AccountingWait("2026-09-03T00:00:30Z", "2026-09-03T00:00:50Z"),
                new StoryBindingMapper.AccountingWait("2026-09-03T00:01:00Z", null)));
        assertThat(StoryAccountingClock.adjusted(mapper, null, "task", "2026-09-03T00:00:10Z",
                Instant.parse("2026-09-03T00:02:00Z"))).isEqualTo(Instant.parse("2026-09-03T00:00:20Z"));
    }
}
