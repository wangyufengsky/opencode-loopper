package io.opencode.loopper.workflow;

import java.util.*;

/** Full historical evidence, independent of a tree-only repository input and writer CODE. */
public final class WorkflowHistorySnapshot {
    public static final String MODULE="system.git.history",TYPE="GIT_HISTORY";
    public static final int MAX_FILE_BYTES=320_000_000;
    private WorkflowHistorySnapshot() { }
    public record Reference(int version,String type,String snapshotId,String sha256) { }
    public record File(String path,long sizeBytes,String sha256) { }
    public record Manifest(int version,String type,String nodeRunId,String branchId,String commitSha,String projectPrefix,
                           String startDate,String endDate,String timezone,String evidenceSha256,int commitCount,long changeCount,
                           long excludedCount,List<File> files) {
        public Manifest { files=List.copyOf(files); }
    }
    public static int require(WorkflowGraph.Node node) {
        if(node.kind()!=WorkflowGraph.NodeKind.SYSTEM||!MODULE.equals(node.moduleId())||node.moduleVersion()!=1
                ||node.roleId()!=null||node.roleRevisionId()!=null||node.completion()==null
                ||!Set.of(WorkflowGraph.CompletionKind.VERIFIED,WorkflowGraph.CompletionKind.DELIVERABLES).contains(node.completion().kind())
                ||!node.outcomes().isEmpty()||node.inputs().size()!=3
                ||!node.inputs().stream().map(WorkflowGraph.Input::name).collect(java.util.stream.Collectors.toSet()).equals(Set.of("branch","startDate","endDate"))
                ||node.inputs().stream().anyMatch(i->i.kind()!=WorkflowGraph.DataKind.TEXT||!i.required())||node.outputs().size()!=3
                ||node.outputs().stream().noneMatch(o->o.name().equals("source")&&o.kind()==WorkflowGraph.DataKind.DOCUMENT&&!o.required())
                ||node.outputs().stream().noneMatch(o->o.name().equals("report")&&o.kind()==WorkflowGraph.DataKind.JSON&&o.required())
                ||node.outputs().stream().noneMatch(o->o.name().equals("summary")&&o.kind()==WorkflowGraph.DataKind.TEXT&&o.required()))
            throw new IllegalArgumentException("历史采集需要明确分支、开始及结束日期，并保留历史资料、采集报告和说明。");
        try{int seconds=Integer.parseInt(node.parameters().getOrDefault("historyTimeoutSeconds","600"));if(seconds<10||seconds>600)throw new IllegalArgumentException();return seconds;}
        catch(RuntimeException invalid){throw new IllegalArgumentException("历史采集时限应为 10–600 秒。");}
    }
}
