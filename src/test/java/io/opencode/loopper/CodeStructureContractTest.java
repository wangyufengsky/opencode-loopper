package io.opencode.loopper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CodeStructureContractTest {
    private static final int DEFAULT_MAX_LINES = 600;

    /**
     * Temporary debt caps. A refactor may lower a cap but must never raise one.
     * Files leave this map as soon as they are at or below the default limit.
     */
    private static final Map<String, Integer> LEGACY_RATCHET = Map.of(
            "io/opencode/loopper/service/DesignerSessionService.java", 5_406,
            "io/opencode/loopper/service/TaskService.java", 2_728,
            "io/opencode/loopper/service/LocalSyncConflictService.java", 1_160);

    @Test
    void productionJavaFilesRespectDefaultLimitOrLegacyRatchet() throws IOException {
        Path sourceRoot = Path.of("src/main/java");
        Map<String, Integer> violations = new LinkedHashMap<>();
        try (var files = Files.walk(sourceRoot)) {
            files.filter(path -> path.toString().endsWith(".java")).sorted().forEach(path -> {
                String relative = sourceRoot.relativize(path).toString().replace('\\', '/');
                int limit = LEGACY_RATCHET.getOrDefault(relative, DEFAULT_MAX_LINES);
                int lines = lineCount(path);
                if (lines > limit) violations.put(relative, lines);
            });
        }

        assertThat(violations)
                .as("Production classes must stay under 600 lines; legacy debt may only shrink")
                .isEmpty();
    }

    @Test
    void legacyRatchetContainsOnlyExistingOversizedFiles() throws IOException {
        Path sourceRoot = Path.of("src/main/java");
        Map<String, Integer> stale = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : LEGACY_RATCHET.entrySet()) {
            Path path = sourceRoot.resolve(entry.getKey());
            int lines = Files.exists(path) ? lineCount(path) : -1;
            if (lines <= DEFAULT_MAX_LINES || lines > entry.getValue()) stale.put(entry.getKey(), lines);
        }

        assertThat(stale)
                .as("Remove files at or below the default limit and never raise a legacy cap")
                .isEmpty();
    }

    private static int lineCount(Path path) {
        try (var lines = Files.lines(path)) {
            return Math.toIntExact(lines.count());
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to read " + path, failure);
        }
    }
}
