package io.opencode.loopper.persistence;
public record StageRow(String id, String taskId, int ordinal, String objective, String allowedPathsJson,
                       String forbiddenPathsJson, String deliverablesJson, String verifiersJson,
                       String state, String createdAt, String updatedAt, long version) { }
