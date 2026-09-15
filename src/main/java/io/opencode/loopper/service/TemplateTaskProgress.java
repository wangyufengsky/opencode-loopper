package io.opencode.loopper.service;

import io.opencode.loopper.persistence.TemplateTaskProgressRow;
import java.nio.file.Path;

/** A read projection, never an authority for Task completion or a prediction of future retries. */
public record TemplateTaskProgress(Integer reviewBatches, Integer contributorBatches, int completedReviews,
        int completedContributors, int activeBatches, int failedBatches, int repairRound, String documentPath, boolean dualReviewRequired, int reportCount, java.util.List<Step> steps, String currentPhase, SnapshotStatus snapshot) {
    public record Step(String key, String label, String state) { }
    public record Phase(String label, int total, int completed) { }
    public record SnapshotStatus(String mode, String targetSha, String baselineSha, int planRevision, int supplements, java.util.List<Phase> phases) { }
    public static TemplateTaskProgress snapshot(io.opencode.loopper.persistence.SnapshotReviewProgressRow row, String taskId, String workspace, String state) {
        String[] statuses = row.stages().split(",");
        String[] labels = {"冻结证据", "规划范围", "功能分析", "独立复核与报告"};
        String[] keys = {"SNAPSHOT", "PLAN", "ANALYSIS", "SNAPSHOT_REVIEW"};
        var steps = new java.util.ArrayList<Step>(); String current = "COMPLETE";
        for (int i = 0; i < statuses.length; i++) {
            boolean done = statuses[i].equals("SUCCEEDED");
            if (!done && current.equals("COMPLETE")) current = keys[i];
            steps.add(new Step(keys[i], labels[i], done ? "COMPLETE" : statuses[i].equals("RUNNING") ? "ACTIVE" : "PENDING"));
        }
        String path = row.documentPath();
        if (row.folder() != null && workspace != null) path = TemplateDocumentPaths.bundleDirectory(path, row.folder(), Path.of(workspace)).toString();
        var phases = java.util.List.of(new Phase("功能规划与衔接", row.planning(), row.planned()),
                new Phase("功能与补充分析", row.analyses(), row.analyzed()), new Phase("独立复核", row.reviews(), row.reviewed()));
        return new TemplateTaskProgress((row.targetSha() == null || row.planning() == 0 && !statuses[1].equals("SUCCEEDED")) ? null : row.planning() + row.analyses(), row.reviews(), row.planned() + row.analyzed(),
                row.reviewed(), row.active(), row.failed(), 0, path, false, row.reportCount(), steps, current,
                new SnapshotStatus(row.mode(), row.targetSha(), row.baselineSha(), row.planRevision(), row.supplements(), phases));
    }
    public static TemplateTaskProgress from(TemplateTaskProgressRow row, String taskId, String workspace, String taskState) {
        String path = row.documentPath();
        if (row.reportAttemptId() != null && (path != null || workspace != null)) {
            Path root = workspace == null ? Path.of(".") : Path.of(workspace);
            path = (row.reportFolderName() == null ? TemplateDocumentPaths.reportDirectory(path, taskId, row.reportAttemptId(), root)
                    : TemplateDocumentPaths.bundleDirectory(path, row.reportFolderName(), root)).toString();
        }
        var flow = TemplateProgressFlow.project(row, taskState);
        return new TemplateTaskProgress(row.reviewBatches(), row.contributorBatches(), row.completedReviews(),
                row.completedContributors(), row.activeBatches(), row.failedBatches(), row.repairRound(), path,
                io.opencode.loopper.template.TemplateTaskDefinition.requiresDualReview(row.templateVersion()), row.reportCount(), flow.steps(), flow.currentPhase(), null);
    }
}
