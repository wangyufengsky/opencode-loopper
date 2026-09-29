package io.opencode.loopper.service.workflow;

import io.opencode.loopper.service.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Rendering runs outside the completion transaction and can be repeated from the same frozen inputs. */
@Service
@Transactional(propagation=Propagation.NOT_SUPPORTED)
public class WorkflowDocumentExecution {
    private final WorkflowDocumentStore store;
    private final WorkflowDocumentBuilder builder;
    private final WorkflowAssessmentReportBuilder assessments;
    private final WorkflowHistoryReportBuilder history;
    private final WorkflowSnapshotReportBuilder snapshots;
    public WorkflowDocumentExecution(WorkflowDocumentStore store,WorkflowDocumentBuilder builder,WorkflowAssessmentReportBuilder assessments,WorkflowHistoryReportBuilder history,WorkflowSnapshotReportBuilder snapshots){this.snapshots=snapshots;this.history=history;this.assessments=assessments;this.store=store;this.builder=builder;}
    public void advance(String id) {
        var context=store.context(id);if(context==null)return;
        WorkflowDocumentBuilder.Result result=null;String code=null;
        try{result=io.opencode.loopper.workflow.WorkflowSnapshotReport.MODULE.equals(context.node().moduleId())?snapshots.build(context):io.opencode.loopper.workflow.WorkflowHistoryReport.MODULE.equals(context.node().moduleId())?history.build(context):io.opencode.loopper.workflow.WorkflowDocument.ASSESSMENT_MODULE.equals(context.node().moduleId())?assessments.build(context):builder.build(context);}
        catch(BadRequestException|ConflictException failure){code=WorkflowFailures.code(failure);}
        store.finish(context,result,code);
    }
}
