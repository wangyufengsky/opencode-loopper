package io.opencode.loopper.persistence;
public record LoopDraftRow(String id, String projectId, String goal, String specJson, String status,
                           String createdAt, String updatedAt, long version) { }
