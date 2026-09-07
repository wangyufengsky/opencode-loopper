package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PackageDesignLunaCorpusTest {
    @Test void freezesBalancedLabelsDisjointFamiliesAndIndependentSemanticChecklists() throws Exception {
        var root = new ObjectMapper().readTree(Files.readString(Path.of("src/test/resources/package-design-luna/corpus.json")));
        var cases = root.path("cases");
        assertThat(cases.size()).isEqualTo(36);
        Map<String, Integer> splits = new HashMap<>(), labels = new HashMap<>();
        Map<String, String> families = new HashMap<>();
        var ids = new HashSet<String>();
        var goldIds = new HashSet<String>();
        for (var item : cases) {
            assertThat(ids.add(item.path("id").asText())).isTrue();
            String split = item.path("split").asText(), family = item.path("family").asText();
            String prior = families.putIfAbsent(family, split);
            assertThat(prior == null || prior.equals(split)).as("family leakage: %s", family).isTrue();
            splits.merge(split, 1, Integer::sum);
            labels.merge(item.path("expectedClass").asText(), 1, Integer::sum);
            assertThat(item.path("semanticChecklist").size()).isGreaterThanOrEqualTo(3);
            for (var gold : item.path("semanticChecklist")) {
                assertThat(goldIds.add(gold.path("id").asText())).isTrue();
                assertThat(gold.path("assertion").asText()).isNotBlank();
            }
        }
        assertThat(splits).isEqualTo(Map.of("train", 18, "dev", 6, "heldout", 12));
        assertThat(labels).isEqualTo(Map.of("RESOLVABLE", 24, "BUSINESS_DECISION", 6, "PROVEN_CONFLICT", 6));
    }

    @Test void isolatedHarnessBudgetAndReplayTestsRunWithoutAnyModel() throws Exception {
        var process = new ProcessBuilder("python3", "scripts/test-qualify-package-design-luna.py").redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(output).isZero();
        assertThat(output).contains("Ran 3 tests", "OK");
    }
}
