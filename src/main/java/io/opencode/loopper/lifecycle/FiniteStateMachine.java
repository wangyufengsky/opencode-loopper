package io.opencode.loopper.lifecycle;

import io.opencode.loopper.domain.DescribedEnum;
import io.opencode.loopper.domain.LifecycleMachineType;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** A small immutable state machine with no Spring runtime dependency. */
public final class FiniteStateMachine<S extends Enum<S> & DescribedEnum,
        E extends Enum<E> & DescribedEnum> {
    private final LifecycleMachineType type;
    private final Class<S> stateType;
    private final Map<Transition<S, E>, S> transitions;
    private final Map<StatePair<S>, E> defaultEvents;

    private FiniteStateMachine(LifecycleMachineType type, Class<S> stateType, Class<E> eventType,
                               Map<Transition<S, E>, S> transitions, Map<StatePair<S>, E> defaultEvents) {
        this.type = type;
        this.stateType = stateType;
        this.transitions = Map.copyOf(transitions);
        this.defaultEvents = Map.copyOf(defaultEvents);
        validateDescriptions(type.getDeclaringClass().getEnumConstants());
        validateDescriptions(stateType.getEnumConstants());
        validateDescriptions(eventType.getEnumConstants());
    }

    public S parse(String value, String entityId) {
        try { return Enum.valueOf(stateType, value); }
        catch (RuntimeException invalid) { throw new PersistedStateInvalidException(type, entityId, value); }
    }

    public S transition(S current, E event) {
        S target = transitions.get(new Transition<>(current, event));
        if (target == null) throw new InvalidStateTransitionException(type, current.name(), event.name(), null);
        return target;
    }

    public E defaultEvent(S current, S target) {
        E event = defaultEvents.get(new StatePair<>(current, target));
        if (event == null) throw new InvalidStateTransitionException(type, current.name(), "UNKNOWN", target.name());
        return event;
    }

    public void requireTarget(S current, E event, S expectedTarget) {
        S actual = transition(current, event);
        if (actual != expectedTarget) {
            throw new InvalidStateTransitionException(type, current.name(), event.name(), expectedTarget.name());
        }
    }

    Map<Transition<S, E>, S> definitions() { return transitions; }

    public static <S extends Enum<S> & DescribedEnum, E extends Enum<E> & DescribedEnum>
    Builder<S, E> builder(LifecycleMachineType type, Class<S> stateType, Class<E> eventType) {
        return new Builder<>(type, stateType, eventType);
    }

    private static void validateDescriptions(DescribedEnum[] values) {
        for (DescribedEnum value : values) {
            String description = value.description();
            if (description == null || description.isBlank() || !description.equals(description.trim())) {
                throw new IllegalStateException(value + " must have a non-blank trimmed Chinese description");
            }
        }
    }

    public static final class Builder<S extends Enum<S> & DescribedEnum,
            E extends Enum<E> & DescribedEnum> {
        private final LifecycleMachineType type;
        private final Class<S> stateType;
        private final Class<E> eventType;
        private final Map<Transition<S, E>, S> transitions = new LinkedHashMap<>();
        private final Map<StatePair<S>, E> defaults = new LinkedHashMap<>();

        private Builder(LifecycleMachineType type, Class<S> stateType, Class<E> eventType) {
            this.type = Objects.requireNonNull(type);
            this.stateType = Objects.requireNonNull(stateType);
            this.eventType = Objects.requireNonNull(eventType);
        }

        public Builder<S, E> transition(S from, E event, S to) {
            Transition<S, E> key = new Transition<>(from, event);
            if (transitions.putIfAbsent(key, to) != null) {
                throw new IllegalStateException("Duplicate transition for " + type + ": " + from + " + " + event);
            }
            defaults.putIfAbsent(new StatePair<>(from, to), event);
            return this;
        }

        public FiniteStateMachine<S, E> build() {
            return new FiniteStateMachine<>(type, stateType, eventType, transitions, defaults);
        }
    }

    record Transition<S, E>(S state, E event) { }
    private record StatePair<S>(S from, S to) { }
}
