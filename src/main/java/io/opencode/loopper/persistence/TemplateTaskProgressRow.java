package io.opencode.loopper.persistence;

/** Current report attempt only. Totals exclude retries and optional future repair rounds. */
public record TemplateTaskProgressRow(Integer reviewBatches, Integer contributorBatches,
        int completedReviews, int completedContributors, int activeBatches, int failedBatches,
        int repairRound, String documentPath, String reportAttemptId) { }
