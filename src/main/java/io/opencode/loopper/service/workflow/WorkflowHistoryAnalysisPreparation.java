package io.opencode.loopper.service.workflow;

import io.opencode.loopper.persistence.WorkflowModelMapper;
import io.opencode.loopper.workflow.WorkflowHistoryAnalysis;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Materialize official delivery files outside SQLite transactions, then recheck the launch before saving. */
@Service
@Transactional(propagation=Propagation.NOT_SUPPORTED)
public class WorkflowHistoryAnalysisPreparation {
    private final WorkflowModelStore models;
    private final WorkflowNodeRuns nodes;
    private final WorkflowHistoryAnalysisContract contract;
    private final WorkflowHistoryEvidence evidence;
    private final WorkflowHistoryAnalysisStore inputs;
    public WorkflowHistoryAnalysisPreparation(WorkflowModelStore models,WorkflowNodeRuns nodes,WorkflowHistoryAnalysisContract contract,WorkflowHistoryEvidence evidence,WorkflowHistoryAnalysisStore inputs){this.models=models;this.nodes=nodes;this.contract=contract;this.evidence=evidence;this.inputs=inputs;}
    public void prepare(WorkflowModelMapper.Launch row) {
        var node=models.definition(row);if(!WorkflowHistoryAnalysis.supports(node.moduleId()))return;
        models.activeWork(row);if(inputs.find(row.attemptId()).isPresent())return;
        var context=contract.context(node,nodes.inputs(models.attempt(row)));
        try {
            var input=WorkflowHistoryAnalysis.prepare(node,context.source(),context.producer(),evidence.read(context.manifest()),context.reviews(),context.reviewAttempts());
            inputs.save(row,input);
        }catch(IllegalArgumentException invalid){throw WorkflowHistoryAnalysisStore.invalid(invalid.getMessage());}
    }
}
