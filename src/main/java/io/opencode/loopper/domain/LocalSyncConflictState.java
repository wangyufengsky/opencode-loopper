package io.opencode.loopper.domain;

public enum LocalSyncConflictState {
    OPEN,
    READY,
    APPLYING,
    VERIFYING,
    APPLIED,
    STALE,
    ROLLED_BACK,
    ROLLBACK_FAILED
}
