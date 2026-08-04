package io.opencode.loopper.domain;

public enum TaskState {
    QUEUED, PREPARING, READY, RUNNING, VERIFYING, RETRY_WAIT, PAUSED, WAITING_INPUT,
    JUDGING, SUCCEEDED, FAILED, CANCELLED;

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
