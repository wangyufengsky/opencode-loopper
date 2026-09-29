package io.opencode.loopper.workflow;

import java.util.*;

/** Existing source-design semantics carried by an independently scoped workflow node. */
public final class WorkflowSourceDesign {
    public static final String AUTHOR="source.design",REVIEW="source.design-review";
    private WorkflowSourceDesign(){ }
    public static boolean supports(String module){return AUTHOR.equals(module)||REVIEW.equals(module);}
    public static void require(WorkflowGraph.Node node) {
        boolean review=REVIEW.equals(node.moduleId());
        if(!supports(node.moduleId())||node.kind()!=WorkflowGraph.NodeKind.WORK||node.moduleVersion()!=1
                ||node.completion()==null||(!review&&node.completion().kind()!=WorkflowGraph.CompletionKind.DELIVERABLES)
                ||review&&!Set.of(WorkflowGraph.CompletionKind.DELIVERABLES,WorkflowGraph.CompletionKind.OUTCOME).contains(node.completion().kind())
                ||node.inputs().stream().filter(i->i.name().equals("source")&&i.kind()==WorkflowGraph.DataKind.DOCUMENT&&i.required()&&i.source()==WorkflowGraph.InputSource.NODE).count()!=1
                ||node.inputs().stream().anyMatch(i->!i.name().equals("source")&&i.kind()!=WorkflowGraph.DataKind.TEXT
                    &&(!review||i.kind()!=WorkflowGraph.DataKind.JSON||!i.required()||i.source()!=WorkflowGraph.InputSource.NODE))
                ||review&&node.inputs().stream().noneMatch(i->i.name().equals("draft")&&i.kind()==WorkflowGraph.DataKind.JSON&&i.required()&&i.source()==WorkflowGraph.InputSource.NODE)
                ||node.outputs().size()!=2||node.outputs().stream().noneMatch(o->o.name().equals("summary")&&o.kind()==WorkflowGraph.DataKind.TEXT&&o.required())
                ||node.outputs().stream().noneMatch(o->o.name().equals(review?"review":"design")&&o.kind()==(review?WorkflowGraph.DataKind.DECISION:WorkflowGraph.DataKind.JSON)&&o.required())
                ||(!review&&!node.outcomes().isEmpty())||(review&&(!new HashSet<>(node.outcomes()).equals(Set.of("PASS","REVISE"))||node.outcomes().size()!=2)))
            throw new IllegalArgumentException("源码设计需要固定源码输入和结构化设计交付；复核还需要设计稿输入，以及通过/返修两个结果。");
    }
}
