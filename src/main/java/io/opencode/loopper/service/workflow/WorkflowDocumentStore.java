package io.opencode.loopper.service.workflow;

import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.service.*;
import io.opencode.loopper.workflow.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** SQLite owns final document bytes; publishing the files, delivery and completion is one atomic action. */
@Service
@Transactional(readOnly=true)
public class WorkflowDocumentStore {
    private final WorkflowNodeActions actions;
    private final WorkflowNodeRuns nodes;
    private final WorkflowEncoding encoding;
    private final WorkflowCommands commands;
    private final WorkflowDocumentMapper mapper;
    private final WorkflowPlans plans;
    private final WorkflowHistoryReportFormats reportFormats;
    private final WorkflowSnapshotReportNames snapshotNames;
    public WorkflowDocumentStore(WorkflowNodeActions actions,WorkflowNodeRuns nodes,WorkflowEncoding encoding,WorkflowCommands commands,WorkflowDocumentMapper mapper,WorkflowPlans plans,WorkflowHistoryReportFormats reportFormats,WorkflowSnapshotReportNames snapshotNames){this.snapshotNames=snapshotNames;this.reportFormats=reportFormats;this.actions=actions;this.nodes=nodes;this.encoding=encoding;this.commands=commands;this.mapper=mapper;this.plans=plans;}
    public record Context(WorkflowExecutionRows.Attempt attempt,WorkflowGraph.Node node,WorkflowDelivery.Inputs inputs,String project) { }
    @Transactional
    public WorkflowNodeActions.Receipt dispatch(String id,String key,WorkflowNodeActions.Start request,WorkflowDispatch.Permit permit) {
        String digest=encoding.digest("NODE_DOCUMENT_START",id+"/"+key,request);var replay=commands.replay(request.requestKey(),digest,WorkflowNodeActions.Receipt.class);if(replay.isPresent())return replay.get();
        var admission=actions.admit(id,key,request,permit);contract(admission.definition());
        var attempt=nodes.begin(admission.node(),admission.owner().headRevision(),admission.inputs(),WorkflowDocument.adapter(admission.definition()),null);
        nodes.transition(attempt,WorkflowAttemptState.RUNNING,LifecycleEvent.START);
        snapshotNames.begin(attempt,admission.definition(),admission.inputs(),admission.owner().projectId());
        reportFormats.begin(attempt,admission.definition(),admission.inputs(),admission.owner().projectId());
        return actions.acknowledge(request.requestKey(),digest,"DOCUMENT_START",id,key,attempt.id());
    }
    public Context context(String id) {
        var attempt=nodes.attempt(id);if(WorkflowAttemptState.valueOf(attempt.state()).terminal())return null;
        if(!attempt.state().equals("RUNNING")||!Set.of(WorkflowDocument.ADAPTER,WorkflowDocument.ASSESSMENT_ADAPTER,WorkflowHistoryReport.ADAPTER,WorkflowSnapshotReport.ADAPTER).contains(attempt.adapterKey()))throw WorkflowCommands.conflict();
        nodes.active(attempt);var node=nodes.requireNode(attempt.nodeRunId());var definition=nodes.definition(node);contract(definition);if(!attempt.adapterKey().equals(WorkflowDocument.adapter(definition)))throw WorkflowCommands.conflict();
        return new Context(attempt,definition,nodes.inputs(attempt),plans.require(node.requirementId()).projectId());
    }
    @Transactional
    public void finish(Context context,WorkflowDocumentBuilder.Result result,String code) {
        var current=nodes.attempt(context.attempt().id());if(WorkflowAttemptState.valueOf(current.state()).terminal())return;
        nodes.active(context.attempt());String id=current.id(),now=Instant.now().toString();boolean success=result!=null;
        var outputs=new LinkedHashMap<String,WorkflowDelivery.Value>();var report=new LinkedHashMap<String,Object>();
        String type=WorkflowDocument.type(context.node());report.put("version",1);report.put("type",type);report.put("complete",success);
        if(code!=null)report.put("code",code);
        if(success) {
            var files=result.files().entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .map(entry->new WorkflowDocument.File(entry.getKey(),entry.getValue().getBytes(StandardCharsets.UTF_8).length,WorkflowEncoding.hash(entry.getValue()))).toList();
            if(files.isEmpty()||files.size()>WorkflowDocumentPaths.limit(type)||files.stream().mapToLong(WorkflowDocument.File::sizeBytes).sum()>64L*1024*1024)
                throw new BadRequestException("SOURCE_ARTIFACT_LIMIT","文档文件超过存储上限，请保留设计成果并缩小汇总范围。");
            files.forEach(file->WorkflowDocumentPaths.require(type,file.path()));
            String manifest=encoding.encode(new WorkflowDocument.Manifest(1,type,files)),hash=WorkflowEncoding.hash(manifest);
            if(manifest.getBytes(StandardCharsets.UTF_8).length>WorkflowDocumentPaths.manifestLimit(type))throw new BadRequestException("SOURCE_ARTIFACT_LIMIT","文档清单超过容量，请缩小明确范围。");
            if(mapper.insert(new WorkflowDocumentMapper.Document(id,manifest,hash,now))!=1)throw WorkflowCommands.conflict();
            for(var file:files)if(mapper.insertFile(new WorkflowDocumentMapper.File(id,file.path(),file.sizeBytes(),file.sha256(),result.files().get(file.path())))!=1)throw WorkflowCommands.conflict();
            outputs.put("document",value(WorkflowGraph.DataKind.DOCUMENT,new WorkflowDocument.Reference(1,type,id,hash)));
            report.put("sourceCount",result.sourceCount());report.put("draftCount",result.draftCount());report.put("reviewedCount",result.reviewedCount());
            report.putAll(result.details());report.put("reviseCount",result.reviseCount());report.put("reviewPolicy",result.policy());report.put("fileCount",files.size());
        }
        String summary=WorkflowSnapshotReport.TYPE.equals(type)?(success?"完整版本审查报告已生成，可预览或下载；分析与复核状态见报告。":"版本报告尚未生成，请检查全部分析及适用的复核结果。") : WorkflowHistoryReport.TYPE.equals(type)
                ?(success?"完整历史报告已生成，可预览或下载。":"历史报告尚未生成，请检查全部分析的固定输入及覆盖范围。")
                :(success?"固定版本文档已生成，可下载；复核情况见汇总报告。":"文档尚未生成，请检查覆盖范围、输入和复核策略。");
        outputs.put("summary",value(WorkflowGraph.DataKind.TEXT,summary));outputs.put("report",value(WorkflowGraph.DataKind.JSON,report));
        nodes.accept(current,new WorkflowDelivery(summary,null,outputs));nodes.stopDocument(current);
        nodes.finish(current,success?WorkflowAttemptState.SUCCEEDED:WorkflowAttemptState.FAILED);actions.settle(context.inputs().requirementId(),!success,id);
    }
    private void contract(WorkflowGraph.Node node){try{WorkflowDocument.require(node);}catch(IllegalArgumentException invalid){throw new BadRequestException("WORKFLOW_DOCUMENT_INVALID",invalid.getMessage());}}
    private WorkflowDelivery.Value value(WorkflowGraph.DataKind kind,Object object){return new WorkflowDelivery.Value(kind,encoding.decode(encoding.encode(object),tools.jackson.databind.JsonNode.class));}
}
