package io.opencode.loopper.persistence;

public record SessionTodoRow(String id, String executionSessionId, String externalTodoId,
                             String content, String status, String priority, int ordinal,
                             String payloadJson, String observedAt, long version) { }
