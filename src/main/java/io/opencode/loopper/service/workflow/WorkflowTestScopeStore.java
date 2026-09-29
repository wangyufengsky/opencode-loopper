package io.opencode.loopper.service.workflow;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.service.SourceTestTree;
import io.opencode.loopper.workflow.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Immutable private baseline and stopped-writer scope proof; all I/O is owned by the executor. */
@Service
public class WorkflowTestScopeStore {
    private final WorkflowTestScopeMapper mapper;
    private final WorkflowModelStore models;
    private final WorkflowWorkspaceStore workspaces;
    private final WorkflowEncoding encoding;
    public WorkflowTestScopeStore(WorkflowTestScopeMapper mapper,WorkflowModelStore models,WorkflowWorkspaceStore workspaces,WorkflowEncoding encoding){this.mapper=mapper;this.models=models;this.workspaces=workspaces;this.encoding=encoding;}
    public record Context(WorkflowModelMapper.Launch model,WorkflowWorkspaceMapper.Workspace workspace,WorkflowWorkspaceStore.Context work){ }
    public Context context(String id){var model=models.require(id);return new Context(model,workspaces.require(id),workspaces.context(id));}
    public Optional<WorkflowTestScopeMapper.Baseline> find(String id){var value=mapper.baseline(id);value.ifPresent(v->{if(!WorkflowEncoding.hash(v.filesJson()).equals(v.sha256())||!v.inputsSha256().equals(models.attempt(models.require(id)).inputsSha256()))throw WorkflowCommands.conflict();});return value;}
    public Map<String,SourceTestTree.File> files(String body){return encoding.decode(body,FileMap.class).files();}
    private record FileMap(Map<String,SourceTestTree.File> files){ }
    @Transactional
    public WorkflowTestScopeMapper.Baseline reserve(Context expected,Map<String,SourceTestTree.File> files) {
        models.active(expected.model());var row=workspaces.require(expected.model().attemptId());
        if(!row.equals(expected.workspace())||!row.state().equals("READY")||!expected.model().state().equals("PREPARING")||expected.model().creationPlanJson()!=null)throw WorkflowCommands.conflict();
        WorkflowTestWrite.require(expected.work().definition());var old=find(row.attemptId());if(old.isPresent())return old.get();
        String body=encoding.encode(new FileMap(files));if(body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>8*1024*1024)throw SourceTestTree.failure("测试写入基线清单超过容量，请缩小项目范围");
        var value=new WorkflowTestScopeMapper.Baseline(row.attemptId(),expected.work().attempt().inputsSha256(),body,WorkflowEncoding.hash(body),Instant.now().toString());
        if(mapper.insertBaseline(value)!=1)throw WorkflowCommands.conflict();return value;
    }
    public Optional<WorkflowTestScopeMapper.Result> result(String id) {
        var value=mapper.result(id);value.ifPresent(v->{if(!WorkflowEncoding.hash(v.filesJson()).equals(v.filesSha256())||!WorkflowEncoding.hash(v.reportJson()).equals(v.reportSha256())||!v.checkpointTree().equals(workspaces.require(id).checkpointTree()))throw WorkflowCommands.conflict();});return value;
    }
    @Transactional
    public WorkflowTestScopeMapper.Result record(Context expected,Map<String,SourceTestTree.File> files,boolean passed,String message) {
        models.active(expected.model());var workspace=workspaces.require(expected.model().attemptId());
        if(!workspace.equals(expected.workspace())||!workspace.state().equals("FROZEN")||models.stopProof(workspace.attemptId()).isEmpty())throw WorkflowCommands.conflict();
        var old=result(workspace.attemptId());if(old.isPresent())return old.get();
        String body=encoding.encode(new FileMap(files));String report=encoding.encode(Map.of("version",1,"type",WorkflowTestWrite.TYPE,"passed",passed,"message",message,"testsExecuted",false));
        var result=new WorkflowTestScopeMapper.Result(workspace.attemptId(),workspace.checkpointTree(),body,WorkflowEncoding.hash(body),report,WorkflowEncoding.hash(report),passed,Instant.now().toString());
        if(mapper.insertResult(result)!=1)throw WorkflowCommands.conflict();return result;
    }
}
