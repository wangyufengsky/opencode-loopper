package io.opencode.loopper.lifecycle;

import static io.opencode.loopper.domain.LifecycleEvent.ABORT;
import static io.opencode.loopper.domain.LifecycleEvent.CANCEL;
import static io.opencode.loopper.domain.LifecycleEvent.COMPLETE;
import static io.opencode.loopper.domain.LifecycleEvent.DISCONNECT;
import static io.opencode.loopper.domain.LifecycleEvent.DISPATCH;
import static io.opencode.loopper.domain.LifecycleEvent.FAIL;
import static io.opencode.loopper.domain.LifecycleEvent.FINISH;
import static io.opencode.loopper.domain.LifecycleEvent.RECOVER;
import static io.opencode.loopper.domain.LifecycleEvent.STALE;
import static io.opencode.loopper.domain.LifecycleEvent.START;
import static io.opencode.loopper.domain.LifecycleEvent.UPDATE;

import io.opencode.loopper.domain.AcceptanceCandidateHandoffState;
import io.opencode.loopper.domain.AcceptanceCandidateHandoffCleanupState;
import io.opencode.loopper.domain.AcceptanceCandidateInternalLaunchCleanupState;
import io.opencode.loopper.domain.AcceptanceCandidateInternalLaunchState;
import io.opencode.loopper.domain.AcceptanceCandidateInternalTerminationIntentState;
import io.opencode.loopper.domain.CandidatePromptDispatchState;
import io.opencode.loopper.domain.GenericCandidateInternalLaunchCleanupState;
import io.opencode.loopper.domain.GenericCandidateInternalLaunchState;
import io.opencode.loopper.domain.GenericCandidateInternalTerminationIntentState;
import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.domain.LifecycleMachineType;

/** Candidate-specific lifecycle topology kept out of the central registration catalog. */
final class CandidateLifecycleTopologies {
    private CandidateLifecycleTopologies() { }

    static FiniteStateMachine<CandidatePromptDispatchState, LifecycleEvent> promptDispatch() {
        var b = FiniteStateMachine.builder(LifecycleMachineType.CANDIDATE_PROMPT_DISPATCH,
                CandidatePromptDispatchState.class, LifecycleEvent.class);
        b.transition(CandidatePromptDispatchState.PROMPTING, COMPLETE,
                        CandidatePromptDispatchState.ACKNOWLEDGED)
                .transition(CandidatePromptDispatchState.PROMPTING, DISCONNECT,
                        CandidatePromptDispatchState.DISCONNECTED)
                .transition(CandidatePromptDispatchState.DISCONNECTED, COMPLETE,
                        CandidatePromptDispatchState.ACKNOWLEDGED)
                .transition(CandidatePromptDispatchState.PROMPTING, CANCEL,
                        CandidatePromptDispatchState.CANCELLED)
                .transition(CandidatePromptDispatchState.DISCONNECTED, CANCEL,
                        CandidatePromptDispatchState.CANCELLED);
        for (CandidatePromptDispatchState state : java.util.List.of(
                CandidatePromptDispatchState.PROMPTING, CandidatePromptDispatchState.ACKNOWLEDGED,
                CandidatePromptDispatchState.DISCONNECTED)) {
            b.transition(state, ABORT, CandidatePromptDispatchState.STOPPING);
        }
        return b.transition(CandidatePromptDispatchState.STOPPING, COMPLETE,
                CandidatePromptDispatchState.STOPPED).build();
    }

