package io.opencode.loopper.persistence;

/** Request identity is recorded before any potentially observable prompt dispatch. */
public record DesignerConversationTurnRow(String id, String conversationId, String messageId, String phase,
        String candidateRunId, String requestJson, String requestSha256, String state,
        String createdAt, String updatedAt, long version) { }
