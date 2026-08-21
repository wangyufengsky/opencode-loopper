package io.opencode.loopper.service;

import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.ModelTokenUsageRow;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelTokenUsageProjectionServiceTest {
    private final LoopperMapper mapper = mock(LoopperMapper.class);
    private final OpenCodeClient openCode = mock(OpenCodeClient.class);
    private final ModelTokenUsageProjectionService projection = new ModelTokenUsageProjectionService(mapper, openCode);

    @Test
    void persistsTranscriptUsageWithoutReadingTheCurrentRemoteTwice() {
        List<OpenCodeClient.UsageRecord> records = List.of(
                new OpenCodeClient.UsageRecord("message-1", "provider", "model", 100L, 20L,
                        120L, null, null, true),
                new OpenCodeClient.UsageRecord("message-2", "provider", "model", 40L, 10L,
                        null, null, null, true));
        when(mapper.listDesignerModelTokenUsage("designer-1")).thenReturn(List.of(
                new ModelTokenUsageRow("usage-1", "designer-1", null, "remote-1",
                        140L, 30L, 170L, true, false, "now")));

        ModelTokenUsageProjectionService.UsageView view = projection.observeDesigner(
                "designer-1", Path.of("/tmp"), "remote-1", records, false);

        ArgumentCaptor<ModelTokenUsageRow> captured = ArgumentCaptor.forClass(ModelTokenUsageRow.class);
        verify(mapper).upsertModelTokenUsage(captured.capture());
        assertThat(captured.getValue().totalTokens()).isEqualTo(170L);
        assertThat(captured.getValue().reliable()).isTrue();
        assertThat(view.totalTokens()).isEqualTo(170L);
        verify(openCode, never()).sessionUsage(any());
    }

    @Test
    void aggregatesEveryProviderRemoteInTheTaskScope() {
        when(mapper.listTaskModelTokenUsage("task-1")).thenReturn(List.of(
                new ModelTokenUsageRow("usage-1", null, "task-1", "implementation", 80L, 20L,
                        100L, true, true, "first"),
                new ModelTokenUsageRow("usage-2", null, "task-1", "judge", 32L, 8L,
                        40L, true, false, "second")));

        ModelTokenUsageProjectionService.UsageView view = projection.taskUsage("task-1");

        assertThat(view.totalTokens()).isEqualTo(140L);
        assertThat(view.unknownUsageCount()).isZero();
    }
}
