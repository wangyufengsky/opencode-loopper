package io.opencode.loopper.template;

import static org.assertj.core.api.Assertions.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class TemplateBatchWindowTest {
    record Batch(int id, String state) { }

    @Test void eightyBatchesFinishOutOfOrderWithoutDuplicateAdmissionOrWaitingForAWave() {
        var rows = new ArrayList<>(IntStream.range(0, 80).mapToObj(i -> new Batch(i, "PREPARED")).toList());
        var admitted = new HashSet<Integer>();
        int completed = 0;
        while (completed < 80) {
            var selected = TemplateBatchWindow.select(rows, Batch::state, 4);
            assertThat(selected).hasSize(Math.min(4, 80 - completed));
            for (var batch : selected) if (batch.state().equals("PREPARED")) {
                assertThat(admitted.add(batch.id())).isTrue();
                rows.set(batch.id(), new Batch(batch.id(), "RUNNING"));
            }
            // Keep the first slow batch active while later completions refill individual slots.
            var done = selected.getLast();
            rows.set(done.id(), new Batch(done.id(), "VALIDATED"));
            completed++;
            assertThat(rows.stream().filter(row -> row.state().equals("RUNNING")).count()).isLessThan(4);
        }
        assertThat(admitted).hasSize(80);
        assertThat(TemplateBatchWindow.select(rows, Batch::state, 4)).isEmpty();
    }

    @Test void restartedUnknownCreationDispatchAndStoppingObligationsStillOccupySlots() {
        var rows = List.of(new Batch(0, "PREPARED"), new Batch(1, "STOPPING"),
                new Batch(2, "CREATING"), new Batch(3, "DISPATCHING"), new Batch(4, "RUNNING"));
        assertThat(TemplateBatchWindow.select(rows, Batch::state, 4)).extracting(Batch::id).containsExactly(1, 2, 3, 4);
        assertThat(TemplateBatchWindow.select(rows, Batch::state, 1)).extracting(Batch::id).containsExactly(1, 2, 3, 4);
    }

    @Test void knownTerminalFailureDoesNotStarveOtherIndependentBatches() {
        var rows = List.of(new Batch(0, "FAILED"), new Batch(1, "PREPARED"), new Batch(2, "VALIDATED"));
        assertThat(TemplateBatchWindow.select(rows, Batch::state, 4)).extracting(Batch::id).containsExactly(1);
        assertThatThrownBy(() -> TemplateBatchWindow.select(rows, Batch::state, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TemplateBatchWindow.select(rows, Batch::state, 17)).isInstanceOf(IllegalArgumentException.class);
    }
}
