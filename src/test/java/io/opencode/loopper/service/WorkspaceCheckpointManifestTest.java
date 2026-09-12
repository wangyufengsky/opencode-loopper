package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.opencode.loopper.persistence.TaskWorkspaceCheckpointRow;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class WorkspaceCheckpointManifestTest {
    private final ObjectMapper json = new ObjectMapper();
    private static final String BASE = "a".repeat(40);
    private static final String CHANGED = "b".repeat(40);

    @Test void directCountsDescribeChangesRatherThanIndexMetadata() {
        String encoded = WorkspaceCheckpointManifest.direct("100644 blob 0\tREADME.md\0", 0, json);
        assertThat(WorkspaceCheckpointManifest.changedFileCount(checkpoint("DIRECT", BASE, encoded), json)).isZero();
        assertThat(WorkspaceCheckpointManifest.changedFileCount(checkpoint("DIRECT", CHANGED,
                WorkspaceCheckpointManifest.direct("index", 3, json)), json)).isEqualTo(3);
    }

    @Test void legacyDirectTreesOnlyProveZeroWhenTheyMatchTheOriginalBaseline() {
        String legacy = "{\"gitIndexBase64\":\"aW5kZXg=\"}";
        assertThat(WorkspaceCheckpointManifest.changedFileCount(checkpoint("DIRECT", BASE, legacy), json)).isZero();
        assertThat(WorkspaceCheckpointManifest.changedFileCount(checkpoint("DIRECT", CHANGED, legacy), json)).isEqualTo(-1);
    }

    @Test void malformedAndUnknownEvidenceNeverMeansNoChanges() {
        for (String manifest : new String[] {"null", "[]", "{}", "not json",
                "{\"gitIndexBase64\":\"\",\"changedFileCount\":-1}",
                "{\"gitIndexBase64\":\"\",\"changedFileCount\":0.5}",
                "{\"gitIndexBase64\":\"\",\"changedFileCount\":4294967296}"}) {
            assertThat(WorkspaceCheckpointManifest.changedFileCount(checkpoint("DIRECT", BASE, manifest), json))
                    .as(manifest).isEqualTo(-1);
        }
        assertThat(WorkspaceCheckpointManifest.changedFileCount(checkpoint("loopper/task", BASE, "[]"), json)).isZero();
        assertThat(WorkspaceCheckpointManifest.changedFileCount(checkpoint("loopper/task", BASE, "{}"), json)).isEqualTo(-1);
    }

    private TaskWorkspaceCheckpointRow checkpoint(String branch, String tree, String manifest) {
        return new TaskWorkspaceCheckpointRow("checkpoint", "task", "cycle", "READY", "snapshot",
                "root", "fingerprint", branch, null, "direct:task:" + BASE, "ref", null, tree,
                manifest, "sha", null, null, null, "now", "now", 1);
    }
}
