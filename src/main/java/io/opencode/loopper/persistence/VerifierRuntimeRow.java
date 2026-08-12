package io.opencode.loopper.persistence;

public record VerifierRuntimeRow(String id, String taskId, String stageId, String attemptId,
                                 String state, Long pid, String processStartInstant, Integer port,
                                 String argvSha256, String resolvedArgvJson, String tempDir,
                                 String evidenceJson, String createdAt, String updatedAt,
                                 String endedAt, long version) { }
