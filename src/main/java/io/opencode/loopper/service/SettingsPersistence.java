package io.opencode.loopper.service;

import io.opencode.loopper.persistence.AppSettingsRow;
import io.opencode.loopper.persistence.LoopperMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Commits validated settings in a short database-only transaction. */
@Service
public class SettingsPersistence {
    private final LoopperMapper mapper;

    public SettingsPersistence(LoopperMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public AppSettingsRow save(AppSettingsRow row) {
        if (mapper.upsertAppSettings(row) != 1) {
            throw new ConflictException("SETTINGS_SAVE_CONFLICT", "Settings could not be persisted");
        }
        return row;
    }
}
