package io.opencode.loopper.service.workflow;

import io.opencode.loopper.service.BadRequestException;
import io.opencode.loopper.workflow.*;
import org.springframework.stereotype.Service;

/** Explicit dispatch for program work; unknown modules never inherit another adapter's privileges. */
@Service
public final class WorkflowSystemDispatch {
    private final WorkflowVerificationStore verifications;
    private final WorkflowCommandStore commands;
    private final WorkflowReviewGate reviewGate;
    private final WorkflowSourceStore sources;
    private final WorkflowDocumentStore documents;
    private final WorkflowSourcePlanStore sourcePlans;
    private final WorkflowTestProfileStore testProfiles;
    private final WorkflowTestSummaryService testSummaries;
    public WorkflowSystemDispatch(WorkflowVerificationStore verifications,WorkflowCommandStore commands,WorkflowReviewGate reviewGate,WorkflowSourceStore sources,WorkflowDocumentStore documents,WorkflowSourcePlanStore sourcePlans,WorkflowTestProfileStore testProfiles,WorkflowTestSummaryService testSummaries){this.verifications=verifications;this.commands=commands;this.reviewGate=reviewGate;this.sources=sources;this.documents=documents;this.sourcePlans=sourcePlans;this.testProfiles=testProfiles;this.testSummaries=testSummaries;}
    public void dispatch(String module,String id,String node,WorkflowNodeActions.Start request,WorkflowDispatch.Permit permit) {
        switch(module) {
            case WorkflowVerification.MODULE -> verifications.dispatch(id,node,request,permit);
            case WorkflowCommandVerification.MODULE,WorkflowNativeTest.MODULE,WorkflowRepositorySnapshot.MODULE,WorkflowHistorySnapshot.MODULE,WorkflowReviewSource.MODULE -> commands.dispatch(id,node,request,permit);
            case WorkflowReviewContract.GATE -> reviewGate.dispatch(id,node,request,permit);
            case WorkflowSourceSnapshot.MODULE -> sources.dispatch(id,node,request,permit);
            case WorkflowDocument.MODULE,WorkflowDocument.ASSESSMENT_MODULE,WorkflowHistoryReport.MODULE,WorkflowSnapshotReport.MODULE -> documents.dispatch(id,node,request,permit);
            case WorkflowSourcePlan.MODULE,WorkflowSourcePlan.TEST_MODULE,WorkflowSourcePlan.DOCUMENT_MODULE,WorkflowHistoryReport.PLAN_MODULE,WorkflowSnapshotReport.PLAN_MODULE -> sourcePlans.dispatch(id,node,request,permit);
            case WorkflowTestSummary.MODULE -> testSummaries.dispatch(id,node,request,permit);
            case WorkflowTestProfile.MODULE -> testProfiles.dispatch(id,node,request,permit);
            default -> throw new BadRequestException("WORKFLOW_SYSTEM_MODULE_UNAVAILABLE","此程序工作模块不可执行，请选择受支持的模块版本。");
        }
    }
}
