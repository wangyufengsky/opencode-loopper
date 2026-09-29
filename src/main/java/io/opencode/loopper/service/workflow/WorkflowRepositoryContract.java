package io.opencode.loopper.service.workflow;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.runtime.DurableCommandProtocol;
import io.opencode.loopper.service.*;
import io.opencode.loopper.workflow.*;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;

/** Database-only frozen input and accepted result rules for supervised repository capture. */
@Component
public final class WorkflowRepositoryContract {
    private final WorkflowRepositoryMapper mapper;
    private final LoopperMapper projects;
    private final WorkflowEncoding encoding;
    public WorkflowRepositoryContract(WorkflowRepositoryMapper mapper,LoopperMapper projects,WorkflowEncoding encoding){this.mapper=mapper;this.projects=projects;this.encoding=encoding;}
    public record Context(WorkflowRepositoryMapper.Snapshot row,GitSnapshotJobProtocol.Input input,int timeoutSeconds) { }
    public boolean supports(WorkflowGraph.Node node){return WorkflowRepositorySnapshot.MODULE.equals(node.moduleId());}
    public void validate(WorkflowGraph.Node node,WorkflowDelivery.Inputs inputs) {
        try{WorkflowRepositorySnapshot.require(node);selection(branch(inputs));}
        catch(RuntimeException invalid){throw new BadRequestException("WORKFLOW_REPOSITORY_INVALID","请选择明确的 Git 分支，并保留采集资料、报告和说明。");}
    }
    public void admit(WorkflowExecutionRows.Attempt attempt,WorkflowNodeActions.Admission admission) {
        if(!supports(admission.definition()))return;
        String branch=branch(admission.inputs());var previous=mapper.find(attempt.nodeRunId());
        if(previous.isPresent()){if(!previous.get().branchId().equals(branch))throw WorkflowCommands.conflict();return;}
        var selection=selection(branch);var owner=admission.owner();
        var project=projects.findProject(owner.projectId()).orElseThrow(WorkflowCommands::conflict);
        var input=new GitSnapshotJobProtocol.Input(attempt.nodeRunId(),project.rootPath(),selection.ref(),selection.remote());
        if(mapper.insert(new WorkflowRepositoryMapper.Snapshot(attempt.nodeRunId(),owner.id(),owner.projectId(),branch,
                encoding.encode(input),hash(input),null,null,Instant.now().toString()))!=1)throw WorkflowCommands.conflict();
    }
    public Context context(WorkflowExecutionRows.Attempt attempt,WorkflowGraph.Node node,WorkflowDelivery.Inputs inputs) {
        if(!supports(node))return null;validate(node,inputs);
        var row=mapper.find(attempt.nodeRunId()).orElseThrow(WorkflowCommands::conflict);
        var input=encoding.decode(row.inputJson(),GitSnapshotJobProtocol.Input.class);
        if(!row.branchId().equals(branch(inputs))||!row.inputSha256().equals(hash(input))||!input.nodeId().equals(attempt.nodeRunId()))throw WorkflowCommands.conflict();
        return new Context(row,input,WorkflowRepositorySnapshot.require(node));
    }
    public WorkflowCommandContract.Evaluation evaluate(WorkflowCommandStore.Context context,DurableCommandProtocol.Result result,GitSnapshotJobProtocol.Snapshot capture) {
        var repository=context.repository();boolean success=result.successful()&&capture!=null;
        var values=new LinkedHashMap<String,WorkflowDelivery.Value>();var report=new LinkedHashMap<String,Object>();
        report.put("version",1);report.put("type",WorkflowRepositorySnapshot.TYPE);report.put("complete",success);report.put("branchId",repository.row().branchId());
        if(success) {
            var input=repository.input();var binding=capture.binding();
            if(!binding.inputSha256().equals(repository.row().inputSha256())||!binding.project().equals(input.projectPath())
                    ||!binding.commit().equals(capture.snapshot().commitSha())||!binding.prefix().equals(capture.snapshot().projectPrefix()))throw WorkflowCommands.conflict();
            var manifest=new WorkflowRepositorySnapshot.Manifest(1,WorkflowRepositorySnapshot.TYPE,repository.row().nodeRunId(),repository.row().branchId(),
                    binding.commit(),capture.snapshot().treeSha(),binding.prefix(),capture.snapshot().files().stream()
                    .map(file->new WorkflowRepositorySnapshot.File(file.path(),file.blobSha(),file.mode(),file.sizeBytes(),file.limitation())).toList());
            String body=encoding.encode(manifest),sha=WorkflowEncoding.hash(body);var current=mapper.find(repository.row().nodeRunId()).orElseThrow(WorkflowCommands::conflict);
            if(current.manifestJson()==null){if(mapper.manifest(current.nodeRunId(),body,sha)!=1)throw WorkflowCommands.conflict();}
            else if(!current.manifestJson().equals(body)||!current.manifestSha256().equals(sha))throw WorkflowCommands.conflict();
            values.put("source",value(WorkflowGraph.DataKind.DOCUMENT,new WorkflowRepositorySnapshot.Reference(1,WorkflowRepositorySnapshot.TYPE,current.nodeRunId(),sha)));
            report.put("commitSha",binding.commit());report.put("projectPrefix",binding.prefix());report.put("fileCount",capture.snapshot().files().size());
            report.put("excludedCount",capture.snapshot().files().stream().filter(file->file.limitation()!=null).count());
        } else {
            String code=result.timedOut()?"TEMPLATE_GIT_TIMEOUT":"WORKFLOW_REPOSITORY_CAPTURE_FAILED";
            var match=java.util.regex.Pattern.compile("LOOPPER_GIT_SNAPSHOT_FAILURE:([A-Z][A-Z0-9_]{0,100})").matcher(result.output());
            if(match.find())code=match.group(1);report.put("code",code);
        }
        String summary=success?"指定分支的代码已固定，后续节点读取同一提交。":"代码采集未完成，请查看原因后恢复原节点。";
        values.put("summary",value(WorkflowGraph.DataKind.TEXT,summary));values.put("report",value(WorkflowGraph.DataKind.JSON,report));
        return new WorkflowCommandContract.Evaluation(new WorkflowDelivery(summary,null,values),success);
    }
    private WorkflowDelivery.Value value(WorkflowGraph.DataKind kind,Object content){return new WorkflowDelivery.Value(kind,encoding.decode(encoding.encode(content),tools.jackson.databind.JsonNode.class));}
    private static String branch(WorkflowDelivery.Inputs inputs) {
        var value=inputs.values().stream().filter(input->input.name().equals("branch")).findFirst().orElseThrow(WorkflowCommands::conflict).content();
        if(!value.isString())throw WorkflowCommands.conflict();return value.asString();
    }
    private static GitCommitReader.Selection selection(String id) {
        if(id.startsWith("local:refs/heads/"))return new GitCommitReader.Selection(id.substring(6),null);
        var matcher=java.util.regex.Pattern.compile("remote:([^:]+):(refs/heads/.+)").matcher(id);
        if(matcher.matches())return new GitCommitReader.Selection(matcher.group(2),matcher.group(1));
        throw new IllegalArgumentException("Invalid branch selection");
    }
    private static String hash(GitSnapshotJobProtocol.Input input) {
        try{return DurableCommandProtocol.hash(GitSnapshotJobProtocol.input(input));}catch(IOException invalid){throw WorkflowCommands.conflict();}
    }
}
