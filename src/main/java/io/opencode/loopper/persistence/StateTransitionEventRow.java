package io.opencode.loopper.persistence;

public record StateTransitionEventRow(long sequence, String id, String machineType, String entityId,
                                      String scopeType, String scopeId, String event, String fromState,
                                      String toState, String reasonCode, String metadataJson,
                                      String occurredAt) { }
