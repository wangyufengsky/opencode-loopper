package io.opencode.loopper.domain;

public enum InteractionAction {
    REPLY, ONCE, SESSION, REJECT;

    public boolean allowedFor(InteractionKind kind) {
        return kind == InteractionKind.QUESTION
                ? this == REPLY || this == REJECT
                : this == ONCE || this == SESSION || this == REJECT;
    }
}
