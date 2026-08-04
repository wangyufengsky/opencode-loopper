package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opencode.loopper.runtime.ProcessResult;
import io.opencode.loopper.runtime.SafeProcessRunner;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenCodeModelCatalogServiceTest {
    private final SafeProcessRunner runner = mock(SafeProcessRunner.class);
    private final OpenCodeModelCatalogService catalog = new OpenCodeModelCatalogService(runner);

    @Test
    void parsesDeduplicatesAndSortsCliModelIds() {
        when(runner.run(any(), eq(List.of("opencode", "models")), any(Duration.class))).thenReturn(new ProcessResult(0, """
                opencode/zeta
                deepseek/deepseek-chat
                ignored-line
                opencode/alpha
                opencode/alpha
                """, false, false));

        assertThat(catalog.discover("opencode"))
                .extracting(OpenCodeModelCatalogService.AvailableModel::id)
                .containsExactly("deepseek/deepseek-chat", "opencode/alpha", "opencode/zeta");
    }

    @Test
    void retriesOneTransientNonZeroExit() {
        when(runner.run(any(), eq(List.of("opencode", "models")), any(Duration.class)))
                .thenReturn(new ProcessResult(1, "busy", false, false))
                .thenReturn(new ProcessResult(0, "opencode/model-a\n", false, false));

        assertThat(catalog.discover("opencode"))
                .extracting(OpenCodeModelCatalogService.AvailableModel::id)
                .containsExactly("opencode/model-a");
    }
}
