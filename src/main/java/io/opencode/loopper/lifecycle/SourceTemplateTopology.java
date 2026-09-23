package io.opencode.loopper.lifecycle;

import static io.opencode.loopper.domain.SourceTemplateState.*;
import static io.opencode.loopper.domain.LifecycleEvent.*;
import io.opencode.loopper.domain.*;
import java.util.Map;

final class SourceTemplateTopology {
    private SourceTemplateTopology() { }
    static FiniteStateMachine<SourceTemplateState, LifecycleEvent> machine() {
        var b = FiniteStateMachine.builder(LifecycleMachineType.SOURCE_TEMPLATE_RUN,
                SourceTemplateState.class, LifecycleEvent.class)
                .transition(PENDING_START, REQUEST_START, PREPARING)
                .transition(PREPARING, BEGIN_PACKAGE_DESIGN, DESIGNING)
                .transition(PREPARING, START, WRITING)
                .transition(DESIGNING, REQUEST_PACKAGE_EXECUTION, EXECUTING)
                .transition(EXECUTING, FINISH, REPORTING)
                .transition(WRITING, REQUIRE_REVIEW, REVIEWING)
                .transition(REVIEWING, RETRY, WRITING)
                .transition(REVIEWING, APPROVE, REPORTING)
                .transition(REPORTING, COMPLETE, COMPLETED)
                .transition(STOPPING, ABORT, CANCELLED)
                .transition(STOPPING, REQUIRE_INPUT, WAITING_INPUT);
        var resumes = Map.of(PREPARING, PREPARE, DESIGNING, BEGIN_PACKAGE_DESIGN,
                EXECUTING, REQUEST_PACKAGE_EXECUTION, WRITING, START,
                REVIEWING, REQUIRE_REVIEW, REPORTING, FINISH);
        resumes.forEach((state, event) -> {
            b.transition(state, REQUIRE_INPUT, WAITING_INPUT);
            b.transition(WAITING_INPUT, event, state);
        });
        for (var state : SourceTemplateState.values())
            if (!state.terminal() && state != STOPPING) b.transition(state, CANCEL, STOPPING);
        return b.build();
    }
}
