package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.opencode.loopper.config.LoopperProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StartupSettingsFileTest {
    @TempDir
    Path tempDir;

    @Test
    void atomicallyCommitsTheWhitelistMirrorAndCanRestoreThePreviousFile() throws Exception {
        LoopperProperties properties = new LoopperProperties();
        properties.setDataDir(tempDir);
        StartupSettingsFile file = new StartupSettingsFile(properties);
        Map<String, String> initial = new LinkedHashMap<>();
        initial.put("SERVER_PORT", "8087");
        initial.put("LOOPPER_RETRY_RATE_LIMIT_BASE", "60s");

        StartupSettingsFile.Prepared first = file.prepare(initial);
        assertThat(file.path()).doesNotExist();
        first.commit();
        assertThat(Files.readString(file.path()))
                .contains("SERVER_PORT=8087", "LOOPPER_RETRY_RATE_LIMIT_BASE=60s")
                .doesNotContain("TOKEN", "PASSWORD");

        StartupSettingsFile.Prepared replacement = file.prepare(Map.of("SERVER_PORT", "9090"));
        replacement.commit();
        assertThat(Files.readString(file.path())).contains("SERVER_PORT=9090").doesNotContain("SERVER_PORT=8087");
        replacement.rollback();

        assertThat(Files.readString(file.path()))
                .contains("SERVER_PORT=8087", "LOOPPER_RETRY_RATE_LIMIT_BASE=60s")
                .doesNotContain("SERVER_PORT=9090");
    }
}
