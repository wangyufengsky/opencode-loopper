package io.opencode.loopper.persistence;

public record TemplateTaskBatchRow(String id, String taskId, String attemptId, String sessionId,
                                   int ordinal, String purpose, String inputJson, String inputSha256, String state,
                                   String creationPlanJson, String promptJson, String promptSha256, String outputJson,
                                   String errorCode, String errorMessage, String createdAt, String updatedAt, long version, int generation) {
    @org.apache.ibatis.annotations.AutomapConstructor
    public TemplateTaskBatchRow { }
    public TemplateTaskBatchRow(String id, String taskId, String attemptId, String sessionId, int ordinal, String purpose,
            String inputJson, String inputSha256, String state, String creationPlanJson, String promptJson, String promptSha256,
            String outputJson, String errorCode, String errorMessage, String createdAt, String updatedAt, long version) {
        this(id, taskId, attemptId, sessionId, ordinal, purpose, inputJson, inputSha256, state, creationPlanJson, promptJson,
                promptSha256, outputJson, errorCode, errorMessage, createdAt, updatedAt, version, 0);
    }
}
