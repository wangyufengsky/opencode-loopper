package io.opencode.loopper.persistence;

/** Durable knowledge identities; source paths and frozen credentials never belong in UI projections. */
public final class KnowledgeRows {
    private KnowledgeRows() { }
    public record Conversation(String id, String projectId, String rootPath, String title, String modelJson,
            String sourcesJson, String connectionsJson, String state, String remoteId, String planJson,
            String createdAt, String updatedAt, long version) { }
    public record Turn(String id, String conversationId, int ordinal, String idempotencyKey, String messageId,
            String state, String userText, String answer, String detail, String requestJson, String requestSha,
            Long inputTokens, Long outputTokens, String createdAt, String updatedAt, long version) { }
    public record Source(String id, String projectId, String kind, String name, String path, String sha256,
            String state, String detail, String createdAt, String updatedAt, long version) { }
    public record Citation(String id, String conversationId, String turnId, String kind, String sourceId,
            String name, String location, String sha256, String bodyJson, String createdAt) { }
    public record Call(String id, String conversationId, String turnId, String tool, String state,
            String detail, String createdAt, String updatedAt) { }
}
