package io.opencode.loopper.persistence;

public record TaskQueueRow(String taskId, String canonicalRoot, String rootFingerprint, long position,
                           String source, String state, String enqueuedAt, String admittedAt,
                           String finishedAt, long version) { }
