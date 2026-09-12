package io.opencode.loopper.persistence;

public record TemplateReportBundleRow(String attemptId, String taskId, String namespaceKey, long sequence,
                                      String projectName, String folderName, String mainPath) { }
