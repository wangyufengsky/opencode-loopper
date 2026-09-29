package io.opencode.loopper.workflow;

import java.util.List;

/** Frozen plain-directory inventory. The lease and preparation record own its identity and publication. */
public record WorkflowDirectorySnapshot(int version, String canonicalRoot, String rootFingerprint,
                                        List<WorkflowCodeSnapshot.File> files) {
    public WorkflowDirectorySnapshot { files = List.copyOf(files); }
}
