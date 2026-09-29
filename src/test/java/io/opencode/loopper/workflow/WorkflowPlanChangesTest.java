package io.opencode.loopper.workflow;
import static org.assertj.core.api.Assertions.*;
import static io.opencode.loopper.workflow.WorkflowGraph.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class WorkflowPlanChangesTest {
    private Node node(String key,String task){return new Node(key,key,NodeKind.HUMAN,null,0,null,task,List.of(),List.of(new Output("result","结果",DataKind.TEXT,true)),List.of(),new Completion(CompletionKind.HUMAN,"检查",null),0,false,Map.of());}
    private WorkflowGraph graph(Node... nodes){return new WorkflowGraph(1,List.of(nodes),List.of(new Edge("ab","a","b",null)),List.of());}
    @Test void laterWorkMayChangeWhileRetainingAnActiveProducer() {
        var before=graph(node("a","原任务"),node("b","后续"));var after=graph(node("a","原任务"),node("b","改后续"));
        var changes=WorkflowPlanChanges.compare(before,after,Map.of("a",WorkflowNodeState.ACTIVE,"b",WorkflowNodeState.PENDING));
        assertThat(changes.changed()).containsExactly("b");assertThat(changes.protectedNodes()).containsExactly("a");
    }
    @Test void consumedAncestryCannotChangeEvenIfOnlyTheDescendantIsStillRetained() {
        var before=graph(node("a","原任务"),node("b","后续"));
        assertThatThrownBy(()->WorkflowPlanChanges.compare(before,graph(node("a","改上游"),node("b","后续")),Map.of("a",WorkflowNodeState.SUCCEEDED,"b",WorkflowNodeState.ACTIVE))).hasMessageContaining("已执行");
        var removed=new WorkflowGraph(1,List.of(node("b","后续")),List.of(),List.of());
        assertThatThrownBy(()->WorkflowPlanChanges.compare(before,removed,Map.of("a",WorkflowNodeState.SUCCEEDED,"b",WorkflowNodeState.ACTIVE))).hasMessageContaining("已执行");
        assertThatThrownBy(()->WorkflowPlanChanges.compare(before,removed,Map.of("a",WorkflowNodeState.ACTIVE,"b",WorkflowNodeState.PENDING))).hasMessageContaining("停止证明");
    }
    @Test void changedDependencyReopensSkippedDescendantsAndPreservesUnrelatedWork() {
        var before=new WorkflowGraph(1,List.of(node("a","a"),node("b","b"),node("c","c"),node("other","other")),List.of(new Edge("ab","a","b",null),new Edge("bc","b","c",null)),List.of());
        var after=new WorkflowGraph(1,before.nodes(),List.of(new Edge("ac","a","c",null),new Edge("cb","c","b",null)),List.of());
        var changes=WorkflowPlanChanges.compare(before,after,Map.of("b",WorkflowNodeState.SKIPPED,"c",WorkflowNodeState.SKIPPED));
        assertThat(changes.affected()).containsExactlyInAnyOrder("b","c");
    }
    @Test void planningScopeCannotRewriteTheProducerOrAnUnrelatedBranch() {
        var before=graph(node("a","设计"),node("b","后续"),node("other","独立"));
        WorkflowPlanChanges.planningScope(before,graph(node("a","设计"),node("b","规划后续"),node("other","独立")),"a");
        assertThatThrownBy(()->WorkflowPlanChanges.planningScope(before,graph(node("a","设计"),node("b","后续"),node("other","改了")),"a")).hasMessageContaining("其他区域");
        var detached=new WorkflowGraph(1,List.of(node("a","设计"),node("b","后续"),node("other","独立"),node("new","新工作")),before.edges(),List.of());
        assertThatThrownBy(()->WorkflowPlanChanges.planningScope(before,detached,"a")).hasMessageContaining("依赖来源");
    }
}
