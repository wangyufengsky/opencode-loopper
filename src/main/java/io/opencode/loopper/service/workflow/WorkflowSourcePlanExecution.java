package io.opencode.loopper.service.workflow;

import io.opencode.loopper.service.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class WorkflowSourcePlanExecution {
    private final WorkflowSourcePlanStore store;
    private final WorkflowSourcePlanBuilder builder;
    private final WorkflowTestPlanBuilder tests;
    private final WorkflowDocumentPlanBuilder documents;
    private final WorkflowHistoryPlanBuilder history;
    private final WorkflowSnapshotPlanBuilder snapshots;
    public WorkflowSourcePlanExecution(WorkflowSourcePlanStore store,WorkflowSourcePlanBuilder builder,WorkflowTestPlanBuilder tests,WorkflowDocumentPlanBuilder documents,WorkflowHistoryPlanBuilder history,WorkflowSnapshotPlanBuilder snapshots){this.snapshots=snapshots;this.history=history;this.documents=documents;this.store=store;this.builder=builder;this.tests=tests;}
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public void advance(String id) {
        var context=store.context(id);if(context==null)return;
        if(io.opencode.loopper.workflow.WorkflowSnapshotReport.PLAN_MODULE.equals(context.node().moduleId())) {
            WorkflowSnapshotPlanBuilder.Result result=null;String code=null;
            try{result=snapshots.build(context);}catch(BadRequestException|ConflictException invalid){code=WorkflowFailures.code(invalid);}
            store.finish(context,snapshots.delivery(result,code),result!=null);return;
        }
        if(io.opencode.loopper.workflow.WorkflowHistoryReport.PLAN_MODULE.equals(context.node().moduleId())) {
            WorkflowHistoryPlanBuilder.Result result=null;String code=null;
            try{result=history.build(context);}catch(BadRequestException|ConflictException invalid){code=WorkflowFailures.code(invalid);}
            store.finish(context,history.delivery(result,code),result!=null);return;
        }
        if(io.opencode.loopper.workflow.WorkflowSourcePlan.DOCUMENT_MODULE.equals(context.node().moduleId())) {
            WorkflowDocumentPlanBuilder.Result result=null;String code=null;
            try{result=documents.build(context);}catch(BadRequestException|ConflictException invalid){code=WorkflowFailures.code(invalid);}
            store.finish(context,documents.delivery(result,code),result!=null);return;
        }
        WorkflowSourcePlanBuilder.Result result=null;String code=null;
        boolean test=io.opencode.loopper.workflow.WorkflowSourcePlan.TEST_MODULE.equals(context.node().moduleId());
        try{result=test?tests.build(context):builder.build(context);}catch(BadRequestException|ConflictException invalid){code=WorkflowFailures.code(invalid);}
        store.finish(context,test?tests.delivery(result,code):builder.delivery(result,code),result!=null);
    }
}
