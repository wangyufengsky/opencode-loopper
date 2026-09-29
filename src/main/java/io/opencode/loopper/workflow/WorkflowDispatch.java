package io.opencode.loopper.workflow;

import java.util.*;

/** Deterministic scheduling scope. Retries never reset the node's accumulated attempt count. */
public final class WorkflowDispatch {
    private WorkflowDispatch() { }
    public enum Mode { SINGLE, UNTIL, CONTINUOUS }
    public enum Reason { READY, RUNNING, SCOPE_COMPLETE, RETRY_EXHAUSTED, CANCELLED, DEPENDENCY_BLOCKED }
    public record Scope(Mode mode,String target,String manualRetryNode,int manualRetryOrdinal) { }
    public record Progress(String nodeRunId,WorkflowNodeState state,int attempts,String outcome) { }
    public record Decision(String nodeKey,Reason reason) { }
    public record Permit(long controlVersion,int planRevision) { }
    public static Set<String> scope(WorkflowGraph graph,Mode mode,String target) {
        if (mode==null) throw new IllegalArgumentException("请选择执行方式");
        if (mode==Mode.CONTINUOUS) {
            if (target!=null) throw new IllegalArgumentException("连续执行不需要目标节点");
            var ids=new LinkedHashSet<String>();graph.nodes().forEach(node->ids.add(node.id()));return ids;
        }
        if (graph.nodes().stream().noneMatch(node->node.id().equals(target))) throw new IllegalArgumentException("请选择当前计划中的目标节点");
        var ids=new LinkedHashSet<String>();var pending=new ArrayDeque<String>();pending.add(target);
        while(!pending.isEmpty()) {
            String id=pending.removeFirst();if (!ids.add(id)) continue;
            if (mode==Mode.UNTIL) graph.edges().stream().filter(edge->edge.to().equals(id)).forEach(edge->pending.add(edge.from()));
        }
        return ids;
    }
    public static Decision next(WorkflowGraph graph,Scope control,Map<String,Progress> progress) {
        var scope=scope(graph,control.mode(),control.target());
        var selected=graph.nodes().stream().filter(node->scope.contains(node.id())).toList();
        for (var node:selected) {
            var value=progress.get(node.id());
            if (value==null) return new Decision(null,Reason.DEPENDENCY_BLOCKED);
            if (value.state()==WorkflowNodeState.CANCELLED) return new Decision(null,Reason.CANCELLED);
            if (value.state()==WorkflowNodeState.FAILED) {
                boolean allowed=value.attempts()<=node.maxRetries() || Objects.equals(value.nodeRunId(),control.manualRetryNode())
                        && value.attempts()+1==control.manualRetryOrdinal();
                if (!allowed) return new Decision(null,Reason.RETRY_EXHAUSTED);
            }
        }
        // Repair failed work before dispatching new siblings. Stop proof is enforced again by node admission.
        for (var node:selected) if (progress.get(node.id()).state()==WorkflowNodeState.FAILED) return new Decision(node.id(),Reason.READY);
        var readiness=new LinkedHashMap<String,WorkflowReadiness.Progress>();
        progress.forEach((key,value)->readiness.put(key,new WorkflowReadiness.Progress(value.state(),value.outcome())));
        for (var node:selected) if (WorkflowReadiness.decide(graph,node.id(),readiness)==WorkflowReadiness.Decision.READY)
            return new Decision(node.id(),Reason.READY);
        if (selected.stream().allMatch(node->Set.of(WorkflowNodeState.SUCCEEDED,WorkflowNodeState.SKIPPED).contains(progress.get(node.id()).state())))
            return new Decision(null,Reason.SCOPE_COMPLETE);
        return new Decision(null,selected.stream().anyMatch(node->progress.get(node.id()).state()==WorkflowNodeState.ACTIVE)?Reason.RUNNING:Reason.DEPENDENCY_BLOCKED);
    }
}
