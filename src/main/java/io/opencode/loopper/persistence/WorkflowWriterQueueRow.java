package io.opencode.loopper.persistence;

public record WorkflowWriterQueueRow(String attemptId, String requirementId, String projectId, String canonicalRoot,
        String rootFingerprint, long position, String state, String enqueuedAt, String admittedAt, String finishedAt, long version) { }
