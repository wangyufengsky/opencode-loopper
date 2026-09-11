package io.opencode.loopper.persistence;

public record TemplateTaskRunRow(String taskId, String requestKey, String requestSha256,
                                 String templateId, String templateVersion, String branchId, String branchLabel,
                                 String branchRef, String remoteName, String startDate, String endDate,
                                 String contractJson, String snapshotJson, String snapshotSha256,
                                 int repairRound, int bypassCache, String createdAt, String updatedAt, long version) { }
