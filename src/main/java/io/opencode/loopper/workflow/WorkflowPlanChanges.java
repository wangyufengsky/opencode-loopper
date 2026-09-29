package io.opencode.loopper.workflow;

import java.util.*;

/** Pure graph comparison and protection of facts already consumed by retained executions. */
public final class WorkflowPlanChanges {
    private WorkflowPlanChanges() { }
    public record Changes(Set<String> added,Set<String> changed,Set<String> removed,Set<String> affected,Set<String> protectedNodes) { }
    public static Changes compare(WorkflowGraph before,WorkflowGraph after,Map<String,WorkflowNodeState> states) {
        if(!before.inputs().equals(after.inputs()))throw new IllegalArgumentException("已开始需求的公共资料声明保持冻结，请使用后续节点交付新的资料。");
        var old=index(before);var next=index(after);var added=new LinkedHashSet<>(next.keySet());added.removeAll(old.keySet());
        var removed=new LinkedHashSet<>(old.keySet());removed.removeAll(next.keySet());var changed=new LinkedHashSet<String>();
        for(var key:next.keySet())if(old.containsKey(key) && (!old.get(key).equals(next.get(key)) || !incoming(before,key).equals(incoming(after,key))))changed.add(key);
        var affected=new LinkedHashSet<>(added);affected.addAll(changed);affected.addAll(removed);
        expand(affected,before,false);expand(affected,after,false);
        var protectedNodes=new LinkedHashSet<String>();
        for(var entry:states.entrySet()) {
            if(entry.getValue()==WorkflowNodeState.ACTIVE && !next.containsKey(entry.getKey()))throw new IllegalArgumentException("正在执行的节点必须先停止并取得停止证明，才能移出计划。");
            if(next.containsKey(entry.getKey()) && Set.of(WorkflowNodeState.ACTIVE,WorkflowNodeState.SUCCEEDED).contains(entry.getValue()))protectedNodes.add(entry.getKey());
        }
        expand(protectedNodes,before,true);
        if(protectedNodes.stream().anyMatch(affected::contains))throw new IllegalArgumentException("当前改动影响已执行节点或它的输入来源；请保留这些节点，另建后续工作。");
        return new Changes(Set.copyOf(added),Set.copyOf(changed),Set.copyOf(removed),Set.copyOf(affected),Set.copyOf(protectedNodes));
    }
    public static Set<String> descendants(WorkflowGraph graph,String key) {
        var result=new LinkedHashSet<String>();result.add(key);expand(result,graph,false);result.remove(key);return Set.copyOf(result);
    }
    /** A planning output can replace only its own downstream area; it cannot grant itself more scope. */
    public static void planningScope(WorkflowGraph before,WorkflowGraph after,String source) {
        var old=index(before);var next=index(after);var scope=descendants(before,source);var newScope=descendants(after,source);
        if(!old.containsKey(source) || !next.containsKey(source) || !before.inputs().equals(after.inputs()))throw new IllegalArgumentException("候选计划不能改变来源节点或公共资料声明。");
        for(var key:old.keySet())if(!scope.contains(key) && (!old.get(key).equals(next.get(key)) || !incoming(before,key).equals(incoming(after,key))))
            throw new IllegalArgumentException("候选计划只能调整来源节点之后的工作，不能改动其他区域。");
        for(var key:next.keySet())if(!old.containsKey(key) || scope.contains(key))if(!newScope.contains(key))
            throw new IllegalArgumentException("新增和保留的规划节点必须依赖来源节点，不能成为无关工作。");
    }
    private static Map<String,WorkflowGraph.Node> index(WorkflowGraph graph) { var result=new LinkedHashMap<String,WorkflowGraph.Node>();graph.nodes().forEach(node->result.put(node.id(),node));return result; }
    private static Set<WorkflowGraph.Edge> incoming(WorkflowGraph graph,String key) { return Set.copyOf(graph.edges().stream().filter(edge->edge.to().equals(key)).toList()); }
    private static void expand(Set<String> result,WorkflowGraph graph,boolean ancestors) {
        boolean changed;do { changed=false;for(var edge:graph.edges())if(result.contains(ancestors?edge.to():edge.from()))changed|=result.add(ancestors?edge.from():edge.to()); }while(changed);
    }
}
