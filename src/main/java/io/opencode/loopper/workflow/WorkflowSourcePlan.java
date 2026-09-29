package io.opencode.loopper.workflow;

import java.util.*;

/** Deterministic planning produces a proposal, never authorization to run it. */
public final class WorkflowSourcePlan {
    public static final String MODULE="system.source.design-plan",ADAPTER="system.source.design-plan.v1";
    public static final String TEST_MODULE="system.source.test-plan",TEST_ADAPTER="system.source.test-plan.v1";
    public static final String DOCUMENT_MODULE="system.document.review-plan",DOCUMENT_ADAPTER="system.document.review-plan.v1";
    private WorkflowSourcePlan(){ }
    public static String adapter(WorkflowGraph.Node node){require(node);return WorkflowSnapshotReport.PLAN_MODULE.equals(node.moduleId())?WorkflowSnapshotReport.PLAN_ADAPTER:WorkflowHistoryReport.PLAN_MODULE.equals(node.moduleId())?WorkflowHistoryReport.PLAN_ADAPTER:DOCUMENT_MODULE.equals(node.moduleId())?DOCUMENT_ADAPTER:TEST_MODULE.equals(node.moduleId())?TEST_ADAPTER:ADAPTER;}
    public static void require(WorkflowGraph.Node node) {
        boolean test=TEST_MODULE.equals(node.moduleId()),document=DOCUMENT_MODULE.equals(node.moduleId());
        if(node.kind()!=WorkflowGraph.NodeKind.SYSTEM||!Set.of(MODULE,TEST_MODULE,DOCUMENT_MODULE,WorkflowHistoryReport.PLAN_MODULE,WorkflowSnapshotReport.PLAN_MODULE).contains(node.moduleId())||node.moduleVersion()!=1||node.roleId()!=null||node.roleRevisionId()!=null
                ||node.completion()==null||node.completion().kind()!=WorkflowGraph.CompletionKind.DELIVERABLES||!node.outcomes().isEmpty()
                ||node.inputs().size()!=((test||document)?2:1)||!document&&node.inputs().stream().noneMatch(i->i.name().equals("source")&&i.source()==WorkflowGraph.InputSource.NODE&&i.kind()==WorkflowGraph.DataKind.DOCUMENT&&i.required())
                ||test&&node.inputs().stream().noneMatch(i->i.name().equals("profile")&&i.source()==WorkflowGraph.InputSource.NODE&&i.kind()==WorkflowGraph.DataKind.JSON&&i.required())
                ||document&&(node.inputs().stream().noneMatch(i->i.name().equals("documents")&&i.kind()==WorkflowGraph.DataKind.DOCUMENT&&i.required())
                    ||node.inputs().stream().noneMatch(i->i.name().equals("code")&&i.source()==WorkflowGraph.InputSource.NODE&&i.kind()==WorkflowGraph.DataKind.DOCUMENT&&i.required()))
                ||node.outputs().size()!=3
                ||node.outputs().stream().noneMatch(o->o.name().equals("plan")&&o.kind()==WorkflowGraph.DataKind.PLAN&&!o.required())
                ||node.outputs().stream().noneMatch(o->o.name().equals("report")&&o.kind()==WorkflowGraph.DataKind.JSON&&o.required())
                ||node.outputs().stream().noneMatch(o->o.name().equals("summary")&&o.kind()==WorkflowGraph.DataKind.TEXT&&o.required()))
            throw new IllegalArgumentException("分批规划需要固定资料，以及候选计划、规划报告和说明交付。");
    }
}
