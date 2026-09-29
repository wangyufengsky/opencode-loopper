package io.opencode.loopper.workflow;

import io.opencode.loopper.template.TemplateTaskDefinition;
import java.util.Set;

/** Report kind selects a fixed compiler; complete evidence coverage is independent of user review gates. */
public final class WorkflowHistoryReport {
    public static final String MODULE="system.history.report",ADAPTER="system.history.report.v1",TYPE="HISTORY_DOCUMENT";
    public static final String PLAN_MODULE="system.history.plan",PLAN_ADAPTER="system.history.plan.v1";
    private WorkflowHistoryReport(){ }
    public static TemplateTaskDefinition kind(WorkflowGraph.Node node) {
        try {
            var value=TemplateTaskDefinition.valueOf(node.parameters().getOrDefault("historyReportKind","CODE_REVIEW"));
            if(!Set.of(TemplateTaskDefinition.CODE_REVIEW,TemplateTaskDefinition.CONTRIBUTION_REPORT).contains(value))throw new IllegalArgumentException();
            return value;
        }catch(IllegalArgumentException invalid){throw new IllegalArgumentException("请选择历史提交审查或项目贡献周报。");}
    }
    public static void require(WorkflowGraph.Node node) {
        kind(node);
        if(!MODULE.equals(node.moduleId())||node.moduleVersion()!=1||node.kind()!=WorkflowGraph.NodeKind.SYSTEM||node.roleId()!=null||node.roleRevisionId()!=null
                ||node.completion()==null||node.completion().kind()!=WorkflowGraph.CompletionKind.DELIVERABLES||!node.outcomes().isEmpty()
                ||node.inputs().stream().filter(i->i.name().equals("source")&&i.kind()==WorkflowGraph.DataKind.DOCUMENT&&i.required()&&i.source()==WorkflowGraph.InputSource.NODE).count()!=1
                ||node.inputs().stream().anyMatch(i->!i.required()||i.source()!=WorkflowGraph.InputSource.NODE||!i.name().equals("source")&&i.kind()!=WorkflowGraph.DataKind.JSON)
                ||node.outputs().size()!=3
                ||node.outputs().stream().noneMatch(o->o.name().equals("document")&&o.kind()==WorkflowGraph.DataKind.DOCUMENT&&!o.required())
                ||node.outputs().stream().noneMatch(o->o.name().equals("report")&&o.kind()==WorkflowGraph.DataKind.JSON&&o.required())
                ||node.outputs().stream().noneMatch(o->o.name().equals("summary")&&o.kind()==WorkflowGraph.DataKind.TEXT&&o.required()))
            throw new IllegalArgumentException("历史报告需要固定历史、全部适用分析和文档、报告、说明交付；空范围仅绑定历史资料。");
    }
}
