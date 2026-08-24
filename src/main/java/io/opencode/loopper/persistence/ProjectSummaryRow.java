package io.opencode.loopper.persistence;

public record ProjectSummaryRow(
        String id, String name, String rootPath, String description, String updatedAt,
        int taskCount, int openDesignerSessionCount, String stackProfileState,
        String stackTechnologyFamiliesJson, int stackComponentCount, String stackAnalyzedAt) { }
