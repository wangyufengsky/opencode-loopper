package io.opencode.loopper.persistence;

public record DocumentTemplateRunRow(String id, String requestKey, String requestSha256, String projectId,
        String templateId, String templateVersion, String title, String state, String resumeState,
        String branchJson, String snapshotJson, String contractJson, String designerId, String taskId,
        int requirementRevision, String waitingReasonCode, String waitingMessage, int archived,
        String createdAt, String updatedAt, long version, int sourceRevision) {
    @org.apache.ibatis.annotations.AutomapConstructor
    public DocumentTemplateRunRow { }
    public boolean directDocuments() { return "2".equals(templateVersion); }
    public int basisRevision() { return directDocuments() ? sourceRevision : requirementRevision; }
    public DocumentTemplateRunRow(String id, String requestKey, String requestSha256, String projectId,
            String templateId, String templateVersion, String title, String state, String resumeState,
            String branchJson, String snapshotJson, String contractJson, String designerId, String taskId,
            int requirementRevision, String waitingReasonCode, String waitingMessage, int archived,
            String createdAt, String updatedAt, long version) {
        this(id,requestKey,requestSha256,projectId,templateId,templateVersion,title,state,resumeState,branchJson,
                snapshotJson,contractJson,designerId,taskId,requirementRevision,waitingReasonCode,waitingMessage,
                archived,createdAt,updatedAt,version,0);
    }
}
