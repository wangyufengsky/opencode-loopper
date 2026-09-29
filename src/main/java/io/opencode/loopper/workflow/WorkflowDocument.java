package io.opencode.loopper.workflow;

import java.util.*;

/** A server-produced immutable document package, distinct from source material and writer code trees. */
public final class WorkflowDocument {
    public static final String MODULE="system.source.design-document",ADAPTER="system.source.design-document.v1",TYPE="DESIGN_DOCUMENT";
    public static final String ASSESSMENT_MODULE="system.document.review-report",ASSESSMENT_ADAPTER="system.document.review-report.v1",ASSESSMENT_TYPE="ASSESSMENT_DOCUMENT";
    public static boolean type(String type){return TYPE.equals(type)||ASSESSMENT_TYPE.equals(type)||WorkflowHistoryReport.TYPE.equals(type)||WorkflowSnapshotReport.TYPE.equals(type);}
    public static String adapterForType(String type){if(!type(type))throw new IllegalArgumentException("未知文档类型");return WorkflowSnapshotReport.TYPE.equals(type)?WorkflowSnapshotReport.ADAPTER:WorkflowHistoryReport.TYPE.equals(type)?WorkflowHistoryReport.ADAPTER:TYPE.equals(type)?ADAPTER:ASSESSMENT_ADAPTER;}
    public static String type(WorkflowGraph.Node node){require(node);return WorkflowSnapshotReport.MODULE.equals(node.moduleId())?WorkflowSnapshotReport.TYPE:WorkflowHistoryReport.MODULE.equals(node.moduleId())?WorkflowHistoryReport.TYPE:ASSESSMENT_MODULE.equals(node.moduleId())?ASSESSMENT_TYPE:TYPE;}
    public static String adapter(WorkflowGraph.Node node){return adapterForType(type(node));}
    private WorkflowDocument(){ }
    public record Reference(int version,String type,String attemptId,String sha256) { }
    public record File(String path,long sizeBytes,String sha256) { }
    public record Manifest(int version,String type,List<File> files) {public Manifest{files=List.copyOf(files);}}
    public enum ReviewPolicy { REQUIRED, NONE }
    public static ReviewPolicy require(WorkflowGraph.Node node) {
        if(WorkflowSnapshotReport.MODULE.equals(node.moduleId()))return WorkflowSnapshotReport.require(node);
        if(WorkflowHistoryReport.MODULE.equals(node.moduleId())){WorkflowHistoryReport.require(node);return ReviewPolicy.NONE;}
        boolean assessment=ASSESSMENT_MODULE.equals(node.moduleId());
        if(node.kind()!=WorkflowGraph.NodeKind.SYSTEM||!Set.of(MODULE,ASSESSMENT_MODULE).contains(node.moduleId())||node.moduleVersion()!=1||node.roleId()!=null||node.roleRevisionId()!=null
                ||node.completion()==null||!Set.of(WorkflowGraph.CompletionKind.DELIVERABLES,WorkflowGraph.CompletionKind.VERIFIED).contains(node.completion().kind())
                ||!node.outcomes().isEmpty()||node.outputs().size()!=3
                ||node.outputs().stream().noneMatch(o->o.name().equals("document")&&o.kind()==WorkflowGraph.DataKind.DOCUMENT&&!o.required())
                ||node.outputs().stream().noneMatch(o->o.name().equals("report")&&o.kind()==WorkflowGraph.DataKind.JSON&&o.required())
                ||node.outputs().stream().noneMatch(o->o.name().equals("summary")&&o.kind()==WorkflowGraph.DataKind.TEXT&&o.required())
                ||node.inputs().stream().noneMatch(i->i.kind()==WorkflowGraph.DataKind.JSON)
                ||!assessment&&(node.inputs().stream().noneMatch(i->i.name().equals("source")&&i.kind()==WorkflowGraph.DataKind.DOCUMENT&&i.required()&&i.source()==WorkflowGraph.InputSource.NODE)
                    ||node.inputs().stream().anyMatch(i->i.source()!=WorkflowGraph.InputSource.NODE||!i.required()||!i.name().equals("source")&&!Set.of(WorkflowGraph.DataKind.JSON,WorkflowGraph.DataKind.DECISION).contains(i.kind())))
                ||assessment&&(node.inputs().stream().noneMatch(i->i.name().equals("documents")&&i.kind()==WorkflowGraph.DataKind.DOCUMENT&&i.required())
                    ||node.inputs().stream().noneMatch(i->i.name().equals("code")&&i.kind()==WorkflowGraph.DataKind.DOCUMENT&&i.required()&&i.source()==WorkflowGraph.InputSource.NODE)
                    ||node.inputs().stream().anyMatch(i->!i.required()||!Set.of("documents","code").contains(i.name())&&(i.source()!=WorkflowGraph.InputSource.NODE||!Set.of(WorkflowGraph.DataKind.JSON,WorkflowGraph.DataKind.DECISION).contains(i.kind())))))
            throw new IllegalArgumentException("文档汇总需要冻结源码、专业设计稿、可选的明确复核及固定文档/报告/说明交付。");
        try{return ReviewPolicy.valueOf(node.parameters().getOrDefault("reviewPolicy","REQUIRED"));}
        catch(IllegalArgumentException invalid){throw new IllegalArgumentException("请选择文档是否要求每份设计稿独立复核通过。");}
    }
}
