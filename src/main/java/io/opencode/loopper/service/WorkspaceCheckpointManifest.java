package io.opencode.loopper.service;

import io.opencode.loopper.persistence.TaskWorkspaceCheckpointRow;
import io.opencode.loopper.runtime.GitWorktreeManager;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/** Encodes Direct snapshot evidence and projects changes without treating index metadata as files. */
public final class WorkspaceCheckpointManifest {
    private WorkspaceCheckpointManifest() { }

    public static String direct(String index, int changedFileCount, ObjectMapper json) {
        if (changedFileCount < 0) throw new IllegalArgumentException("Changed file count must be known");
        return json.writeValueAsString(Map.of("gitIndexBase64",
                Base64.getEncoder().encodeToString(index.getBytes(StandardCharsets.UTF_8)),
                "changedFileCount", changedFileCount));
    }

    /** -1 means the frozen evidence cannot establish a count; it never authorizes no-change acceptance. */
    public static int changedFileCount(TaskWorkspaceCheckpointRow checkpoint, ObjectMapper json) {
        try {
            var manifest = json.readTree(checkpoint.manifestJson());
            if (!GitWorktreeManager.DIRECT_BRANCH.equals(checkpoint.branchName())) {
                return manifest.isArray() ? manifest.size() : -1;
            }
            String baseline = checkpoint.baselineCommit();
            String prefix = "direct:" + checkpoint.taskId() + ":";
            if (baseline == null || !baseline.startsWith(prefix)
                    || !baseline.substring(prefix.length()).matches("[0-9a-fA-F]{40,64}")
                    || checkpoint.checkpointTree() == null
                    || !checkpoint.checkpointTree().matches("[0-9a-fA-F]{40,64}")
                    || !manifest.isObject() || !manifest.path("gitIndexBase64").isString()) return -1;
            var count = manifest.get("changedFileCount");
            if (count != null) return count.isIntegralNumber() && count.canConvertToInt() && count.intValue() >= 0
                    ? count.intValue() : -1;
            // Legacy Direct checkpoints preserve complete trees but no change count. Equal immutable
            // tree identities prove zero changes; a different tree does not tell us how many files changed.
            return baseline.substring(prefix.length()).equals(checkpoint.checkpointTree()) ? 0 : -1;
        } catch (RuntimeException invalid) {
            return -1;
        }
    }
}