    static FiniteStateMachine<AcceptanceCandidateHandoffState, LifecycleEvent> acceptanceHandoff() {
        var b = FiniteStateMachine.builder(LifecycleMachineType.ACCEPTANCE_CANDIDATE_HANDOFF,
                AcceptanceCandidateHandoffState.class, LifecycleEvent.class);
        b.transition(AcceptanceCandidateHandoffState.STOPPING_OLD, COMPLETE,
                        AcceptanceCandidateHandoffState.OLD_STOPPED)
                .transition(AcceptanceCandidateHandoffState.OLD_STOPPED, DISPATCH,
                        AcceptanceCandidateHandoffState.CREATING_LEGACY)
                .transition(AcceptanceCandidateHandoffState.CREATING_LEGACY, COMPLETE,
                        AcceptanceCandidateHandoffState.LEGACY_CREATED)
                .transition(AcceptanceCandidateHandoffState.LEGACY_CREATED, START,
                        AcceptanceCandidateHandoffState.LEGACY_OPENED)
                .transition(AcceptanceCandidateHandoffState.LEGACY_OPENED, DISPATCH,
                        AcceptanceCandidateHandoffState.PROMPTING)
                .transition(AcceptanceCandidateHandoffState.PROMPTING, COMPLETE,
                        AcceptanceCandidateHandoffState.HANDED_OFF)
                .transition(AcceptanceCandidateHandoffState.HANDED_OFF, COMPLETE,
                        AcceptanceCandidateHandoffState.SETTLED)
                .transition(AcceptanceCandidateHandoffState.STOPPING_LEGACY, UPDATE,
                        AcceptanceCandidateHandoffState.STOPPING_LEGACY)
                .transition(AcceptanceCandidateHandoffState.CREATING_LEGACY, FAIL,
                        AcceptanceCandidateHandoffState.FAILED_STOPPED);
        for (AcceptanceCandidateHandoffState state : java.util.List.of(
                AcceptanceCandidateHandoffState.STOPPING_OLD,
                AcceptanceCandidateHandoffState.OLD_STOPPED,
                AcceptanceCandidateHandoffState.CREATING_LEGACY,
                AcceptanceCandidateHandoffState.LEGACY_CREATED,
                AcceptanceCandidateHandoffState.LEGACY_OPENED,
                AcceptanceCandidateHandoffState.PROMPTING,
                AcceptanceCandidateHandoffState.HANDED_OFF)) {
            b.transition(state, ABORT, AcceptanceCandidateHandoffState.STOPPING_LEGACY);
        }
        return b.transition(AcceptanceCandidateHandoffState.STOPPING_LEGACY, FAIL,
                        AcceptanceCandidateHandoffState.FAILED_STOPPED)
                .transition(AcceptanceCandidateHandoffState.STOPPING_LEGACY, CANCEL,
                        AcceptanceCandidateHandoffState.CANCELLED)
                .transition(AcceptanceCandidateHandoffState.STOPPING_LEGACY, STALE,
                        AcceptanceCandidateHandoffState.STALE)
                .build();
    }

    static FiniteStateMachine<AcceptanceCandidateHandoffCleanupState, LifecycleEvent> handoffCleanup() {
        var b = FiniteStateMachine.builder(LifecycleMachineType.ACCEPTANCE_CANDIDATE_HANDOFF_CLEANUP,
                AcceptanceCandidateHandoffCleanupState.class, LifecycleEvent.class);
        return b.transition(AcceptanceCandidateHandoffCleanupState.DISCOVERED, ABORT,
                        AcceptanceCandidateHandoffCleanupState.STOPPING)
                .transition(AcceptanceCandidateHandoffCleanupState.STOPPING, DISCONNECT,
                        AcceptanceCandidateHandoffCleanupState.DISCONNECTED)
                .transition(AcceptanceCandidateHandoffCleanupState.DISCONNECTED, ABORT,
                        AcceptanceCandidateHandoffCleanupState.STOPPING)
                .transition(AcceptanceCandidateHandoffCleanupState.STOPPING, COMPLETE,
                        AcceptanceCandidateHandoffCleanupState.STOPPED)
                .build();
    }

    static FiniteStateMachine<AcceptanceCandidateInternalLaunchState, LifecycleEvent> internalLaunch() {
        var b = FiniteStateMachine.builder(LifecycleMachineType.ACCEPTANCE_CANDIDATE_INTERNAL_LAUNCH,
                AcceptanceCandidateInternalLaunchState.class, LifecycleEvent.class);
        b.transition(AcceptanceCandidateInternalLaunchState.PREPARED, DISPATCH,
                        AcceptanceCandidateInternalLaunchState.CREATING)
                .transition(AcceptanceCandidateInternalLaunchState.CREATING, COMPLETE,
                        AcceptanceCandidateInternalLaunchState.CREATED)
                .transition(AcceptanceCandidateInternalLaunchState.CREATING, DISCONNECT,
                        AcceptanceCandidateInternalLaunchState.DISCONNECTED)
                .transition(AcceptanceCandidateInternalLaunchState.DISCONNECTED, COMPLETE,
                        AcceptanceCandidateInternalLaunchState.CREATED)
                .transition(AcceptanceCandidateInternalLaunchState.CREATED, COMPLETE,
                        AcceptanceCandidateInternalLaunchState.SETTLED)
                .transition(AcceptanceCandidateInternalLaunchState.SETTLED, FAIL,
                        AcceptanceCandidateInternalLaunchState.FAILED_STOPPED)
                .transition(AcceptanceCandidateInternalLaunchState.SETTLED, CANCEL,
                        AcceptanceCandidateInternalLaunchState.CANCELLED)
                .transition(AcceptanceCandidateInternalLaunchState.SETTLED, STALE,
                        AcceptanceCandidateInternalLaunchState.STALE)
                .transition(AcceptanceCandidateInternalLaunchState.PREPARED, CANCEL,
                        AcceptanceCandidateInternalLaunchState.CANCELLED)
                .transition(AcceptanceCandidateInternalLaunchState.PREPARED, STALE,
                        AcceptanceCandidateInternalLaunchState.STALE);
        for (AcceptanceCandidateInternalLaunchState state : java.util.List.of(
                AcceptanceCandidateInternalLaunchState.PREPARED,
                AcceptanceCandidateInternalLaunchState.CREATING,
                AcceptanceCandidateInternalLaunchState.CREATED,
                AcceptanceCandidateInternalLaunchState.DISCONNECTED)) {
            b.transition(state, ABORT, AcceptanceCandidateInternalLaunchState.STOPPING);
        }
        return b.transition(AcceptanceCandidateInternalLaunchState.STOPPING, DISCONNECT,
                        AcceptanceCandidateInternalLaunchState.DISCONNECTED)
                .transition(AcceptanceCandidateInternalLaunchState.STOPPING, FAIL,
                        AcceptanceCandidateInternalLaunchState.FAILED_STOPPED)
                .transition(AcceptanceCandidateInternalLaunchState.STOPPING, CANCEL,
                        AcceptanceCandidateInternalLaunchState.CANCELLED)
                .transition(AcceptanceCandidateInternalLaunchState.STOPPING, STALE,
                        AcceptanceCandidateInternalLaunchState.STALE)
                .build();
    }

