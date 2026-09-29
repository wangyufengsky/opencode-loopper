package io.opencode.loopper.workflow;

import java.util.Set;

/** Read-only source material is distinct from the complete CODE tree used to seed a writer. */
public final class WorkflowSourceSnapshot {
    public static final String MODULE="system.source.snapshot";
    public static final String ADAPTER="system.source.snapshot.v1";
    private WorkflowSourceSnapshot() { }
    public record Reference(int version,String snapshotId,String sha256) { }
    public enum Purpose { DESIGN, UNIT_TEST }
    public static Purpose require(WorkflowGraph.Node node) {
        if(node.kind()!=WorkflowGraph.NodeKind.SYSTEM || !MODULE.equals(node.moduleId()) || node.moduleVersion()!=1
                || node.roleId()!=null || node.roleRevisionId()!=null || node.completion()==null
                || !Set.of(WorkflowGraph.CompletionKind.DELIVERABLES,WorkflowGraph.CompletionKind.VERIFIED).contains(node.completion().kind())
                || !node.outcomes().isEmpty() || node.inputs().size()!=1
                || !node.inputs().getFirst().name().equals("path") || node.inputs().getFirst().kind()!=WorkflowGraph.DataKind.TEXT
                || !node.inputs().getFirst().required() || node.outputs().size()!=3
                || node.outputs().stream().noneMatch(o->o.name().equals("source")&&o.kind()==WorkflowGraph.DataKind.DOCUMENT&&!o.required())
                || node.outputs().stream().noneMatch(o->o.name().equals("report")&&o.kind()==WorkflowGraph.DataKind.JSON&&o.required())
                || node.outputs().stream().noneMatch(o->o.name().equals("summary")&&o.kind()==WorkflowGraph.DataKind.TEXT&&o.required()))
            throw new IllegalArgumentException("冻结源码需要一个必填路径输入，以及源码资料、采集报告和说明三个固定交付物。");
        try { return Purpose.valueOf(node.parameters().getOrDefault("sourcePurpose","DESIGN")); }
        catch(RuntimeException invalid){throw new IllegalArgumentException("请选择详细设计或单元测试的源码采集用途。");}
    }
}
