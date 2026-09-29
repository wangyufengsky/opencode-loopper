package io.opencode.loopper.workflow;

import java.util.List;

/** Complete project tree plus baseline-relative changes; never a pointer to a mutable directory. */
public record WorkflowCodeSnapshot(int version, String snapshotId, String projectId, String requirementId,
        String attemptId, String inputsSha256, String baseTree, String resultTree, String projectPrefix,
        List<File> files, List<Change> changes) {
    public WorkflowCodeSnapshot { files = List.copyOf(files); changes = List.copyOf(changes); }
    public record File(String path, String mode, String blob, String sha256, long sizeBytes) { }
    public record Change(String path, String kind, String beforeBlob, String afterBlob) { }
    public record Reference(int version, String snapshotId, String sha256) { }
}
