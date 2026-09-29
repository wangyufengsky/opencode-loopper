package io.opencode.loopper.service.workflow;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.template.*;
import io.opencode.loopper.workflow.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** All reuse decisions and their acceptance are atomic database work before Session creation. */
@Service
public class WorkflowSnapshotReuseStore {
    private final WorkflowModelStore models;
    private final WorkflowNodeRuns nodes;
    private final WorkflowSnapshotWorkStore inputs;
    private final WorkflowSnapshotWorkMapper prepared;
    private final WorkflowSnapshotReuseMapper mapper;
    private final WorkflowSnapshotReuseContext contexts;
    private final WorkflowEncoding encoding;
    public WorkflowSnapshotReuseStore(WorkflowModelStore models,WorkflowNodeRuns nodes,WorkflowSnapshotWorkStore inputs,WorkflowSnapshotWorkMapper prepared,WorkflowSnapshotReuseMapper mapper,WorkflowSnapshotReuseContext contexts,WorkflowEncoding encoding){this.models=models;this.nodes=nodes;this.inputs=inputs;this.prepared=prepared;this.mapper=mapper;this.contexts=contexts;this.encoding=encoding;}
    @Transactional
    public void remember(WorkflowModelMapper.Launch expected,SnapshotReview.Snapshot snapshot) {
        var row=models.activeWork(expected);var attempt=models.attempt(row);var input=inputs.require(row.attemptId());
        if(!row.state().equals("PREPARING")||row.creationPlanJson()!=null||row.suspended())throw WorkflowCommands.conflict();
        var fingerprint=contexts.fingerprint(row,attempt,models.definition(row),nodes.inputs(attempt),input,snapshot);
        if(fingerprint.isEmpty())return;
        String sha=prepared.find(attempt.id()).orElseThrow(WorkflowCommands::conflict).sha256();var prior=mapper.context(attempt.id());
        if(prior.isPresent()){if(!prior.get().fingerprint().equals(fingerprint.get())||!prior.get().inputSha256().equals(sha))throw WorkflowCommands.conflict();return;}
        if(mapper.insertContext(new WorkflowSnapshotReuseMapper.Context(attempt.id(),fingerprint.get(),sha,Instant.now().toString()))!=1)throw WorkflowCommands.conflict();
    }
    @Transactional
    public boolean apply(WorkflowModelMapper.Launch expected) {
        var row=models.activeWork(expected);var attempt=models.attempt(row);var node=models.definition(row);
        if(!WorkflowSnapshotWork.ANALYZE.equals(node.moduleId())||!WorkflowSnapshotWork.reuseAllowed(node)||attempt.ordinal()!=1||mapper.priorFailure(row.requirementId()))return false;
        if(!row.state().equals("PREPARING")||row.creationPlanJson()!=null||row.suspended()||attempt.externalSessionId()!=null)throw WorkflowCommands.conflict();
        var context=mapper.context(attempt.id());if(context.isEmpty())return false;
        var current=inputs.require(attempt.id());var inputRow=prepared.find(attempt.id()).orElseThrow(WorkflowCommands::conflict);
        if(!context.get().inputSha256().equals(inputRow.sha256()))throw WorkflowCommands.conflict();
        var found=mapper.source(row.requirementId(),context.get().fingerprint());if(found.isEmpty())return false;var source=found.get();
        if(!WorkflowEncoding.hash(source.contentJson()).equals(source.sha256())||!WorkflowEncoding.hash(source.inputJson()).equals(source.inputSha256()))return false;
        var output=encoding.decode(source.contentJson(),WorkflowDelivery.class).outputs().get("analysis");if(output==null||output.kind()!=WorkflowGraph.DataKind.JSON)return false;
        var original=encoding.decode(encoding.encode(output.content()),WorkflowSnapshotWork.Analysis.class);var sourceInput=encoding.decode(source.inputJson(),WorkflowSnapshotWork.Input.class);
        if(original.version()!=1||!WorkflowSnapshotWork.ANALYSIS_TYPE.equals(original.type())||!original.source().equals(sourceInput.source()))return false;
        var mapped=SnapshotReviewReusePolicy.remap(original.claims(),sourceInput.batch(),current.batch());if(mapped.isEmpty())return false;
        String body=encoding.encode(mapped.get());var receipt=new WorkflowSnapshotReuseMapper.Receipt(attempt.id(),source.attemptId(),source.requirementId(),source.title(),source.nodeTitle(),context.get().fingerprint(),source.sha256(),body,WorkflowEncoding.hash(body),Instant.now().toString());
        if(mapper.reuse(receipt)!=1)throw WorkflowCommands.conflict();
        String summary="复用已完成的历史无问题分析；本次未创建模型会话。";
        var delivery=new WorkflowDelivery(summary,"NO_FINDINGS",Map.of("summary",new WorkflowDelivery.Value(WorkflowGraph.DataKind.TEXT,encoding.decode(encoding.encode(summary),tools.jackson.databind.JsonNode.class)),
            "analysis",new WorkflowDelivery.Value(WorkflowGraph.DataKind.JSON,encoding.decode(body,tools.jackson.databind.JsonNode.class))));
        models.completeReuse(row,delivery,Map.of("mode","SNAPSHOT_REUSE","sourceAttempt",source.attemptId(),"sourceDeliverySha256",source.sha256(),"fingerprint",receipt.fingerprint()));return true;
    }
}
