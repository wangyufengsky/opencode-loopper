package io.opencode.loopper.persistence;

public record VerificationSummaryRow(
        String id, String attemptId, int verifierIndex, String type, String state,
        String summary, String evidenceSummaryJson, String createdAt) { }
