package io.opencode.loopper.persistence;

/** Durable identity of one reusable, read-only design cycle. */
public record DesignerConversationRow(String id, String designerSessionId, String scopeKey, int generation,
        String externalSessionId, String runtimeGenerationId, String internalMcpServer, String rootPath,
        String profile, String modelJson, String state, String reason, String createdAt, String updatedAt,
        long version) { }
