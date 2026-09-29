package io.opencode.loopper.workflow;

import java.util.*;

/** Conditional edges are settled only by completed business outcomes, never by transport failures. */
public final class WorkflowReadiness {
    private WorkflowReadiness() { }
    public enum Decision { READY, WAITING, SKIPPED, ALREADY_STARTED }
    public record Progress(WorkflowNodeState state, String outcome) { }
    public static Decision decide(WorkflowGraph graph, String nodeId, Map<String, Progress> progress) {
        if (graph.nodes().stream().noneMatch(node -> node.id().equals(nodeId))) throw new IllegalArgumentException("Unknown node");
        var own = progress.get(nodeId);
        if (own != null && own.state() != WorkflowNodeState.PENDING) return Decision.ALREADY_STARTED;
        var incoming = graph.edges().stream().filter(edge -> edge.to().equals(nodeId)).toList();
        if (incoming.isEmpty()) return Decision.READY;
        int applicable = 0;
        for (var edge : incoming) {
            var parent = progress.get(edge.from());
            if (parent == null) return Decision.WAITING;
            if (parent.state() == WorkflowNodeState.SKIPPED) continue;
            if (parent.state() != WorkflowNodeState.SUCCEEDED) return Decision.WAITING;
            if (edge.outcome() == null) applicable++;
            else if (parent.outcome() == null) return Decision.WAITING;
            else if (edge.outcome().equals(parent.outcome())) applicable++;
        }
        return applicable == 0 ? Decision.SKIPPED : Decision.READY;
    }
}
