package io.opencode.loopper.workflow;

import java.util.List;
import java.util.Set;

/** A fixed committed tree for read-only analysis; it does not qualify as a writer CODE delivery. */
public final class WorkflowRepositorySnapshot {
    public static final String MODULE="system.repository.snapshot",TYPE="REPOSITORY_SOURCE";
    private WorkflowRepositorySnapshot() { }
    public record Reference(int version,String type,String snapshotId,String sha256) { }
    public record File(String path,String blobSha,String mode,long sizeBytes,String limitation) { }
    public record Manifest(int version,String type,String nodeRunId,String branchId,String commitSha,String treeSha,String projectPrefix,List<File> files) {
        public Manifest {files=List.copyOf(files);}
    }
    public static int require(WorkflowGraph.Node node) {
        if(node.kind()!=WorkflowGraph.NodeKind.SYSTEM||!MODULE.equals(node.moduleId())||node.moduleVersion()!=1
                ||node.roleId()!=null||node.roleRevisionId()!=null||node.completion()==null
                ||!Set.of(WorkflowGraph.CompletionKind.VERIFIED,WorkflowGraph.CompletionKind.DELIVERABLES).contains(node.completion().kind())
                ||!node.outcomes().isEmpty()||node.inputs().size()!=1||!node.inputs().getFirst().name().equals("branch")
                ||node.inputs().getFirst().kind()!=WorkflowGraph.DataKind.TEXT||!node.inputs().getFirst().required()
                ||node.outputs().size()!=3
                ||node.outputs().stream().noneMatch(o->o.name().equals("source")&&o.kind()==WorkflowGraph.DataKind.DOCUMENT&&!o.required())
                ||node.outputs().stream().noneMatch(o->o.name().equals("report")&&o.kind()==WorkflowGraph.DataKind.JSON&&o.required())
                ||node.outputs().stream().noneMatch(o->o.name().equals("summary")&&o.kind()==WorkflowGraph.DataKind.TEXT&&o.required()))
            throw new IllegalArgumentException("固定分支代码需要分支输入，以及代码资料、采集报告和说明三个交付物。");
        try{int seconds=Integer.parseInt(node.parameters().getOrDefault("repositoryTimeoutSeconds","180"));if(seconds<10||seconds>600)throw new IllegalArgumentException();return seconds;}
        catch(RuntimeException invalid){throw new IllegalArgumentException("代码采集时限应为 10–600 秒。");}
    }
}
