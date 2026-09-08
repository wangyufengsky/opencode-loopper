package io.opencode.loopper.persistence;

public record JudgeSummaryRow(String id, String role, int ordinal, String state, String verdict, String reason,
                              String externalSessionId, String createdAt, String endedAt, int hasRawOutput) { }
