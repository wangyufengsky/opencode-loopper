package io.opencode.loopper.persistence;

public record DocumentTemplateModelRow(String id, String runId, String candidateKind, int ordinal,
        int generation, String state, String inputJson, String inputSha256, String creationPlanJson,
        String externalSessionId, String promptJson, String promptSha256, String outputJson,
        String outputSha256, String errorCode, String createdAt, String updatedAt, long version, int attempt) {
    @org.apache.ibatis.annotations.AutomapConstructor
    public DocumentTemplateModelRow { }
    public DocumentTemplateModelRow(String id, String runId, String candidateKind, int ordinal, int generation,
            String state, String inputJson, String inputSha256, String creationPlanJson, String externalSessionId,
            String promptJson, String promptSha256, String outputJson, String outputSha256, String errorCode,
            String createdAt, String updatedAt, long version) {
        this(id, runId, candidateKind, ordinal, generation, state, inputJson, inputSha256, creationPlanJson,
                externalSessionId, promptJson, promptSha256, outputJson, outputSha256, errorCode, createdAt, updatedAt, version, 0);
    }
}
