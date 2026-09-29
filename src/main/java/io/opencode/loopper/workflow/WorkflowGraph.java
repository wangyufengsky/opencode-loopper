package io.opencode.loopper.workflow;

import java.util.List;
import java.util.Map;

/** Executable intent only. Canvas positions, live attempts and accepted artifact versions live separately. */
public record WorkflowGraph(int schemaVersion, List<Node> nodes, List<Edge> edges, List<PublicInput> inputs) {
    public WorkflowGraph {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
    }
    public static WorkflowGraph empty() { return new WorkflowGraph(1, List.of(), List.of(), List.of()); }
    public record Node(String id, String title, NodeKind kind, String moduleId, int moduleVersion,
                       String roleId, String task, List<Input> inputs, List<Output> outputs,
                       List<String> outcomes, Completion completion, int maxRetries, boolean pauseAfter,
                       Map<String, String> parameters,
                       @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                       String roleRevisionId) {
        public Node(String id, String title, NodeKind kind, String moduleId, int moduleVersion,
                    String roleId, String task, List<Input> inputs, List<Output> outputs,
                    List<String> outcomes, Completion completion, int maxRetries, boolean pauseAfter,
                    Map<String, String> parameters) {
            this(id, title, kind, moduleId, moduleVersion, roleId, task, inputs, outputs, outcomes,
                    completion, maxRetries, pauseAfter, parameters, null);
        }
        public Node {
            inputs = inputs == null ? List.of() : List.copyOf(inputs);
            outputs = outputs == null ? List.of() : List.copyOf(outputs);
            outcomes = outcomes == null ? List.of() : List.copyOf(outcomes);
            parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        }
    }
    public enum NodeKind { WORK, HUMAN, SYSTEM }
    public enum DataKind { TEXT, JSON, DOCUMENT, CODE, DECISION, PLAN, CONTROL }
    public enum InputSource { REQUIREMENT, NODE }
    public enum CompletionKind { DELIVERABLES, OUTCOME, VERIFIED, HUMAN }
    public record Input(String name, InputSource source, String sourceId, String output,
                        DataKind kind, boolean required) { }
    public record Output(String name, String title, DataKind kind, boolean required) { }
    public record PublicInput(String name, String title, DataKind kind, boolean required) { }
    public record Completion(CompletionKind kind, String criterion, String expectedOutcome) { }
    /** Null outcome is unconditional; a named outcome matches a business result, never a transport failure. */
    public record Edge(String id, String from, String to, String outcome) { }
}
