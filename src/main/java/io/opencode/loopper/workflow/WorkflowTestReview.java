package io.opencode.loopper.workflow;

import io.opencode.loopper.template.SourceDesign;
import java.util.*;

/** Independent semantic assessment linked to exact native cases; execution facts remain server-owned. */
public final class WorkflowTestReview {
    public static final String MODULE="source.test-review",TYPE="SOURCE_TEST_REVIEW";
    private WorkflowTestReview(){ }
    public record Candidate(String reason,List<Mapping> scenarios){ }
    public record Mapping(String key,String assessment,String reason,List<String> testIds,List<SourceDesign.Reference> references){ }
    public static boolean supports(String module){return MODULE.equals(module);}
    public static void require(WorkflowGraph.Node node) {
        if(!supports(node.moduleId())||node.kind()!=WorkflowGraph.NodeKind.WORK||!Set.of(1,2).contains(node.moduleVersion())||node.inputs().size()!=5||node.outputs().size()!=2
                ||node.completion()==null||!Set.of(WorkflowGraph.CompletionKind.DELIVERABLES,WorkflowGraph.CompletionKind.OUTCOME).contains(node.completion().kind())
                ||node.outcomes().size()!=2||!new HashSet<>(node.outcomes()).equals(Set.of("PASS","REVISE")))throw invalid();
        for(var port:Map.of("source",WorkflowGraph.DataKind.DOCUMENT,"profile",WorkflowGraph.DataKind.JSON,"design",WorkflowGraph.DataKind.JSON,"code",WorkflowGraph.DataKind.CODE,"test",WorkflowGraph.DataKind.JSON).entrySet())
            if(node.inputs().stream().noneMatch(i->i.name().equals(port.getKey())&&i.kind()==port.getValue()&&i.source()==WorkflowGraph.InputSource.NODE&&i.required()))throw invalid();
        if(node.outputs().stream().noneMatch(o->o.name().equals("review")&&o.kind()==WorkflowGraph.DataKind.DECISION&&o.required())
                ||node.outputs().stream().noneMatch(o->o.name().equals("summary")&&o.kind()==WorkflowGraph.DataKind.TEXT&&o.required()))throw invalid();
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("单测场景复核需要同版源码、配置、场景、代码和实际测试报告，交付独立复核意见与说明。");}
}
