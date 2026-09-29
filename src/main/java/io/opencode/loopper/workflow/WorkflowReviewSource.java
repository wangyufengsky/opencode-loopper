package io.opencode.loopper.workflow;

import io.opencode.loopper.template.SnapshotReview;
import java.util.*;

/** Fixed final-tree review evidence; it grants neither write access nor a review verdict. */
public final class WorkflowReviewSource {
    public static final String MODULE = "system.review.snapshot", TYPE = "REVIEW_SOURCE";
    public static final int MAX_FILE_BYTES = 320_000_000;
    private WorkflowReviewSource() { }
    public record Reference(int version, String type, String snapshotId, String sha256) { }
    public record File(String path, long sizeBytes, String sha256) { }
    public record Manifest(int version, String type, String nodeRunId, String branchId, SnapshotReview.Mode mode,
            String sourceSha, String baselineSha, String targetSha, String projectPrefix, String startDate, String endDate,
            String capturedAt, boolean noChanges, boolean nonMonotonic, String evidenceSha256, int unitCount, int excludedCount, List<File> files) {
        public Manifest { files = List.copyOf(files); }
    }
    public static SnapshotReview.Mode mode(WorkflowGraph.Node node) {
        try { return SnapshotReview.Mode.valueOf(node.parameters().getOrDefault("reviewMode", "DATE_INCREMENTAL")); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("请选择日期增量或全面审查范围。"); }
    }
    public static int require(WorkflowGraph.Node node) {
        var mode = mode(node); var names = mode == SnapshotReview.Mode.FULL ? Set.of("branch") : Set.of("branch", "startDate", "endDate");
        if (node.kind() != WorkflowGraph.NodeKind.SYSTEM || !MODULE.equals(node.moduleId()) || node.moduleVersion() != 1
                || node.roleId() != null || node.roleRevisionId() != null || node.completion() == null
                || !Set.of(WorkflowGraph.CompletionKind.VERIFIED, WorkflowGraph.CompletionKind.DELIVERABLES).contains(node.completion().kind())
                || !node.outcomes().isEmpty() || node.inputs().size() != names.size()
                || !node.inputs().stream().map(WorkflowGraph.Input::name).collect(java.util.stream.Collectors.toSet()).equals(names)
                || node.inputs().stream().anyMatch(i -> i.kind() != WorkflowGraph.DataKind.TEXT || !i.required()) || node.outputs().size() != 3
                || node.outputs().stream().noneMatch(o -> o.name().equals("source") && o.kind() == WorkflowGraph.DataKind.DOCUMENT && !o.required())
                || node.outputs().stream().noneMatch(o -> o.name().equals("report") && o.kind() == WorkflowGraph.DataKind.JSON && o.required())
                || node.outputs().stream().noneMatch(o -> o.name().equals("summary") && o.kind() == WorkflowGraph.DataKind.TEXT && o.required()))
            throw new IllegalArgumentException("版本审查采集需要明确分支、所选模式的日期输入，以及固定资料、采集报告和说明。");
        try { int seconds = Integer.parseInt(node.parameters().getOrDefault("reviewTimeoutSeconds", "600")); if (seconds < 10 || seconds > 600) throw new IllegalArgumentException(); return seconds; }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("版本审查采集时限应为 10–600 秒。"); }
    }
}
