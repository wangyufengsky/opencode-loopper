package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opencode.loopper.persistence.AppSettingsRow;
import io.opencode.loopper.persistence.LoopperMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

class SettingsPersistenceTest {
    @Test
    void rollsBackTheDatabaseAndRestoresTheOldFileWhenFileActivationFails() {
        LoopperMapper mapper = mock(LoopperMapper.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transaction = mock(TransactionStatus.class);
        StartupSettingsFile.Prepared prepared = mock(StartupSettingsFile.Prepared.class);
        AppSettingsRow row = new AppSettingsRow(1, "opencode", "", "provider", "model", 9, 25,
                0, "{}", "2026-08-18T00:00:00Z");
        when(transactionManager.getTransaction(any())).thenReturn(transaction);
        when(mapper.upsertAppSettings(row)).thenReturn(1);
        doThrow(new ServiceUnavailableException("STARTUP_SETTINGS_WRITE_FAILED", "failed"))
                .when(prepared).commit();

        SettingsPersistence persistence = new SettingsPersistence(mapper, transactionManager);

        assertThatThrownBy(() -> persistence.save(row, prepared))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("failed");
        verify(transactionManager).rollback(transaction);
        verify(prepared).rollback();
    }

    @Test
    void restoresTheOldFileWhenTheDatabaseCommitFailsAfterFileActivation() {
        LoopperMapper mapper = mock(LoopperMapper.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transaction = mock(TransactionStatus.class);
        StartupSettingsFile.Prepared prepared = mock(StartupSettingsFile.Prepared.class);
        AppSettingsRow row = new AppSettingsRow(1, "opencode", "", "provider", "model", 9, 25,
                0, "{}", "2026-08-18T00:00:00Z");
        when(transactionManager.getTransaction(any())).thenReturn(transaction);
        when(mapper.upsertAppSettings(row)).thenReturn(1);
        doThrow(new IllegalStateException("database commit failed")).when(transactionManager).commit(transaction);

        SettingsPersistence persistence = new SettingsPersistence(mapper, transactionManager);

        assertThatThrownBy(() -> persistence.save(row, prepared))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("database commit failed");
        verify(prepared).commit();
        verify(prepared, times(1)).rollback();
    }
}
