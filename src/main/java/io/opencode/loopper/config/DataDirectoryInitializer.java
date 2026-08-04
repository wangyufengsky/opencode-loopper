package io.opencode.loopper.config;

import java.nio.file.Files;
import org.springframework.stereotype.Component;

@Component
class DataDirectoryInitializer {
    DataDirectoryInitializer(LoopperProperties properties) {
        try { Files.createDirectories(properties.getDataDir().toAbsolutePath().normalize()); }
        catch (Exception e) { throw new IllegalStateException("Unable to create Loopper data directory", e); }
    }
}
