package io.opencode.loopper.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class OpenCodeAccountingAgentTest {
    @TempDir Path directory;

    @Test void installedAgentPreservesLastMatchingPermissionPrecedence() {
        String config = OpenCodeAccountingAgent.install("{\"agent\":{},\"plugin\":[\"operator-plugin\"]}", directory);
        var root = new ObjectMapper().readTree(config);
        String rules = root.path("agent").path("loopper-accounting").path("permission").toString();
        assertThat(rules).isEqualTo("{\"*\":\"deny\",\"aicoding*\":\"allow\"}");
        assertThat(root.path("plugin").toString()).contains("operator-plugin", "loopper-accounting-guard.mjs");
    }
}
