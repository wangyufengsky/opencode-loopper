package io.opencode.loopper.persistence;

/** Heterogeneous audit metadata encoded by SQLite; large body columns are deliberately absent. */
public record TaskAuditEntryRow(String entryType, String payloadJson) { }
