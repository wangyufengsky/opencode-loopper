package io.opencode.loopper.persistence;

/** Persisted agent facts; runtime grants are deliberately absent. */
public final class PptAgentRows {
    private PptAgentRows() { }
    public record Run(String id, String documentId, String idempotencyKey, String inputSha,
                      String userText, String scopeJson, long sourceRevision, String phase, String modelJson,
                      String rootPath, String contextJson, String state, String detail, String answer,
                      String planJson, String externalSessionId, String generation, String messageId,
                      String requestJson, String requestSha, int createDispatched, int round, String stopReason, String stopProof,
                      Long inputTokens, Long outputTokens, String createdAt, String updatedAt, long version) { }
    public record Question(String id, String runId, String documentId, String prompt, String optionsJson,
                           String state, String answer, String replyKey, String replySha, String createdAt, long version) { }
    public record Receipt(String runId, String idempotencyKey, String tool, String inputSha, String responseJson, String createdAt) { }
}
