package io.opencode.loopper.persistence;

public record WorkspaceLeaseRow(String canonicalRoot, String rootFingerprint, String mode,
                                String holderTaskId, String writerSessionId, String state,
                                String acquiredAt, String heartbeatAt, String releasedAt,
                                String releaseReason, long version) { }
