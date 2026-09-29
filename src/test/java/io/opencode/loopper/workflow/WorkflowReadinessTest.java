package io.opencode.loopper.workflow;

import static org.assertj.core.api.Assertions.*;
import static io.opencode.loopper.workflow.WorkflowFixtures.*;
import static io.opencode.loopper.workflow.WorkflowReadiness.*;
import static io.opencode.loopper.workflow.WorkflowNodeState.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class WorkflowReadinessTest {
    @Test void rootsAreReadyAndActiveOrFailedWorkCannotBeAutomaticallyStartedAgain() {
        assertThat(decide(single(), "first", Map.of())).isEqualTo(Decision.READY);
        for (var state : List.of(ACTIVE, FAILED, CANCELLED, SUCCEEDED, SKIPPED))
            assertThat(decide(single(), "first", Map.of("first", new Progress(state, null)))).isEqualTo(Decision.ALREADY_STARTED);
    }
    @Test void parentFailureAndMissingBusinessOutcomeNeverSelectASuccessBranch() {
        var graph = conditional();
        for (var state : List.of(PENDING, ACTIVE, FAILED, CANCELLED))
            assertThat(decide(graph, "second", Map.of("first", new Progress(state, "SUCCESS")))).isEqualTo(Decision.WAITING);
        assertThat(decide(graph, "second", Map.of("first", new Progress(SUCCEEDED, null)))).isEqualTo(Decision.WAITING);
        assertThat(decide(graph, "second", Map.of("first", new Progress(SUCCEEDED, "SUCCESS")))).isEqualTo(Decision.READY);
        assertThat(decide(graph, "second", Map.of("first", new Progress(SUCCEEDED, "REVISE")))).isEqualTo(Decision.SKIPPED);
    }
    @Test void skipPropagatesButAnUnconditionalJoinStillWaitsForEveryApplicableParent() {
        var graph = new WorkflowGraph(1, List.of(node("left", List.of()), node("right", List.of()), node("join", List.of())),
                List.of(new WorkflowGraph.Edge("lj", "left", "join", null), new WorkflowGraph.Edge("rj", "right", "join", null)), List.of());
        assertThat(decide(graph, "join", Map.of("left", new Progress(SKIPPED, null), "right", new Progress(ACTIVE, null)))).isEqualTo(Decision.WAITING);
        assertThat(decide(graph, "join", Map.of("left", new Progress(SKIPPED, null), "right", new Progress(SUCCEEDED, "SUCCESS")))).isEqualTo(Decision.READY);
        assertThat(decide(graph, "join", Map.of("left", new Progress(SKIPPED, null), "right", new Progress(SKIPPED, null)))).isEqualTo(Decision.SKIPPED);
    }
    private WorkflowGraph conditional() {
        return new WorkflowGraph(1, chain().nodes(), List.of(new WorkflowGraph.Edge("first-second", "first", "second", "SUCCESS")), List.of());
    }
}
