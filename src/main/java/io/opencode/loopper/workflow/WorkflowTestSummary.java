package io.opencode.loopper.workflow;

import java.util.*;

/** Explicit completion policy for one frozen source scope and one final CODE version. */
public final class WorkflowTestSummary {
    public static final String MODULE="system.source.test-summary",ADAPTER="system.source.test-summary.v1",TYPE="SOURCE_TEST_SUMMARY";
    public enum ReviewPolicy { NONE, SINGLE, DUAL }
    private WorkflowTestSummary(){ }
    public static ReviewPolicy require(WorkflowGraph.Node node) {
        if(node.kind()!=WorkflowGraph.NodeKind.SYSTEM||!MODULE.equals(node.moduleId())||node.moduleVersion()!=1||node.roleId()!=null||node.roleRevisionId()!=null
                ||node.completion()==null||!Set.of(WorkflowGraph.CompletionKind.VERIFIED,WorkflowGraph.CompletionKind.DELIVERABLES,WorkflowGraph.CompletionKind.OUTCOME).contains(node.completion().kind())
                ||node.inputs().size()<4||node.inputs().size()>64||node.outputs().size()!=2||node.outcomes().size()!=2||!new HashSet<>(node.outcomes()).equals(Set.of("PASS","FAIL")))throw invalid();
        for(var port:Map.of("source",WorkflowGraph.DataKind.DOCUMENT,"profile",WorkflowGraph.DataKind.JSON,"code",WorkflowGraph.DataKind.CODE).entrySet())
            if(node.inputs().stream().noneMatch(i->i.name().equals(port.getKey())&&i.kind()==port.getValue()&&i.source()==WorkflowGraph.InputSource.NODE&&i.required()))throw invalid();
        for(var input:node.inputs())if(!Set.of("source","profile","code").contains(input.name())&&(!input.required()||input.source()!=WorkflowGraph.InputSource.NODE
                ||!(input.name().startsWith("test_")&&input.kind()==WorkflowGraph.DataKind.JSON||input.name().startsWith("review_")&&input.kind()==WorkflowGraph.DataKind.DECISION)))throw invalid();
        if(node.inputs().stream().noneMatch(i->i.name().startsWith("test_")&&i.kind()==WorkflowGraph.DataKind.JSON))throw invalid();
        for(var port:Map.of("report",WorkflowGraph.DataKind.JSON,"summary",WorkflowGraph.DataKind.TEXT).entrySet())
            if(node.outputs().stream().noneMatch(o->o.name().equals(port.getKey())&&o.kind()==port.getValue()&&o.required()))throw invalid();
        try{return ReviewPolicy.valueOf(node.parameters().getOrDefault("reviewPolicy","DUAL"));}catch(IllegalArgumentException bad){throw invalid();}
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("单测汇总需要同版源码、配置、最终代码、各模块测试及所选复核，并明确不要求、单份或双份独立复核策略。");}
}
