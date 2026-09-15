package io.opencode.loopper.persistence;

public record SnapshotReviewProgressRow(String mode, String targetSha, String baselineSha, int planRevision,
        String stages, int planning, int planned, int analyses, int analyzed, int reviews, int reviewed,
        int active, int failed, int supplements, int reportCount, String documentPath, String folder) { }
