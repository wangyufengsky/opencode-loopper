package io.opencode.loopper.lifecycle;

import io.opencode.loopper.domain.LifecycleMachineType;

public final class InvalidStateTransitionException extends RuntimeException {
    public InvalidStateTransitionException(LifecycleMachineType machine, String current, String event, String target) {
        super(machine.name() + " cannot apply " + event + " from " + current
                + (target == null ? "" : " to " + target));
    }
}
