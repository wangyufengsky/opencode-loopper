package io.opencode.loopper.service.workflow;

import io.opencode.loopper.persistence.WorkflowModelMapper;
import io.opencode.loopper.workflow.WorkflowSnapshotWork;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(propagation=Propagation.NOT_SUPPORTED)
public class WorkflowSnapshotPreparation {
    private final WorkflowModelStore models;
    private final WorkflowNodeRuns nodes;
    private final WorkflowSnapshotInputs sources;
    private final WorkflowSnapshotEvidence evidence;
    private final WorkflowSnapshotWorkStore inputs;
    private final WorkflowSnapshotReuseStore reuse;
    public WorkflowSnapshotPreparation(WorkflowModelStore models,WorkflowNodeRuns nodes,WorkflowSnapshotInputs sources,WorkflowSnapshotEvidence evidence,WorkflowSnapshotWorkStore inputs,WorkflowSnapshotReuseStore reuse){this.models=models;this.nodes=nodes;this.sources=sources;this.evidence=evidence;this.inputs=inputs;this.reuse=reuse;}
    public boolean prepare(WorkflowModelMapper.Launch row){
        var node=models.definition(row);if(!WorkflowSnapshotWork.supports(node.moduleId()))return false;
        models.activeWork(row);
        if(inputs.find(row.attemptId()).isEmpty())try{
            var context=sources.read(node,nodes.inputs(models.attempt(row)));var snapshot=evidence.read(context.manifest());
            var input=WorkflowSnapshotWork.prepare(node,context.source(),context.producer(),snapshot,context.analysisAttempt(),context.analysis(),context.reviews());
            inputs.save(row,input);reuse.remember(row,snapshot);
        }catch(IllegalArgumentException failure){throw WorkflowSnapshotWorkStore.invalid(failure.getMessage());}
        return reuse.apply(row);
    }
}
