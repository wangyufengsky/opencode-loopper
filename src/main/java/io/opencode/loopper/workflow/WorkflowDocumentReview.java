package io.opencode.loopper.workflow;

import java.util.*;

/** Direct document-to-code assessment and an independently readable review are separate work modules. */
public final class WorkflowDocumentReview {
    public static final String AUTHOR="document.direct-review",REVIEW="document.direct-review-check";
    private WorkflowDocumentReview(){ }
    public static boolean supports(String module){return AUTHOR.equals(module)||REVIEW.equals(module);}
    public static int ordinal(WorkflowGraph.Node node) {
        try{int value=Integer.parseInt(node.parameters().getOrDefault("documentBatchOrdinal","0"));if(value<0||value>511)throw new IllegalArgumentException();return value;}
        catch(RuntimeException invalid){throw new IllegalArgumentException("评审批次序号应为 0–511。");}
    }
    public static void require(WorkflowGraph.Node node) {
        boolean review=REVIEW.equals(node.moduleId());ordinal(node);
        if(!supports(node.moduleId())||node.moduleVersion()!=1||node.kind()!=WorkflowGraph.NodeKind.WORK||node.completion()==null
            ||(!review&&node.completion().kind()!=WorkflowGraph.CompletionKind.DELIVERABLES)
            ||review&&!Set.of(WorkflowGraph.CompletionKind.DELIVERABLES,WorkflowGraph.CompletionKind.OUTCOME).contains(node.completion().kind())
            ||node.inputs().stream().filter(i->i.name().equals("documents")&&i.kind()==WorkflowGraph.DataKind.DOCUMENT&&i.required()).count()!=1
            ||node.inputs().stream().filter(i->i.name().equals("code")&&i.kind()==WorkflowGraph.DataKind.DOCUMENT&&i.required()&&i.source()==WorkflowGraph.InputSource.NODE).count()!=1
            ||node.inputs().stream().anyMatch(i->!Set.of("documents","code").contains(i.name())&&i.kind()!=WorkflowGraph.DataKind.TEXT
                &&(!review||i.kind()!=WorkflowGraph.DataKind.JSON||!i.required()||i.source()!=WorkflowGraph.InputSource.NODE))
            ||review&&node.inputs().stream().noneMatch(i->i.name().equals("draft")&&i.kind()==WorkflowGraph.DataKind.JSON&&i.required()&&i.source()==WorkflowGraph.InputSource.NODE)
            ||node.outputs().size()!=2||node.outputs().stream().noneMatch(o->o.name().equals("summary")&&o.kind()==WorkflowGraph.DataKind.TEXT&&o.required())
            ||node.outputs().stream().noneMatch(o->o.name().equals(review?"review":"assessment")&&o.kind()==(review?WorkflowGraph.DataKind.DECISION:WorkflowGraph.DataKind.JSON)&&o.required())
            ||(!review&&!node.outcomes().isEmpty())||review&&(!new HashSet<>(node.outcomes()).equals(Set.of("PASS","REVISE"))||node.outcomes().size()!=2))
            throw new IllegalArgumentException("原文评审需要固定文档与分支代码；独立复核还需本批评审稿和通过/返修两个结果。");
    }
}
