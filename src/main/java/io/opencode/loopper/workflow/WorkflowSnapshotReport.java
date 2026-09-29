package io.opencode.loopper.workflow;

/** Complete fixed-version reports distinguish required independent review from explicit user omission. */
public final class WorkflowSnapshotReport {
    public static final String MODULE="system.snapshot.report",ADAPTER="system.snapshot.report.v1",TYPE="SNAPSHOT_DOCUMENT";
    public static final String PLAN_MODULE="system.snapshot.plan",PLAN_ADAPTER="system.snapshot.plan.v1";
    private WorkflowSnapshotReport(){ }
    public static WorkflowDocument.ReviewPolicy require(WorkflowGraph.Node node) {
        if(!MODULE.equals(node.moduleId())||node.moduleVersion()!=1||node.kind()!=WorkflowGraph.NodeKind.SYSTEM||node.roleId()!=null||node.roleRevisionId()!=null
            ||node.completion()==null||node.completion().kind()!=WorkflowGraph.CompletionKind.DELIVERABLES||!node.outcomes().isEmpty()
            ||node.inputs().stream().filter(i->i.name().equals("source")&&i.kind()==WorkflowGraph.DataKind.DOCUMENT&&i.required()&&i.source()==WorkflowGraph.InputSource.NODE).count()!=1
            ||node.inputs().stream().anyMatch(i->i.source()!=WorkflowGraph.InputSource.NODE||!i.name().equals("source")&&i.kind()!=WorkflowGraph.DataKind.JSON)
            ||node.outputs().size()!=3
            ||node.outputs().stream().noneMatch(o->o.name().equals("document")&&o.kind()==WorkflowGraph.DataKind.DOCUMENT&&!o.required())
            ||node.outputs().stream().noneMatch(o->o.name().equals("report")&&o.kind()==WorkflowGraph.DataKind.JSON&&o.required())
            ||node.outputs().stream().noneMatch(o->o.name().equals("summary")&&o.kind()==WorkflowGraph.DataKind.TEXT&&o.required()))
            throw new IllegalArgumentException("版本报告需要固定资料、全部分析、适用的复核和文档、报告、说明交付。");
        try{return WorkflowDocument.ReviewPolicy.valueOf(node.parameters().getOrDefault("reviewPolicy","REQUIRED"));}
        catch(IllegalArgumentException invalid){throw new IllegalArgumentException("请选择候选问题是否需要独立复核。");}
    }
}
