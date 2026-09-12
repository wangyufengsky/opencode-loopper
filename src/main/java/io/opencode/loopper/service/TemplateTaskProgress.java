package io.opencode.loopper.service;

import io.opencode.loopper.persistence.TemplateTaskProgressRow;
import java.nio.file.Path;

/** A read projection, never an authority for Task completion or a prediction of future retries. */
public record TemplateTaskProgress(Integer reviewBatches, Integer contributorBatches, int completedReviews,
        int completedContributors, int activeBatches, int failedBatches, int repairRound, String documentPath) {
    public static TemplateTaskProgress from(TemplateTaskProgressRow row, String taskId, String workspace) {
        String path = row.documentPath();
        if (row.reportAttemptId() != null && (path != null || workspace != null)) {
            path = TemplateDocumentPaths.reportDirectory(path, taskId, row.reportAttemptId(),
                    workspace == null ? Path.of(".") : Path.of(workspace)).toString();
        }
        return new TemplateTaskProgress(row.reviewBatches(), row.contributorBatches(), row.completedReviews(),
                row.completedContributors(), row.activeBatches(), row.failedBatches(), row.repairRound(), path);
    }
}
