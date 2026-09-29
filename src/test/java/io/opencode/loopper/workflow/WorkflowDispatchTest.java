package io.opencode.loopper.workflow;
import static org.assertj.core.api.Assertions.*;
import static io.opencode.loopper.workflow.WorkflowGraph.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class WorkflowDispatchTest {
    private Node node(String key,int retries) { return new Node(key,key,NodeKind.HUMAN,null,0,null,"检查",List.of(),List.of(new Output("result","结果",DataKind.TEXT,true)),List.of(),new Completion(CompletionKind.HUMAN,"确认",null),retries,false,Map.of()); }
    private WorkflowDispatch.Progress progress(String key,WorkflowNodeState state,int attempts) { return new WorkflowDispatch.Progress(key,state,attempts,null); }
    @Test void untilIncludesBothParentsAndExcludesUnrelatedSiblingAndDescendants() {
        var graph=new WorkflowGraph(1,List.of(node("a",0),node("b",0),node("join",0),node("other",0),node("later",0)),
                List.of(new Edge("aj","a","join",null),new Edge("bj","b","join",null),new Edge("jl","join","later",null)),List.of());
        assertThat(WorkflowDispatch.scope(graph,WorkflowDispatch.Mode.UNTIL,"join")).containsExactlyInAnyOrder("a","b","join");
        var progress=new HashMap<String,WorkflowDispatch.Progress>();graph.nodes().forEach(n->progress.put(n.id(),progress(n.id(),WorkflowNodeState.PENDING,0)));
        var scope=new WorkflowDispatch.Scope(WorkflowDispatch.Mode.UNTIL,"join",null,0);
        assertThat(WorkflowDispatch.next(graph,scope,progress).nodeKey()).isEqualTo("a");
        progress.put("a",progress("a",WorkflowNodeState.ACTIVE,1));assertThat(WorkflowDispatch.next(graph,scope,progress).nodeKey()).isEqualTo("b");
        progress.put("b",progress("b",WorkflowNodeState.ACTIVE,1));assertThat(WorkflowDispatch.next(graph,scope,progress).reason()).isEqualTo(WorkflowDispatch.Reason.RUNNING);
    }
    @Test void automaticRetriesAreBoundedAndExplicitRetryAllowsOnlyOneAdditionalOrdinal() {
        var graph=new WorkflowGraph(1,List.of(node("a",1),node("b",0)),List.of(),List.of());
        var progress=new HashMap<String,WorkflowDispatch.Progress>();progress.put("a",progress("run-a",WorkflowNodeState.FAILED,1));progress.put("b",progress("b",WorkflowNodeState.PENDING,0));
        var scope=new WorkflowDispatch.Scope(WorkflowDispatch.Mode.CONTINUOUS,null,null,0);
        assertThat(WorkflowDispatch.next(graph,scope,progress).nodeKey()).isEqualTo("a");
        progress.put("a",progress("run-a",WorkflowNodeState.FAILED,2));assertThat(WorkflowDispatch.next(graph,scope,progress).reason()).isEqualTo(WorkflowDispatch.Reason.RETRY_EXHAUSTED);
        var manual=new WorkflowDispatch.Scope(WorkflowDispatch.Mode.SINGLE,"a","run-a",3);assertThat(WorkflowDispatch.next(graph,manual,progress).nodeKey()).isEqualTo("a");
        progress.put("a",progress("run-a",WorkflowNodeState.FAILED,3));assertThat(WorkflowDispatch.next(graph,manual,progress).reason()).isEqualTo(WorkflowDispatch.Reason.RETRY_EXHAUSTED);
    }
    @Test void singleNeverRunsItsMissingPrerequisiteAndUnknownBranchOutcomeIsBlocked() {
        var graph=new WorkflowGraph(1,List.of(node("a",0),node("b",0)),List.of(new Edge("ab","a","b","pass")),List.of());
        var progress=Map.of("a",progress("a",WorkflowNodeState.PENDING,0),"b",progress("b",WorkflowNodeState.PENDING,0));
        var scope=new WorkflowDispatch.Scope(WorkflowDispatch.Mode.SINGLE,"b",null,0);
        assertThat(WorkflowDispatch.next(graph,scope,progress).reason()).isEqualTo(WorkflowDispatch.Reason.DEPENDENCY_BLOCKED);
        assertThat(WorkflowDispatch.next(graph,scope,Map.of("a",progress("a",WorkflowNodeState.SUCCEEDED,1),"b",progress.get("b"))).reason()).isEqualTo(WorkflowDispatch.Reason.DEPENDENCY_BLOCKED);
    }
}
