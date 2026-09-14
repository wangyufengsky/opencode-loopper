package io.opencode.loopper.service;

import io.opencode.loopper.domain.*;
import io.opencode.loopper.persistence.DocumentTemplateRunRow;
import org.springframework.stereotype.Service;

/** Checkpoint-sized progression, with no executable Task during input analysis or static review. */
@Service
public final class DocumentAnalysisFlow {
    private final DocumentTemplatePreparation preparation;
    private final DocumentRequirementWorkflow requirements;
    private final DocumentAssessmentWorkflow assessments;
    private final DocumentRequirementReportService reports;
    private final DocumentTemplateAdmission admission;
    private final DirectDocumentReviewWorkflow direct;
    public DocumentAnalysisFlow(DocumentTemplatePreparation preparation, DocumentRequirementWorkflow requirements,
            DocumentAssessmentWorkflow assessments, DocumentRequirementReportService reports, DocumentTemplateAdmission admission, DirectDocumentReviewWorkflow direct) {
        this.preparation = preparation; this.requirements = requirements; this.assessments = assessments;
        this.reports = reports; this.admission = admission; this.direct = direct;
    }
    public void advance(DocumentTemplateRunRow run, DocumentTemplateService.Contract contract) {
        switch (DocumentTemplateState.valueOf(run.state())) {
            case PREPARING -> preparation.recover(run.id());
            case ANALYZING, REVIEWING -> {
                if (requirements.advance(run, contract)) {
                    boolean review = run.templateId().equals("REQUIREMENT_CODE_REVIEW");
                    admission.transition(admission.require(run.id()), review ? DocumentTemplateState.ASSESSING : DocumentTemplateState.DESIGNING,
                            review ? LifecycleEvent.ASSESS_REQUIREMENT_CODE : LifecycleEvent.DESIGN_DOCUMENT_REQUIREMENTS, null, null);
                }
            }
            case ASSESSING, VERIFYING -> {
                if (run.directDocuments() ? direct.advance(run, contract) : assessments.advance(run, contract)) admission.transition(admission.require(run.id()), DocumentTemplateState.REPORTING,
                        LifecycleEvent.RENDER_REQUIREMENT_REPORT, null, null);
            }
            case REPORTING -> {
                reports.review(run);
                admission.transition(admission.require(run.id()), DocumentTemplateState.COMPLETED, LifecycleEvent.COMPLETE, null, null);
            }
            default -> { }
        }
    }
}
