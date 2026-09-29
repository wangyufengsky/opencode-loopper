package io.opencode.loopper.service.workflow;

import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.service.*;
import io.opencode.loopper.template.SourceManifest;
import io.opencode.loopper.workflow.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Frozen source identity survives retries; all file I/O belongs to the executor outside these transactions. */
@Service
@Transactional(readOnly=true)
public class WorkflowSourceStore {
    private final WorkflowNodeActions actions;
    private final WorkflowNodeRuns nodes;
    private final WorkflowEncoding encoding;
    private final WorkflowCommands commands;
    private final WorkflowSourceMapper mapper;
    private final LoopperMapper domain;
    public WorkflowSourceStore(WorkflowNodeActions actions,WorkflowNodeRuns nodes,WorkflowEncoding encoding,
            WorkflowCommands commands,WorkflowSourceMapper mapper,LoopperMapper domain) {
        this.actions=actions;this.nodes=nodes;this.encoding=encoding;this.commands=commands;this.mapper=mapper;this.domain=domain;
    }
    public record Context(WorkflowExecutionRows.Attempt attempt,WorkflowSourceMapper.Snapshot snapshot) { }
    @Transactional
    public WorkflowNodeActions.Receipt dispatch(String id,String key,WorkflowNodeActions.Start request,WorkflowDispatch.Permit permit) {
        String digest=encoding.digest("NODE_SOURCE_START",id+"/"+key,request);
        var replay=commands.replay(request.requestKey(),digest,WorkflowNodeActions.Receipt.class);if(replay.isPresent())return replay.get();
        var admission=actions.admit(id,key,request,permit);
        WorkflowSourceSnapshot.Purpose purpose;
        try{purpose=WorkflowSourceSnapshot.require(admission.definition());}
        catch(IllegalArgumentException invalid){throw new BadRequestException("WORKFLOW_SOURCE_INVALID",invalid.getMessage());}
        var input=admission.inputs().values().stream().filter(value->value.name().equals("path")).findFirst().orElseThrow(WorkflowCommands::conflict);
        if(!input.content().isString() || input.content().asString().isBlank() || input.content().asString().length()>2048)
            throw new BadRequestException("WORKFLOW_SOURCE_PATH_REQUIRED","请输入项目内的源码文件或目录。");
        String path=input.content().asString();var previous=mapper.find(admission.node().id());
        if(previous.isPresent()) {
            if(!previous.get().sourcePath().equals(path)||!previous.get().purpose().equals(purpose.name()))throw WorkflowCommands.conflict();
        } else {
            var project=domain.findProject(admission.owner().projectId()).orElseThrow(WorkflowCommands::conflict);
            if(mapper.insert(new WorkflowSourceMapper.Snapshot(admission.node().id(),id,project.id(),project.rootPath(),path,purpose.name(),null,null,Instant.now().toString(),null))!=1)
                throw WorkflowCommands.conflict();
        }
        var attempt=nodes.begin(admission.node(),admission.owner().headRevision(),admission.inputs(),WorkflowSourceSnapshot.ADAPTER,null);
        nodes.transition(attempt,WorkflowAttemptState.RUNNING,LifecycleEvent.START);
        return actions.acknowledge(request.requestKey(),digest,"SOURCE_START",id,key,attempt.id());
    }
    public Context context(String id) {
        var attempt=nodes.attempt(id);if(WorkflowAttemptState.valueOf(attempt.state()).terminal())return null;
        if(!attempt.adapterKey().equals(WorkflowSourceSnapshot.ADAPTER)||!attempt.state().equals("RUNNING"))throw WorkflowCommands.conflict();
        nodes.active(attempt);WorkflowSourceSnapshot.require(nodes.definition(nodes.requireNode(attempt.nodeRunId())));
        return new Context(attempt,mapper.find(attempt.nodeRunId()).orElseThrow(WorkflowCommands::conflict));
    }
    @Transactional
    public WorkflowSourceMapper.Snapshot plan(Context context,SourceManifest manifest) {
        nodes.active(context.attempt());String body=encoding.encode(manifest),sha=WorkflowEncoding.hash(body);
        var row=mapper.find(context.snapshot().nodeRunId()).orElseThrow(WorkflowCommands::conflict);
        if(row.manifestJson()!=null) {
            if(!row.manifestJson().equals(body)||!row.manifestSha256().equals(sha))
                throw new ConflictException("WORKFLOW_SOURCE_CHANGED","冻结源码正文尚未保存完整，当前项目已经变化。请恢复原源码，或修改计划创建新节点采集新版本。");
        } else if(mapper.plan(row.nodeRunId(),body,sha)!=1)throw WorkflowCommands.conflict();
        return mapper.find(row.nodeRunId()).orElseThrow(WorkflowCommands::conflict);
    }
    @Transactional
    public void finish(Context context,boolean success,String code) {
        var current=nodes.attempt(context.attempt().id());if(WorkflowAttemptState.valueOf(current.state()).terminal())return;
        nodes.active(context.attempt());var row=mapper.find(context.snapshot().nodeRunId()).orElseThrow(WorkflowCommands::conflict);
        var manifest=manifest(row);var values=new LinkedHashMap<String,WorkflowDelivery.Value>();
        String summary=success?"源码已冻结，后续节点读取同一版资料。":"源码冻结未完成，请查看采集报告后处理原节点。";
        var report=new LinkedHashMap<String,Object>();report.put("version",1);report.put("type","SOURCE_SNAPSHOT");report.put("complete",success);
        report.put("sourcePath",row.sourcePath());report.put("purpose",row.purpose());
        if(code!=null)report.put("code",code);
        if(manifest!=null){report.put("targetCount",manifest.targetCount());report.put("fileCount",manifest.files().size());
            report.put("excludedCount",manifest.files().stream().filter(f->f.exclusion()!=null).count());
            report.put("incompleteCount",manifest.files().stream().filter(f->f.target()&&SourceTreeCapture.unresolved(f.exclusion())).count());
            report.put("exclusions",manifest.files().stream().filter(f->f.exclusion()!=null)
                    .sorted(Comparator.comparing((SourceManifest.File f)->!(f.target()&&SourceTreeCapture.unresolved(f.exclusion()))).thenComparing(SourceManifest.File::path))
                    .limit(20).map(f->Map.of("path",f.path().length()>2048?f.path().substring(0,2048)+"…":f.path(),"target",f.target(),"reason",f.exclusion())).toList());}
        if(success) {
            if(manifest==null || !complete(manifest))throw WorkflowCommands.conflict();
            if(row.readyAt()==null && mapper.ready(row.nodeRunId(),Instant.now().toString())!=1)throw WorkflowCommands.conflict();
            values.put("source",value(WorkflowGraph.DataKind.DOCUMENT,new WorkflowSourceSnapshot.Reference(1,row.nodeRunId(),row.manifestSha256())));
        }
        values.put("summary",value(WorkflowGraph.DataKind.TEXT,summary));values.put("report",value(WorkflowGraph.DataKind.JSON,report));
        nodes.accept(current,new WorkflowDelivery(summary,null,values));nodes.stopSource(current);
        nodes.finish(current,success?WorkflowAttemptState.SUCCEEDED:WorkflowAttemptState.FAILED);actions.settle(row.requirementId(),!success,current.id());
    }
    public SourceManifest manifest(WorkflowSourceMapper.Snapshot row) {
        return WorkflowSourceRecords.decode(row,encoding);
    }
    public static boolean complete(SourceManifest manifest){return manifest.targetCount()>0&&manifest.files().stream().noneMatch(f->f.target()&&SourceTreeCapture.unresolved(f.exclusion()));}
    private WorkflowDelivery.Value value(WorkflowGraph.DataKind kind,Object content){return new WorkflowDelivery.Value(kind,encoding.decode(encoding.encode(content),tools.jackson.databind.JsonNode.class));}
}
