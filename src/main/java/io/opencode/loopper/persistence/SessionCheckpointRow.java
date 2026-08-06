package io.opencode.loopper.persistence;

public record SessionCheckpointRow(String id, String taskId, String executionSessionId, String attemptId,
                                   String externalMessageId, String messageRefsJson, String todoRefsJson,
                                   String diffRefJson, String contentSha256, String createdAt, long version) { }
