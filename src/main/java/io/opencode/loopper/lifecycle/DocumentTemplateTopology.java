package io.opencode.loopper.lifecycle;

import static io.opencode.loopper.domain.DocumentTemplateState.*;
import static io.opencode.loopper.domain.LifecycleEvent.*;
import io.opencode.loopper.domain.DocumentTemplateState;
import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.domain.LifecycleMachineType;
import java.util.Map;

final class DocumentTemplateTopology {
    private DocumentTemplateTopology() { }
    static FiniteStateMachine<DocumentTemplateState, LifecycleEvent> machine() {
        var b = FiniteStateMachine.builder(LifecycleMachineType.DOCUMENT_TEMPLATE_RUN,
                DocumentTemplateState.class, LifecycleEvent.class)
                .transition(PREPARING, ANALYZE_DOCUMENT_REQUIREMENTS, ANALYZING)
                .transition(ANALYZING, REVIEW_DOCUMENT_REQUIREMENTS, REVIEWING)
                .transition(REVIEWING, ANALYZE_DOCUMENT_REQUIREMENTS, ANALYZING)
                .transition(REVIEWING, DESIGN_DOCUMENT_REQUIREMENTS, DESIGNING)
                .transition(REVIEWING, ASSESS_REQUIREMENT_CODE, ASSESSING)
                .transition(DESIGNING, EXECUTE_DOCUMENT_REQUIREMENTS, EXECUTING)
                .transition(EXECUTING, RENDER_REQUIREMENT_REPORT, REPORTING)
                .transition(ASSESSING, VERIFY_REQUIREMENT_ASSESSMENT, VERIFYING)
                .transition(VERIFYING, ASSESS_REQUIREMENT_CODE, ASSESSING)
                .transition(VERIFYING, RENDER_REQUIREMENT_REPORT, REPORTING)
                .transition(REPORTING, COMPLETE, COMPLETED)
                .transition(STOPPING, ABORT, CANCELLED)
                .transition(STOPPING, REQUIRE_INPUT, WAITING_INPUT);
        var resumes = Map.of(ANALYZING, ANALYZE_DOCUMENT_REQUIREMENTS, REVIEWING, REVIEW_DOCUMENT_REQUIREMENTS,
                DESIGNING, DESIGN_DOCUMENT_REQUIREMENTS, EXECUTING, EXECUTE_DOCUMENT_REQUIREMENTS,
                ASSESSING, ASSESS_REQUIREMENT_CODE, VERIFYING, VERIFY_REQUIREMENT_ASSESSMENT,
                REPORTING, RENDER_REQUIREMENT_REPORT, PREPARING, PREPARE);
        resumes.forEach((state, event) -> {
            b.transition(state, REQUIRE_INPUT, WAITING_INPUT);
            b.transition(WAITING_INPUT, event, state);
        });
        for (var state : DocumentTemplateState.values()) {
            if (!state.terminal() && state != STOPPING) b.transition(state, CANCEL, STOPPING);
        }
        return b.build();
    }
}
