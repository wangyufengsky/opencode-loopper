package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.persistence.AppSettingsRow;
import io.opencode.loopper.persistence.LoopperMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SettingsServiceTest {
    @Test
    void persistsAndAppliesASelectedModelToFutureSessions() {
        LoopperMapper mapper = mock(LoopperMapper.class);
        OpenCodeModelCatalogService catalog = mock(OpenCodeModelCatalogService.class);
        LoopperProperties properties = new LoopperProperties();
        when(mapper.findAppSettings()).thenReturn(Optional.empty());
        when(catalog.discover("opencode")).thenReturn(List.of(
                new OpenCodeModelCatalogService.AvailableModel("deepseek/deepseek-chat", "deepseek", "deepseek-chat", "deepseek / deepseek-chat")));
        SettingsService service = new SettingsService(mapper, properties, catalog);

        SettingsService.AppSettings saved = service.save(new SettingsService.AppSettings(
                "opencode", "", "deepseek", "deepseek-chat", 9, 25, false, null));

        ArgumentCaptor<AppSettingsRow> row = ArgumentCaptor.forClass(AppSettingsRow.class);
        verify(mapper).upsertAppSettings(row.capture());
        assertThat(row.getValue().modelId()).isEqualTo("deepseek-chat");
        assertThat(saved.model()).isEqualTo("deepseek-chat");
        assertThat(properties.getOpenCode().getModel()).isEqualTo("deepseek/deepseek-chat");
        assertThat(properties.getMaxTaskAttempts()).isEqualTo(9);
        assertThat(properties.getAttemptTimeout()).hasMinutes(25);
    }
}