    static FiniteStateMachine<AcceptanceCandidateInternalLaunchCleanupState, LifecycleEvent>
            internalLaunchCleanup() {
        var b = FiniteStateMachine.builder(LifecycleMachineType.ACCEPTANCE_CANDIDATE_INTERNAL_LAUNCH_CLEANUP,
                AcceptanceCandidateInternalLaunchCleanupState.class, LifecycleEvent.class);
        return b.transition(AcceptanceCandidateInternalLaunchCleanupState.DISCOVERED, ABORT,
                        AcceptanceCandidateInternalLaunchCleanupState.STOPPING)
                .transition(AcceptanceCandidateInternalLaunchCleanupState.STOPPING, DISCONNECT,
                        AcceptanceCandidateInternalLaunchCleanupState.DISCONNECTED)
                .transition(AcceptanceCandidateInternalLaunchCleanupState.DISCONNECTED, ABORT,
                        AcceptanceCandidateInternalLaunchCleanupState.STOPPING)
                .transition(AcceptanceCandidateInternalLaunchCleanupState.STOPPING, COMPLETE,
                        AcceptanceCandidateInternalLaunchCleanupState.STOPPED)
                .build();
    }

    static FiniteStateMachine<AcceptanceCandidateInternalTerminationIntentState, LifecycleEvent>
            internalTerminationIntent() {
        var b = FiniteStateMachine.builder(
                LifecycleMachineType.ACCEPTANCE_CANDIDATE_INTERNAL_TERMINATION_INTENT,
                AcceptanceCandidateInternalTerminationIntentState.class, LifecycleEvent.class);
        return b.transition(AcceptanceCandidateInternalTerminationIntentState.REQUESTED, DISCONNECT,
                        AcceptanceCandidateInternalTerminationIntentState.DISCONNECTED)
                .transition(AcceptanceCandidateInternalTerminationIntentState.DISCONNECTED, RECOVER,
                        AcceptanceCandidateInternalTerminationIntentState.REQUESTED)
                .transition(AcceptanceCandidateInternalTerminationIntentState.REQUESTED, COMPLETE,
                        AcceptanceCandidateInternalTerminationIntentState.READY)
                .transition(AcceptanceCandidateInternalTerminationIntentState.DISCONNECTED, COMPLETE,
                        AcceptanceCandidateInternalTerminationIntentState.READY)
                .transition(AcceptanceCandidateInternalTerminationIntentState.READY, FINISH,
                        AcceptanceCandidateInternalTerminationIntentState.COMPLETED)
                .build();
    }

