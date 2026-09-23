package io.opencode.loopper.persistence;

public record SourceTemplateModelRow(String id, String runId, String candidateKind, int ordinal,
        int generation, int attempt, String state, String inputJson, String inputSha256,
        String creationPlanJson, String externalSessionId, String promptJson, String promptSha256,
        String outputJson, String outputSha256, String acceptedAt, String errorCode,
        String createdAt, String updatedAt, long version, String startedAt) { }
