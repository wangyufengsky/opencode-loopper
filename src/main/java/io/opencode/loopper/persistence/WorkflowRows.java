package io.opencode.loopper.persistence;

/** Summary rows deliberately exclude executable graph bodies. */
public final class WorkflowRows {
    private WorkflowRows() { }
    public record Template(String id, String title, String description, boolean builtin, boolean archived,
                           int headRevision, long version, String layoutJson, long layoutVersion,
                           String sourceTemplateId, Integer sourceRevision, String createdAt, String updatedAt) { }
    public record TemplateSummary(String id, String title, String description, boolean builtin,
                                  int headRevision, long version, String createdAt, String updatedAt) { }
    public record Revision(String ownerId, int revision, String definitionJson, String sha256, String createdAt) { }
    public record Requirement(String id, String projectId, String title, String objective, String state,
                              int headRevision, long version, String sourceTemplateId, int sourceRevision,
                              String layoutJson, long layoutVersion, String createdAt, String updatedAt) { }
    public record RequirementSummary(String id, String projectId, String title, String state,
                                     int headRevision, long version, String createdAt, String updatedAt) { }
    public record Command(String requestKey, String requestSha256, String entityType, String entityId,
                          String action, String receiptJson, String createdAt) { }
}
