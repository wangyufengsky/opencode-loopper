package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.persistence.AppSettingsRow;
import io.opencode.loopper.persistence.LoopperMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class SettingsServiceTest {
    @Test
    void acceptsManagedModeWithoutDowngradingThePersistedStartupSetting() {
        LoopperMapper mapper = mock(LoopperMapper.class);
        when(mapper.findAppSettings()).thenReturn(Optional.empty());
        OpenCodeModelCatalogService catalog = mock(OpenCodeModelCatalogService.class);
        when(catalog.discover("opencode")).thenReturn(List.of(
                new OpenCodeModelCatalogService.AvailableModel(
                        "deepseek/deepseek-chat", "deepseek", "deepseek-chat", "DeepSeek")));
        SettingsPersistence persistence = mock(SettingsPersistence.class);
        when(persistence.save(any(AppSettingsRow.class), any(StartupSettingsFile.Prepared.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        StartupSettingsFile startupFile = mock(StartupSettingsFile.class);
        when(startupFile.path()).thenReturn(java.nio.file.Path.of("/tmp/startup-overrides.properties"));
        when(startupFile.prepare(any())).thenReturn(mock(StartupSettingsFile.Prepared.class));
        LoopperProperties properties = new LoopperProperties();
        SettingsService service = new SettingsService(
                mapper, properties, catalog, persistence, startupFile, new ObjectMapper());

        SettingsService.AppSettings saved = service.save(new SettingsService.AppSettings(
                new SettingsService.RuntimeSettings(8080, true, "", 2, 750, 3),
                new SettingsService.OpenCodeSettings("opencode", "managed", "http://127.0.0.1:4096",
                        "deepseek", "deepseek-chat", 5, 30, 15),
                new SettingsService.LimitSettings(3, 9, 3, 120, 25, 10, 30),
                new SettingsService.RetryWaitSettings(60, 300, 10, 60, 5, 30),
                new SettingsService.PublicationSettings(List.of("gitlab.spdb.com"), "gitlab.spdb.com",
                        "http://gitlab.spdb.com/api/v4", 3, 10), null, List.of(), List.of(), null));

        assertThat(saved.openCode().mode()).isEqualTo("managed");
        assertThat(properties.getOpenCode().getMode()).isEqualTo("managed");
        verify(startupFile).prepare(org.mockito.ArgumentMatchers.argThat(
                values -> "managed".equals(values.get("LOOPPER_OPENCODE_MODE"))));
    }

    @Test
    void persistsAndAppliesASelectedModelToFutureSessions() {
        LoopperMapper mapper = mock(LoopperMapper.class);
        OpenCodeModelCatalogService catalog = mock(OpenCodeModelCatalogService.class);
        SettingsPersistence persistence = mock(SettingsPersistence.class);
        StartupSettingsFile startupFile = mock(StartupSettingsFile.class);
        StartupSettingsFile.Prepared prepared = mock(StartupSettingsFile.Prepared.class);
        LoopperProperties properties = new LoopperProperties();
        when(mapper.findAppSettings()).thenReturn(Optional.empty());
        when(catalog.discover("opencode")).thenReturn(List.of(
                new OpenCodeModelCatalogService.AvailableModel("deepseek/deepseek-chat", "deepseek", "deepseek-chat", "deepseek / deepseek-chat")));
        when(startupFile.path()).thenReturn(java.nio.file.Path.of("/tmp/startup-overrides.properties"));
        when(startupFile.prepare(any())).thenReturn(prepared);
        when(persistence.save(any(AppSettingsRow.class), any(StartupSettingsFile.Prepared.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        SettingsService service = new SettingsService(mapper, properties, catalog, persistence, startupFile, new ObjectMapper());

        SettingsService.AppSettings saved = service.save(new SettingsService.AppSettings(
                new SettingsService.RuntimeSettings(8080, true, "", 2, 750, 3),
                new SettingsService.OpenCodeSettings("opencode", "auto", "http://127.0.0.1:4096",
                        "deepseek", "deepseek-chat", 5, 30, 15),
                new SettingsService.LimitSettings(3, 9, 3, 120, 25, 10, 30),
                new SettingsService.RetryWaitSettings(60, 300, 10, 60, 5, 30),
                new SettingsService.PublicationSettings(List.of("gitlab.spdb.com"), "gitlab.spdb.com",
                        "http://gitlab.spdb.com/api/v4", 3, 10), null, List.of(), List.of(), null));

        ArgumentCaptor<AppSettingsRow> row = ArgumentCaptor.forClass(AppSettingsRow.class);
        verify(persistence).save(row.capture(), any(StartupSettingsFile.Prepared.class));
        assertThat(row.getValue().modelId()).isEqualTo("deepseek-chat");
        assertThat(saved.openCode().model()).isEqualTo("deepseek-chat");
        assertThat(properties.getOpenCode().getModel()).isEqualTo("deepseek/deepseek-chat");
        assertThat(properties.getMaxTaskAttempts()).isEqualTo(9);
        assertThat(properties.getAttemptTimeout()).hasMinutes(25);
        assertThat(properties.getRetryWait().getRateLimitBase()).hasSeconds(60);
        verify(startupFile).prepare(org.mockito.ArgumentMatchers.argThat(values ->
                values.containsKey("LOOPPER_RETRY_RATE_LIMIT_BASE")
                        && values.keySet().stream().noneMatch(key -> key.contains("TOKEN")
                        || key.contains("PASSWORD") || key.contains("JAVA_HOME") || key.contains("JAR_PATH")
                        || key.equals("LOOPPER_DATA_DIR"))));
    }

    @Test
    void doesNotOverrideStartupEnvironmentWithACompletePersistedSnapshot() {
        LoopperMapper mapper = mock(LoopperMapper.class);
        LoopperProperties properties = new LoopperProperties();
        properties.setMaxTaskAttempts(7);
        when(mapper.findAppSettings()).thenReturn(Optional.of(new AppSettingsRow(1, "opencode", "",
                "saved", "model", 40, 90, 0, "{\"runtime\":{}}", "2026-08-18T00:00:00Z")));
        StartupSettingsFile startupFile = mock(StartupSettingsFile.class);
        when(startupFile.path()).thenReturn(java.nio.file.Path.of("/tmp/startup-overrides.properties"));

        new SettingsService(mapper, properties, mock(OpenCodeModelCatalogService.class),
                mock(SettingsPersistence.class), startupFile, new ObjectMapper());

        assertThat(properties.getMaxTaskAttempts()).isEqualTo(7);
    }

    @Test
    void rejectsAnInvalidRetryMaximumBeforePersistingAnything() {
        LoopperMapper mapper = mock(LoopperMapper.class);
        when(mapper.findAppSettings()).thenReturn(Optional.empty());
        StartupSettingsFile startupFile = mock(StartupSettingsFile.class);
        when(startupFile.path()).thenReturn(java.nio.file.Path.of("/tmp/startup-overrides.properties"));
        SettingsPersistence persistence = mock(SettingsPersistence.class);
        SettingsService service = new SettingsService(mapper, new LoopperProperties(),
                mock(OpenCodeModelCatalogService.class), persistence, startupFile, new ObjectMapper());

        SettingsService.AppSettings invalid = new SettingsService.AppSettings(
                new SettingsService.RuntimeSettings(8080, true, "", 2, 750, 3),
                new SettingsService.OpenCodeSettings("opencode", "auto", "http://127.0.0.1:4096",
                        "deepseek", "deepseek-chat", 5, 30, 15),
                new SettingsService.LimitSettings(3, 9, 3, 120, 25, 10, 30),
                new SettingsService.RetryWaitSettings(60, 30, 10, 60, 5, 30),
                new SettingsService.PublicationSettings(List.of("gitlab.spdb.com"), "gitlab.spdb.com",
                        "http://gitlab.spdb.com/api/v4", 3, 10), null, List.of(), List.of(), null);

        assertThatThrownBy(() -> service.save(invalid))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Rate-limit retry maximum");
        verify(persistence, never()).save(any(), any());
        verify(startupFile, never()).prepare(any());
    }
}
