package io.opencode.loopper.lifecycle;

import io.opencode.loopper.domain.LifecycleMachineType;

public final class PersistedStateInvalidException extends RuntimeException {
    public PersistedStateInvalidException(LifecycleMachineType machine, String entityId, String state) {
        super("Stored state is invalid for " + machine.name() + " entity " + entityId + ": " + state);
    }
}
