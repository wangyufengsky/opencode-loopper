package io.opencode.loopper.service.workflow;

import io.opencode.loopper.persistence.WorkflowExecutionRows.Attempt;
import io.opencode.loopper.workflow.*;
import org.springframework.stereotype.Component;

/** Module-specific input and candidate policies, independent of the generic node lifecycle ledger. */
@Component
public final class WorkflowWorkContracts {
    private final WorkflowSourceDesignContract sources;
    private final WorkflowTestWorkContract tests;
    private final WorkflowTestReviewContract reviews;
    private final WorkflowEncoding encoding;
    private final WorkflowDocumentReviewContract documents;
    private final WorkflowKnowledgeBundleContract knowledge;
    private final WorkflowHistoryAnalysisContract history;
    private final WorkflowSnapshotContract snapshot;
    public WorkflowWorkContracts(WorkflowSourceDesignContract sources,WorkflowTestWorkContract tests,WorkflowTestReviewContract reviews,WorkflowEncoding encoding,WorkflowDocumentReviewContract documents,WorkflowKnowledgeBundleContract knowledge,WorkflowHistoryAnalysisContract history,WorkflowSnapshotContract snapshot){this.sources=sources;this.tests=tests;this.reviews=reviews;this.encoding=encoding;this.documents=documents;this.knowledge=knowledge;this.history=history;this.snapshot=snapshot;}
    public void validate(WorkflowGraph.Node node,WorkflowDelivery.Inputs inputs) {
        if(WorkflowSnapshotWork.supports(node.moduleId()))snapshot.validate(node,inputs);
        if(WorkflowHistoryAnalysis.supports(node.moduleId()))history.context(node,inputs);
        if(WorkflowSourceDesign.supports(node.moduleId()))sources.context(node,inputs);
        if(WorkflowTestDesign.supports(node.moduleId())||WorkflowTestWrite.supports(node.moduleId()))tests.context(node,inputs);
        if(WorkflowTestReview.supports(node.moduleId()))reviews.context(node,inputs);
        if(WorkflowDocumentReview.supports(node.moduleId()))documents.context(node,inputs);
    }
    public WorkflowDelivery accept(Attempt attempt,WorkflowGraph.Node node,WorkflowDelivery.Inputs inputs,WorkflowDelivery delivery) {
        if(WorkflowSnapshotWork.supports(node.moduleId()))return snapshot.accept(attempt,node,inputs,delivery);
        if(WorkflowHistoryAnalysis.supports(node.moduleId()))return history.accept(attempt,node,inputs,delivery);
        if(WorkflowKnowledgeBundle.supports(node.moduleId()))return knowledge.accept(attempt,node,delivery);
        if(WorkflowSourceDesign.supports(node.moduleId()))return sources.accept(attempt,node,inputs,delivery);
        if(WorkflowTestDesign.supports(node.moduleId()))return tests.accept(attempt,node,inputs,delivery);
        if(WorkflowTestReview.supports(node.moduleId()))return reviews.accept(attempt,node,inputs,delivery);
        if(WorkflowDocumentReview.supports(node.moduleId()))return documents.accept(attempt,node,inputs,delivery);
        if(WorkflowReviewContract.reviewer(node.moduleId()))return WorkflowReviewContract.accept(node,delivery,inputs,encoding);
        return delivery;
    }
}
