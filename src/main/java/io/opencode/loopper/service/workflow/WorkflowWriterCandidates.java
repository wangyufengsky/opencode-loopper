package io.opencode.loopper.service.workflow;

import io.opencode.loopper.persistence.WorkflowWriterCandidateMapper;
import io.opencode.loopper.persistence.WorkflowWriterCandidateMapper.Candidate;
import io.opencode.loopper.persistence.WorkflowModelMapper.Launch;
import io.opencode.loopper.runtime.WorkflowModelProfile;
import io.opencode.loopper.service.BadRequestException;
import io.opencode.loopper.workflow.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** The model supplies business content only. Code identities are added by the stopped writer's capture. */
@Service
public class WorkflowWriterCandidates {
    private final WorkflowWriterCandidateMapper mapper;
    private final WorkflowModelStore models;
    private final WorkflowTestWorkContract tests;
    private final WorkflowNodeRuns nodes;
    public WorkflowWriterCandidates(WorkflowWriterCandidateMapper mapper,WorkflowModelStore models,WorkflowTestWorkContract tests,WorkflowNodeRuns nodes) { this.mapper=mapper;this.models=models;this.tests=tests;this.nodes=nodes; }
    @Transactional(readOnly=true)
    public Optional<Candidate> find(String id) {
        var result=mapper.find(id);
        if (result.isPresent() && !WorkflowEncoding.hash(result.get().contentJson()).equals(result.get().sha256())) throw WorkflowCommands.conflict();
        return result;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public Candidate submit(Launch row,WorkflowDelivery delivery) {
        models.active(row);
        if (!WorkflowModelProfile.writer(models.attempt(row).adapterKey()) || models.stopProof(row.attemptId()).isPresent()) throw WorkflowCommands.conflict();
        var definition=models.definition(row);
        if (delivery.outputs().entrySet().stream().anyMatch(entry->entry.getValue().kind()==WorkflowGraph.DataKind.CODE
                || definition.outputs().stream().anyMatch(output->output.name().equals(entry.getKey()) && output.kind()==WorkflowGraph.DataKind.CODE)))
            throw new BadRequestException("WORKFLOW_CODE_SERVER_OWNED","CODE 交付由程序保存工作区后生成，请从候选 outputs 中省略代码输出");
        if(WorkflowTestWrite.supports(definition.moduleId())) {
            if(delivery.outputs().containsKey("scope"))throw new BadRequestException("WORKFLOW_TEST_SCOPE_SERVER_OWNED","范围检查由程序在停止后执行，请从候选中省略 scope 输出。");
            tests.writerCandidate(models.attempt(row),definition,nodes.inputs(models.attempt(row)));
        }
        // A temporary object satisfies shape validation only; it is never persisted or accepted as a code reference.
        WorkflowDeliveries.validate(definition,withScope(definition,withCode(definition,delivery,models.encoding().decode("{}",tools.jackson.databind.JsonNode.class)),models.encoding().decode("{}",tools.jackson.databind.JsonNode.class)));
        String body=models.encoding().encode(delivery),hash=WorkflowEncoding.hash(body);
        var previous=find(row.attemptId());
        if (previous.isPresent()) {
            if (!previous.get().contentJson().equals(body)) throw WorkflowCommands.conflict();
            return previous.get();
        }
        var result=new Candidate(row.attemptId(),models.attempt(row).version(),body,hash,Instant.now().toString());
        if (mapper.insert(result)!=1) throw WorkflowCommands.conflict();
        return result;
    }
    public WorkflowDelivery delivery(Launch row,WorkflowCodeSnapshot.Reference code) {
        var candidate=find(row.attemptId()).orElseThrow(WorkflowCommands::conflict);
        return withCode(models.definition(row),models.encoding().decode(candidate.contentJson(),WorkflowDelivery.class),
                code==null?null:models.encoding().decode(models.encoding().encode(code),tools.jackson.databind.JsonNode.class));
    }
    public WorkflowDelivery scope(Launch row,WorkflowDelivery delivery,String report){return withScope(models.definition(row),delivery,models.encoding().decode(report,tools.jackson.databind.JsonNode.class));}
    private static WorkflowDelivery withScope(WorkflowGraph.Node node,WorkflowDelivery delivery,tools.jackson.databind.JsonNode scope) {
        if(!WorkflowTestWrite.supports(node.moduleId()))return delivery;
        var outputs=new LinkedHashMap<>(delivery.outputs());outputs.put("scope",new WorkflowDelivery.Value(WorkflowGraph.DataKind.JSON,scope));return new WorkflowDelivery(delivery.summary(),delivery.outcome(),outputs);
    }
    private static WorkflowDelivery withCode(WorkflowGraph.Node node,WorkflowDelivery delivery,tools.jackson.databind.JsonNode code) {
        var outputs=new LinkedHashMap<>(delivery.outputs());
        for (var output:node.outputs()) if (output.kind()==WorkflowGraph.DataKind.CODE) {
            if (code==null) throw WorkflowCommands.conflict();
            outputs.put(output.name(),new WorkflowDelivery.Value(output.kind(),code));
        }
        return new WorkflowDelivery(delivery.summary(),delivery.outcome(),outputs);
    }
}
