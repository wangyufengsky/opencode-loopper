package io.opencode.loopper.persistence;
public record TaskEventRow(String id, String taskId, long sequence, String type, String payloadJson,
                           String occurredAt) { }
