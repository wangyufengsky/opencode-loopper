package io.opencode.loopper.service.workflow;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.runtime.DurableCommandProtocol;
import io.opencode.loopper.service.*;
import io.opencode.loopper.workflow.*;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;

/** Database-only input freeze and acceptance; source I/O and result materialization precede this boundary. */
@Component
public final class WorkflowHistoryContract {
    private final WorkflowHistoryMapper mapper;
    private final LoopperMapper projects;
    private final WorkflowEncoding encoding;
    public WorkflowHistoryContract(WorkflowHistoryMapper mapper,LoopperMapper projects,WorkflowEncoding encoding){this.mapper=mapper;this.projects=projects;this.encoding=encoding;}
    public record Context(WorkflowHistoryMapper.Snapshot row,GitHistoryJobProtocol.Input input,int timeoutSeconds) { }
    public boolean supports(WorkflowGraph.Node node){return WorkflowHistorySnapshot.MODULE.equals(node.moduleId());}
    public void validate(WorkflowGraph.Node node,WorkflowDelivery.Inputs inputs) {
        try{WorkflowHistorySnapshot.require(node);selection(text(inputs,"branch"));dates(inputs);}
        catch(RuntimeException invalid){throw new BadRequestException("WORKFLOW_HISTORY_INVALID","请选择明确分支并填写有效的开始、结束日期（YYYY-MM-DD），结束日期不能早于开始日期。");}
    }
    public void admit(WorkflowExecutionRows.Attempt attempt,WorkflowNodeActions.Admission admission) {
        if(!supports(admission.definition()))return;
        var owner=admission.owner();var inputs=admission.inputs();String branch=text(inputs,"branch");var previous=mapper.find(attempt.nodeRunId());
        if(previous.isPresent()){context(attempt,admission.definition(),inputs);return;}
        var selected=selection(branch);var project=projects.findProject(owner.projectId()).orElseThrow(WorkflowCommands::conflict);
        var input=new GitHistoryJobProtocol.Input(new GitSnapshotJobProtocol.Input(attempt.nodeRunId(),project.rootPath(),selected.ref(),selected.remote()),text(inputs,"startDate"),text(inputs,"endDate"));
        if(mapper.insert(new WorkflowHistoryMapper.Snapshot(attempt.nodeRunId(),owner.id(),owner.projectId(),branch,encoding.encode(input),hash(input),null,null,Instant.now().toString()))!=1)throw WorkflowCommands.conflict();
    }
    public Context context(WorkflowExecutionRows.Attempt attempt,WorkflowGraph.Node node,WorkflowDelivery.Inputs inputs) {
        if(!supports(node))return null;validate(node,inputs);
        var row=mapper.find(attempt.nodeRunId()).orElseThrow(WorkflowCommands::conflict);var input=encoding.decode(row.inputJson(),GitHistoryJobProtocol.Input.class);
        if(!row.branchId().equals(text(inputs,"branch"))||!input.startDate().equals(text(inputs,"startDate"))||!input.endDate().equals(text(inputs,"endDate"))
                ||!row.inputSha256().equals(hash(input))||!input.nodeId().equals(attempt.nodeRunId()))throw WorkflowCommands.conflict();
        return new Context(row,input,WorkflowHistorySnapshot.require(node));
    }
    public WorkflowCommandContract.Evaluation evaluate(WorkflowCommandStore.Context context,DurableCommandProtocol.Result result,WorkflowHistorySnapshot.Manifest manifest) {
        var history=context.history();boolean success=result.successful()&&manifest!=null;
        var values=new LinkedHashMap<String,WorkflowDelivery.Value>();var report=new LinkedHashMap<String,Object>();
        report.put("version",1);report.put("type",WorkflowHistorySnapshot.TYPE);report.put("complete",success);report.put("branchId",history.row().branchId());
        report.put("startDate",history.input().startDate());report.put("endDate",history.input().endDate());report.put("timezone","Asia/Shanghai");
        if(success) {
            if(manifest.version()!=1||!manifest.type().equals(WorkflowHistorySnapshot.TYPE)||!manifest.nodeRunId().equals(history.row().nodeRunId())
                    ||!manifest.branchId().equals(history.row().branchId())||!manifest.startDate().equals(history.input().startDate())||!manifest.endDate().equals(history.input().endDate())
                    ||manifest.files().size()!=manifest.commitCount()+1)throw WorkflowCommands.conflict();
            String body=encoding.encode(manifest),sha=WorkflowEncoding.hash(body);var current=mapper.find(history.row().nodeRunId()).orElseThrow(WorkflowCommands::conflict);
            if(current.manifestJson()==null){if(mapper.manifest(current.nodeRunId(),body,sha)!=1)throw WorkflowCommands.conflict();}
            else if(!current.manifestJson().equals(body)||!current.manifestSha256().equals(sha))throw WorkflowCommands.conflict();
            values.put("source",value(WorkflowGraph.DataKind.DOCUMENT,new WorkflowHistorySnapshot.Reference(1,WorkflowHistorySnapshot.TYPE,current.nodeRunId(),sha)));
            report.put("commitSha",manifest.commitSha());report.put("projectPrefix",manifest.projectPrefix());report.put("commitCount",manifest.commitCount());
            report.put("changeCount",manifest.changeCount());report.put("excludedCount",manifest.excludedCount());
        }else {
            String code=result.timedOut()?"TEMPLATE_GIT_TIMEOUT":"WORKFLOW_HISTORY_CAPTURE_FAILED";
            var match=java.util.regex.Pattern.compile("LOOPPER_GIT_HISTORY_FAILURE:([A-Z][A-Z0-9_]{0,100})").matcher(result.output());if(match.find())code=match.group(1);report.put("code",code);
        }
        String summary=success?"指定分支和日期范围的 Git 历史已固定，后续节点读取同一份证据。":"历史采集未完成，请查看原因后恢复原节点。";
        values.put("summary",value(WorkflowGraph.DataKind.TEXT,summary));values.put("report",value(WorkflowGraph.DataKind.JSON,report));
        return new WorkflowCommandContract.Evaluation(new WorkflowDelivery(summary,null,values),success);
    }
    private WorkflowDelivery.Value value(WorkflowGraph.DataKind kind,Object content){return new WorkflowDelivery.Value(kind,encoding.decode(encoding.encode(content),tools.jackson.databind.JsonNode.class));}
    private static String text(WorkflowDelivery.Inputs inputs,String name) {
        var value=inputs.values().stream().filter(i->i.name().equals(name)).findFirst().orElseThrow(WorkflowCommands::conflict).content();if(!value.isString())throw WorkflowCommands.conflict();return value.asString();
    }
    private static void dates(WorkflowDelivery.Inputs inputs) {
        String start=text(inputs,"startDate"),end=text(inputs,"endDate");if(!start.matches("\\d{4}-\\d{2}-\\d{2}")||!end.matches("\\d{4}-\\d{2}-\\d{2}"))throw new IllegalArgumentException();
        io.opencode.loopper.template.TemplateDateRange.parse(start,end,java.time.Clock.systemUTC());
    }
    private static GitCommitReader.Selection selection(String id) {
        if(id.startsWith("local:refs/heads/"))return new GitCommitReader.Selection(id.substring(6),null);
        var matcher=java.util.regex.Pattern.compile("remote:([^:]+):(refs/heads/.+)").matcher(id);if(matcher.matches())return new GitCommitReader.Selection(matcher.group(2),matcher.group(1));throw new IllegalArgumentException();
    }
    private static String hash(GitHistoryJobProtocol.Input input){try{return DurableCommandProtocol.hash(GitHistoryJobProtocol.input(input));}catch(IOException invalid){throw WorkflowCommands.conflict();}}
}
