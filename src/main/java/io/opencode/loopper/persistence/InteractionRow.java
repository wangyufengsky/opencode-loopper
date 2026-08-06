package io.opencode.loopper.persistence;

public record InteractionRow(String id, String scopeType, String scopeId, String taskId,
                             String designerSessionId, String localSessionId, String externalSessionId,
                             String externalRequestId, String kind, String state, String payloadJson,
                             String resolvedAction, String responseJson, String createdAt, String updatedAt,
                             String resolvedAt, long version) { }
