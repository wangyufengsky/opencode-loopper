package io.opencode.loopper.template;

import io.opencode.loopper.domain.TemplateBatchState;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Durable remote obligations occupy the window until a proven terminal state. */
public final class TemplateBatchWindow {
    private TemplateBatchWindow() { }

    public static <T> List<T> select(List<T> rows, Function<T, String> state, int limit) {
        if (limit < 1 || limit > 16) throw new IllegalArgumentException("模板分析并发数必须为 1 至 16");
        var active = new ArrayList<T>();
        var waiting = new ArrayList<T>();
        for (T row : rows) {
            var value = TemplateBatchState.valueOf(state.apply(row));
            if (value == TemplateBatchState.PREPARED) waiting.add(row);
            else if (!value.terminal()) active.add(row);
        }
        // Never abandon an existing remote obligation, including STOPPING, on recovery.
        int available = Math.max(0, limit - active.size());
        active.addAll(waiting.subList(0, Math.min(available, waiting.size())));
        return List.copyOf(active);
    }
}
