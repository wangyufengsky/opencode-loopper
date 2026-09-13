package io.opencode.loopper.service;

import io.opencode.loopper.persistence.TemplateTaskProgressRow;
import java.nio.file.Path;

/** A read projection, never an authority for Task completion or a prediction of future retries. */
public record TemplateTaskProgress(Integer reviewBatches, Integer contributorBatches, int completedReviews,
        int completedContributors, int activeBatches, int failedBatches, int repairRound, String documentPath, boolean dualReviewRequired, int reportCount, java.util.List<Step> steps, String currentPhase) {
    public record Step(String key, String label, String state) { }
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
                io.opencode.loopper.template.TemplateTaskDefinition.requiresDualReview(row.templateVersion()), row.reportCount(), flow.steps(), flow.currentPhase());
    }
}
