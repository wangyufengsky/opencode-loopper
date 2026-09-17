package io.opencode.loopper.lifecycle;
import io.opencode.loopper.domain.*;
import static io.opencode.loopper.domain.KnowledgeTurnState.*;
import static io.opencode.loopper.domain.LifecycleEvent.*;
final class KnowledgeTopology {
    private KnowledgeTopology() { }
    static FiniteStateMachine<KnowledgeTurnState, LifecycleEvent> turn() {
        var builder = FiniteStateMachine.builder(LifecycleMachineType.KNOWLEDGE_TURN, KnowledgeTurnState.class, LifecycleEvent.class)
                .transition(PREPARED, PREPARE, CREATING).transition(PREPARED, DISPATCH, SENDING)
                .transition(CREATING, DISCONNECT, CREATE_UNKNOWN).transition(CREATE_UNKNOWN, RECOVER, CREATING)
                .transition(CREATING, DISPATCH, SENDING).transition(SENDING, START, RUNNING)
                .transition(SENDING, DISCONNECT, UNKNOWN).transition(SENDING, FAIL, FAILED).transition(UNKNOWN, RECOVER, RUNNING)
                .transition(RUNNING, COMPLETE, COMPLETED).transition(PREPARED, FAIL, FAILED).transition(RUNNING, FAIL, FAILED)
                .transition(STOPPING, ABORT, STOPPED);
        for (KnowledgeTurnState state : KnowledgeTurnState.values()) if (!state.terminal() && state != STOPPING) builder.transition(state, CANCEL, STOPPING);
        return builder.build();
    }
    static FiniteStateMachine<KnowledgeConversationState, LifecycleEvent> conversation() {
        var builder = FiniteStateMachine.builder(LifecycleMachineType.KNOWLEDGE_CONVERSATION, KnowledgeConversationState.class, LifecycleEvent.class);
        return builder.transition(KnowledgeConversationState.IDLE, START, KnowledgeConversationState.RUNNING)
                .transition(KnowledgeConversationState.RUNNING, COMPLETE, KnowledgeConversationState.IDLE)
                .transition(KnowledgeConversationState.RUNNING, CANCEL, KnowledgeConversationState.STOPPING)
                .transition(KnowledgeConversationState.STOPPING, ABORT, KnowledgeConversationState.IDLE)
                .transition(KnowledgeConversationState.RUNNING, DISCONNECT, KnowledgeConversationState.DISCONNECTED)
                .transition(KnowledgeConversationState.DISCONNECTED, RECOVER, KnowledgeConversationState.RUNNING)
                .transition(KnowledgeConversationState.DISCONNECTED, CANCEL, KnowledgeConversationState.STOPPING).build();
    }
}
