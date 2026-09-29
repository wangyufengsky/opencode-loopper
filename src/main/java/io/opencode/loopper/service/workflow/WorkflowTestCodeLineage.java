package io.opencode.loopper.service.workflow;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.service.BadRequestException;
import io.opencode.loopper.workflow.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** Follows the workspace seed actually used by each successful writer, never an arbitrary CODE input. */
@Component
public final class WorkflowTestCodeLineage {
    private final WorkflowExecutionMapper nodes;
    private final WorkflowInputSnapshots inputs;
    private final WorkflowTestScopeMapper scopes;
    private final WorkflowCodeMapper codes;
    private final WorkflowWorkspaceMapper workspaces;
    private final WorkflowEncoding encoding;
    public WorkflowTestCodeLineage(WorkflowExecutionMapper nodes,WorkflowInputSnapshots inputs,WorkflowTestScopeMapper scopes,
            WorkflowCodeMapper codes,WorkflowWorkspaceMapper workspaces,WorkflowEncoding encoding) {
        this.nodes=nodes;this.inputs=inputs;this.scopes=scopes;this.codes=codes;this.workspaces=workspaces;this.encoding=encoding;
    }
    public record Entry(WorkflowDelivery.Input code,WorkflowDelivery.Input design){ }
    private record Writer(WorkflowDelivery.Inputs inputs,WorkflowGraph.Node node,WorkflowCodeMapper.Snapshot code,WorkflowWorkspaceMapper.Workspace workspace){ }
    public List<Entry> trace(WorkflowDelivery.Inputs values,WorkflowDelivery.Input code) {
        var result=new ArrayList<Entry>();var seen=new HashSet<String>();var current=code;
        while(current!=null) {
            if(result.size()>=256||!seen.add(current.attemptId()))throw invalid();
            var writer=writer(values,current);
            result.add(new Entry(current,named(writer.inputs(),"design")));
            current=seed(writer);
        }
        return List.copyOf(result);
    }
    private Writer writer(WorkflowDelivery.Inputs values,WorkflowDelivery.Input code) {
        if(code.kind()!=WorkflowGraph.DataKind.CODE||!"NODE".equals(code.source())||code.attemptId()==null||!"code".equals(code.outputName()))throw invalid();
        var attempt=nodes.attempt(code.attemptId()).orElseThrow(WorkflowTestCodeLineage::invalid);
        var owner=nodes.node(attempt.nodeRunId()).orElseThrow(WorkflowTestCodeLineage::invalid);
        if(!attempt.state().equals("SUCCEEDED")||!attempt.adapterKey().equals(WorkflowWriterLeases.ADAPTER)||nodes.stop(attempt.id()).isEmpty()
                ||!owner.requirementId().equals(values.requirementId())||!owner.id().equals(code.sourceId())||!WorkflowEncoding.hash(owner.definitionJson()).equals(owner.definitionSha256()))throw invalid();
        var definition=encoding.decode(owner.definitionJson(),WorkflowGraph.Node.class);
        try{WorkflowTestWrite.require(definition);}catch(IllegalArgumentException failure){throw invalid();}
        var original=inputs.materialize(inputs.snapshot(attempt));
        for(String name:List.of("source","profile"))if(!named(values,name).equals(named(original,name)))throw invalid();
        var saved=nodes.delivery(attempt.id()).orElseThrow(WorkflowTestCodeLineage::invalid);
        if(!saved.sha256().equals(code.sha256())||!WorkflowEncoding.hash(saved.contentJson()).equals(saved.sha256()))throw invalid();
        var output=encoding.decode(saved.contentJson(),WorkflowDelivery.class).outputs().get("code");
        if(output==null||output.kind()!=WorkflowGraph.DataKind.CODE||!output.content().equals(code.content()))throw invalid();
        var ref=reference(code);var snapshot=codes.snapshot(ref.snapshotId()).orElseThrow(WorkflowTestCodeLineage::invalid);
        var manifest=codes.manifest(ref.snapshotId()).orElseThrow(WorkflowTestCodeLineage::invalid);
        var scope=scopes.result(attempt.id()).orElseThrow(WorkflowTestCodeLineage::invalid);
        var workspace=workspaces.find(attempt.id()).orElseThrow(WorkflowTestCodeLineage::invalid);
        if(!snapshot.attemptId().equals(attempt.id())||!snapshot.requirementId().equals(values.requirementId())||!snapshot.inputsSha256().equals(attempt.inputsSha256())
                ||!scope.passed()||!scope.checkpointTree().equals(snapshot.resultTree())||!WorkflowEncoding.hash(scope.reportJson()).equals(scope.reportSha256())
                ||!manifest.sha256().equals(ref.sha256())||!WorkflowEncoding.hash(manifest.contentJson()).equals(ref.sha256())
                ||!workspace.requirementId().equals(values.requirementId())||!workspace.projectId().equals(snapshot.projectId())
                ||!workspace.baseTree().equals(snapshot.baseTree())||!Objects.equals(workspace.checkpointTree(),snapshot.resultTree())
                ||!workspace.state().equals("RELEASED")||workspace.releaseReceiptJson()==null)throw invalid();
        return new Writer(original,definition,snapshot,workspace);
    }
    private WorkflowDelivery.Input seed(Writer writer) {
        var available=writer.inputs().values().stream().filter(i->i.kind()==WorkflowGraph.DataKind.CODE).toList();
        String selected=writer.node().parameters().get("workspaceInput");var workspace=writer.workspace();
        if(available.isEmpty()&&selected==null) {
            if(workspace.seedSnapshotId()!=null||workspace.seedSha256()!=null||workspace.seedTree()!=null)throw invalid();
            return null;
        }
        if(selected==null&&available.size()!=1)throw invalid();
        var input=selected==null?available.getFirst():available.stream().filter(i->i.name().equals(selected)).findFirst().orElseThrow(WorkflowTestCodeLineage::invalid);
        var ref=reference(input);var seed=codes.snapshot(ref.snapshotId()).orElseThrow(WorkflowTestCodeLineage::invalid);
        if(!ref.snapshotId().equals(workspace.seedSnapshotId())||!ref.sha256().equals(workspace.seedSha256())||!seed.resultTree().equals(workspace.seedTree())
                ||!seed.baseTree().equals(writer.code().baseTree())||!seed.projectPrefix().equals(writer.code().projectPrefix())
                ||!seed.projectId().equals(writer.code().projectId())||!seed.rootFingerprint().equals(writer.code().rootFingerprint()))throw invalid();
        return input;
    }
    static boolean same(WorkflowDelivery.Input a,WorkflowDelivery.Input b) {
        return a.kind()==b.kind()&&Objects.equals(a.source(),b.source())&&Objects.equals(a.sourceId(),b.sourceId())&&Objects.equals(a.outputName(),b.outputName())
                &&Objects.equals(a.attemptId(),b.attemptId())&&Objects.equals(a.sha256(),b.sha256())&&Objects.equals(a.content(),b.content());
    }
    private WorkflowCodeSnapshot.Reference reference(WorkflowDelivery.Input input) {
        WorkflowCodeSnapshot.Reference ref;
        try{ref=encoding.decode(encoding.encode(input.content()),WorkflowCodeSnapshot.Reference.class);}catch(RuntimeException failure){throw invalid();}
        if(ref==null||ref.version()!=1||ref.snapshotId()==null||ref.sha256()==null)throw invalid();return ref;
    }
    private static WorkflowDelivery.Input named(WorkflowDelivery.Inputs values,String name){return values.values().stream().filter(i->i.name().equals(name)).findFirst().orElseThrow(WorkflowTestCodeLineage::invalid);}
    static BadRequestException invalid(){return new BadRequestException("WORKFLOW_TEST_CODE_LINEAGE_INVALID","最终测试代码必须沿实际工作区继承本次源码与配置下成功的单测编写成果；请核对代码输入及各批场景来源。");}
}
