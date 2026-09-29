package io.opencode.loopper.workflow;

import static org.assertj.core.api.Assertions.*;
import static io.opencode.loopper.workflow.WorkflowFixtures.*;
import static io.opencode.loopper.workflow.WorkflowGraph.*;
import static io.opencode.loopper.workflow.WorkflowGraphValidator.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkflowGraphValidatorTest {
    @Test void freeWorkWithoutHiddenReviewIsExecutableAndBlankDraftRemainsEditable() {
        assertThat(validate(single(), Mode.EXECUTION)).isEmpty();
        assertThat(validate(WorkflowGraph.empty(), Mode.DRAFT)).allMatch(value -> value.severity() == Severity.WARNING);
        assertThat(validate(WorkflowGraph.empty(), Mode.EXECUTION)).anyMatch(value -> value.code().equals("WORKFLOW_EMPTY") && value.severity() == Severity.ERROR);
    }
    @Test void parallelAndConditionalPathsCanJoinWithExplicitAncestorInputs() {
        var graph = new WorkflowGraph(1, List.of(node("design", List.of()), node("left", List.of(input("design", "design"))),
                node("right", List.of(input("design", "design"))), node("join", List.of(input("design", "design"),
                        new Input("left", InputSource.NODE, "left", "result", DataKind.TEXT, false),
                        new Input("right", InputSource.NODE, "right", "result", DataKind.TEXT, false)))),
                List.of(new Edge("a", "design", "left", "SUCCESS"), new Edge("b", "design", "right", "REVISE"),
                        new Edge("c", "left", "join", null), new Edge("d", "right", "join", null)), List.of());
        assertThat(validate(graph, Mode.EXECUTION)).isEmpty();
    }
    @Test void rejectsCyclesDanglingEdgesAndDuplicateIdsWithLocations() {
        var chain = chain();
        var cycle = new WorkflowGraph(1, chain.nodes(), List.of(chain.edges().getFirst(), new Edge("return", "second", "first", null)), List.of());
        assertThat(validate(cycle, Mode.EXECUTION)).anyMatch(value -> value.code().equals("WORKFLOW_CYCLE"));
        var broken = new WorkflowGraph(1, List.of(node("first", List.of()), node("first", List.of())),
                List.of(new Edge("missing", "first", "unknown", null)), List.of());
        assertThat(validate(broken, Mode.EXECUTION)).extracting(Diagnostic::code).contains("NODE_ID_INVALID", "EDGE_ENDPOINT_INVALID");
        assertThat(validate(broken, Mode.EXECUTION)).allMatch(value -> !value.path().isBlank());
    }
    @Test void anInputCannotSilentlyDependOnAnUnconnectedSiblingOrDeletedProducer() {
        var graph = new WorkflowGraph(1, chain().nodes(), List.of(), List.of());
        assertThat(validate(graph, Mode.EXECUTION)).anyMatch(value -> value.code().equals("INPUT_SOURCE_UNAVAILABLE"));
        graph = new WorkflowGraph(1, List.of(chain().nodes().getLast()), List.of(), List.of());
        assertThat(validate(graph, Mode.EXECUTION)).anyMatch(value -> value.code().equals("INPUT_SOURCE_UNAVAILABLE"));
    }
    @Test void enforcesDeclaredInputTypesOutputsAndBusinessConditions() {
        var wrong = new Input("previous", InputSource.NODE, "first", "result", DataKind.CODE, true);
        var graph = new WorkflowGraph(1, List.of(node("first", List.of()), node("second", List.of(wrong))),
                List.of(new Edge("branch", "first", "second", "TRANSPORT_FAILURE")), List.of());
        assertThat(validate(graph, Mode.EXECUTION)).extracting(Diagnostic::code).contains("INPUT_TYPE_MISMATCH", "EDGE_OUTCOME_UNKNOWN");
    }
    @Test void publicMaterialIsExplicitAndMatchesTheChosenType() {
        var input = new Input("request", InputSource.REQUIREMENT, "brief", null, DataKind.TEXT, true);
        var graph = new WorkflowGraph(1, List.of(node("design", List.of(input))), List.of(),
                List.of(new PublicInput("brief", "需求说明", DataKind.TEXT, true)));
        assertThat(validate(graph, Mode.EXECUTION)).isEmpty();
    }
    @Test void aHumanNodeNeedsNoFabricatedRoleButStillRequiresAnOutputAndCompletionRule() {
        var human = new Node("check", "人工检查", NodeKind.HUMAN, null, 0, null, "检查上游成果",
                List.of(), List.of(new Output("decision", "人工决定", DataKind.CONTROL, true)), List.of(),
                new Completion(CompletionKind.HUMAN, "用户记录检查结论", null), 0, true, Map.of());
        assertThat(validate(new WorkflowGraph(1, List.of(human), List.of(), List.of()), Mode.EXECUTION)).isEmpty();
    }
}
