package io.opencode.loopper.persistence;
public record VerificationResultRow(String id, String attemptId, int verifierIndex, String type, String state,
                                    String summary, String evidenceJson, String createdAt) { }
