package io.opencode.loopper.persistence;

/** The append-only messages for a DesignerSession.  SYSTEM entries are handoff notices, never model output. */
public record DesignerMessageRow(String id, String designerSessionId, int ordinal, String role, String content,
                                 String deliveryState, String createdAt) { }
