package io.opencode.loopper.service;

import io.opencode.loopper.persistence.AppSettingsRow;
import io.opencode.loopper.persistence.LoopperMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Commits validated settings and its prepared startup mirror as one rollback-aware operation. */
@Service
public class SettingsPersistence {
    private final LoopperMapper mapper;
    private final TransactionTemplate transactions;

    public SettingsPersistence(LoopperMapper mapper, PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public AppSettingsRow save(AppSettingsRow row, StartupSettingsFile.Prepared prepared) {
        try {
            transactions.executeWithoutResult(status -> {
                if (mapper.upsertAppSettings(row) != 1) {
                    throw new ConflictException("SETTINGS_SAVE_CONFLICT", "Settings could not be persisted");
                }
                prepared.commit();
            });
            return row;
        } catch (RuntimeException failure) {
            try {
                prepared.rollback();
            } catch (RuntimeException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
    }
}
