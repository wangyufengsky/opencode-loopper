package io.opencode.loopper.workflow;

import java.util.*;

/** Specialized test writer; code and scope proof are produced by the stopped program adapter. */
public final class WorkflowTestWrite {
    public static final String MODULE="source.test-write", TYPE="SOURCE_TEST_SCOPE";
    private WorkflowTestWrite(){ }
    public static boolean supports(String module){return MODULE.equals(module);}
    public static void require(WorkflowGraph.Node node) {
        if(!supports(node.moduleId())||node.kind()!=WorkflowGraph.NodeKind.WORK||node.moduleVersion()!=1
                ||node.completion()==null||node.completion().kind()!=WorkflowGraph.CompletionKind.DELIVERABLES||!node.outcomes().isEmpty())throw invalid();
        for(var name:List.of("source","profile","design"))if(node.inputs().stream().filter(i->i.name().equals(name)&&i.kind()==(name.equals("source")?WorkflowGraph.DataKind.DOCUMENT:WorkflowGraph.DataKind.JSON)&&i.required()&&i.source()==WorkflowGraph.InputSource.NODE).count()!=1)throw invalid();
        if(node.inputs().stream().anyMatch(i->!Set.of("source","profile","design").contains(i.name())&&!Set.of(WorkflowGraph.DataKind.TEXT,WorkflowGraph.DataKind.CODE).contains(i.kind()))||node.outputs().size()!=3)throw invalid();
        for(var name:List.of("code","scope","summary"))if(node.outputs().stream().noneMatch(o->o.name().equals(name)&&o.required()&&o.kind()==(name.equals("code")?WorkflowGraph.DataKind.CODE:name.equals("scope")?WorkflowGraph.DataKind.JSON:WorkflowGraph.DataKind.TEXT)))throw invalid();
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("单测编写需要同版源码、原生测试配置与场景设计，并交付固定代码、程序范围检查及说明。");}
}