    static FiniteStateMachine<GenericCandidateInternalLaunchState, LifecycleEvent> genericInternalLaunch() {
        var b = FiniteStateMachine.builder(LifecycleMachineType.GENERIC_CANDIDATE_INTERNAL_LAUNCH,
                GenericCandidateInternalLaunchState.class, LifecycleEvent.class);
        b.transition(GenericCandidateInternalLaunchState.PREPARED, DISPATCH,
                        GenericCandidateInternalLaunchState.CREATING)
                .transition(GenericCandidateInternalLaunchState.CREATING, COMPLETE,
                        GenericCandidateInternalLaunchState.CREATED)
                .transition(GenericCandidateInternalLaunchState.CREATING, DISCONNECT,
                        GenericCandidateInternalLaunchState.DISCONNECTED)
                .transition(GenericCandidateInternalLaunchState.DISCONNECTED, COMPLETE,
                        GenericCandidateInternalLaunchState.CREATED)
                .transition(GenericCandidateInternalLaunchState.CREATED, COMPLETE,
                        GenericCandidateInternalLaunchState.SETTLED)
                .transition(GenericCandidateInternalLaunchState.SETTLED, FAIL,
                        GenericCandidateInternalLaunchState.FAILED_STOPPED)
                .transition(GenericCandidateInternalLaunchState.SETTLED, CANCEL,
                        GenericCandidateInternalLaunchState.CANCELLED)
                .transition(GenericCandidateInternalLaunchState.SETTLED, STALE,
                        GenericCandidateInternalLaunchState.STALE)
                .transition(GenericCandidateInternalLaunchState.SETTLED, FINISH,
                        GenericCandidateInternalLaunchState.COMPLETED)
                .transition(GenericCandidateInternalLaunchState.PREPARED, CANCEL,
                        GenericCandidateInternalLaunchState.CANCELLED)
                .transition(GenericCandidateInternalLaunchState.PREPARED, FAIL,
                        GenericCandidateInternalLaunchState.FAILED_STOPPED)
                .transition(GenericCandidateInternalLaunchState.PREPARED, STALE,
                        GenericCandidateInternalLaunchState.STALE);
        for (GenericCandidateInternalLaunchState state : java.util.List.of(
                GenericCandidateInternalLaunchState.PREPARED,
                GenericCandidateInternalLaunchState.CREATING,
                GenericCandidateInternalLaunchState.CREATED,
                GenericCandidateInternalLaunchState.DISCONNECTED)) {
            b.transition(state, ABORT, GenericCandidateInternalLaunchState.STOPPING);
        }
        return b.transition(GenericCandidateInternalLaunchState.STOPPING, DISCONNECT,
                        GenericCandidateInternalLaunchState.DISCONNECTED)
                .transition(GenericCandidateInternalLaunchState.STOPPING, FAIL,
                        GenericCandidateInternalLaunchState.FAILED_STOPPED)
                .transition(GenericCandidateInternalLaunchState.STOPPING, CANCEL,
                        GenericCandidateInternalLaunchState.CANCELLED)
                .transition(GenericCandidateInternalLaunchState.STOPPING, STALE,
                        GenericCandidateInternalLaunchState.STALE)
                .transition(GenericCandidateInternalLaunchState.STOPPING, FINISH,
                        GenericCandidateInternalLaunchState.COMPLETED)
                .build();
    }

    static FiniteStateMachine<GenericCandidateInternalLaunchCleanupState, LifecycleEvent>
            genericInternalLaunchCleanup() {
        var b = FiniteStateMachine.builder(LifecycleMachineType.GENERIC_CANDIDATE_INTERNAL_LAUNCH_CLEANUP,
                GenericCandidateInternalLaunchCleanupState.class, LifecycleEvent.class);
        return b.transition(GenericCandidateInternalLaunchCleanupState.DISCOVERED, ABORT,
                        GenericCandidateInternalLaunchCleanupState.STOPPING)
                .transition(GenericCandidateInternalLaunchCleanupState.STOPPING, DISCONNECT,
                        GenericCandidateInternalLaunchCleanupState.DISCONNECTED)
                .transition(GenericCandidateInternalLaunchCleanupState.DISCONNECTED, ABORT,
                        GenericCandidateInternalLaunchCleanupState.STOPPING)
                .transition(GenericCandidateInternalLaunchCleanupState.STOPPING, COMPLETE,
                        GenericCandidateInternalLaunchCleanupState.STOPPED)
                .build();
    }

    static FiniteStateMachine<GenericCandidateInternalTerminationIntentState, LifecycleEvent>
            genericInternalTerminationIntent() {
        var b = FiniteStateMachine.builder(
                LifecycleMachineType.GENERIC_CANDIDATE_INTERNAL_TERMINATION_INTENT,
                GenericCandidateInternalTerminationIntentState.class, LifecycleEvent.class);
        return b.transition(GenericCandidateInternalTerminationIntentState.REQUESTED, DISCONNECT,
                        GenericCandidateInternalTerminationIntentState.DISCONNECTED)
                .transition(GenericCandidateInternalTerminationIntentState.DISCONNECTED, RECOVER,
                        GenericCandidateInternalTerminationIntentState.REQUESTED)
                .transition(GenericCandidateInternalTerminationIntentState.REQUESTED, COMPLETE,
                        GenericCandidateInternalTerminationIntentState.READY)
                .transition(GenericCandidateInternalTerminationIntentState.DISCONNECTED, COMPLETE,
                        GenericCandidateInternalTerminationIntentState.READY)
                .transition(GenericCandidateInternalTerminationIntentState.READY, FINISH,
                        GenericCandidateInternalTerminationIntentState.COMPLETED)
                .build();
    }
}
