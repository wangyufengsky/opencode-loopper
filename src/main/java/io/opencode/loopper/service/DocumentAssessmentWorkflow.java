package io.opencode.loopper.service;

import io.opencode.loopper.domain.*;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.template.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** All functional assessments settle before any final independent cross-batch review round. */
@Service
public final class DocumentAssessmentWorkflow {
    private final DocumentAssessmentPlan planner;
    private final DocumentAssessmentMapper assessments;
    private final DocumentTemplateModelMapper models;
    private final DocumentModelStore store;
    private final DocumentModelExecution execution;
    private final DocumentTemplateAdmission admission;
    private final DocumentAssessmentLedger ledger;
    private final ObjectMapper json;
    public DocumentAssessmentWorkflow(DocumentAssessmentPlan planner, DocumentAssessmentMapper assessments,
            DocumentTemplateModelMapper models, DocumentModelStore store, DocumentModelExecution execution,
            DocumentTemplateAdmission admission, DocumentAssessmentLedger ledger, ObjectMapper json) {
        this.planner = planner; this.assessments = assessments; this.models = models; this.store = store;
        this.execution = execution; this.admission = admission; this.ledger = ledger; this.json = json;
    }
    public boolean advance(DocumentTemplateRunRow run, DocumentTemplateService.Contract contract) {
        assessments.createProgress(run.id());
        var progress = assessments.progress(run.id()).orElseThrow();
        var batches = planner.batches(run);
        if (run.state().equals("ASSESSING")) {
            var work = pending(run.id(), batches.size(), "REQUIREMENT_CODE_ASSESSMENT_V1", progress.round());
            if (!work.isEmpty()) {
                for (var next : TemplateBatchWindow.select(work, Work::state, contract.analysisConcurrency())) {
                    if (next.model() == null) create(run, next.ordinal(), progress.round(), batches.get(next.ordinal()));
                    else execution.advance(next.model().id(), contract);
                }
                return false;
            }
            admission.transition(run, DocumentTemplateState.VERIFYING, LifecycleEvent.VERIFY_REQUIREMENT_ASSESSMENT, null, null);
            return false;
        }
        var work = pending(run.id(), batches.size(), "REQUIREMENT_ASSESSMENT_REVIEW_V1", progress.round());
        if (!work.isEmpty()) {
            for (var next : TemplateBatchWindow.select(work, Work::state, contract.analysisConcurrency())) {
                if (next.model() != null) { execution.advance(next.model().id(), contract); continue; }
                var input = batches.get(next.ordinal());
                var model = models.exact(run.id(), "REQUIREMENT_CODE_ASSESSMENT_V1", next.ordinal(), progress.round()).orElseThrow();
                store.create(run.id(), MachineCandidateKind.REQUIREMENT_ASSESSMENT_REVIEW_V1, next.ordinal(), progress.round(),
                        new DocumentModelInput(input.sections(), input.requirements(), null, input.snapshotSha(),
                                json.readValue(model.outputJson(), RequirementCodeAssessment.Candidate.class), null));
            }
            return false;
        }
        boolean repair = false;
        for (int i = 0; i < batches.size(); i++) {
            var review = models.exact(run.id(), "REQUIREMENT_ASSESSMENT_REVIEW_V1", i, progress.round()).orElseThrow();
            repair |= !json.readValue(review.outputJson(), RequirementCodeAssessment.Review.class).approved();
        }
        if (repair) {
            if (progress.round() + 1 >= contract.maxStageAttempts()) throw new BadRequestException("DOCUMENT_ASSESSMENT_REPAIR_EXHAUSTED",
                    "独立复核仍有待修正的评审结论，修正预算已耗尽；保留未完成范围等待处理");
            ledger.repair(run, progress); return false;
        }
        for (int i = 0; i < batches.size(); i++) {
            if (assessments.batch(run.id(), run.requirementRevision(), i).isPresent()) continue;
            ledger.accept(run, models.exact(run.id(), "REQUIREMENT_CODE_ASSESSMENT_V1", i, progress.round()).orElseThrow(),
                    models.exact(run.id(), "REQUIREMENT_ASSESSMENT_REVIEW_V1", i, progress.round()).orElseThrow());
            return false;
        }
        return true;
    }
    private java.util.List<Work> pending(String run, int count, String kind, int round) {
        var result = new java.util.ArrayList<Work>();
        for (int i = 0; i < count; i++) {
            var row = models.exact(run, kind, i, round).orElse(null);
            if (row != null && row.state().equals("VALIDATED")) continue;
            if (row != null && TemplateBatchState.valueOf(row.state()).terminal())
                throw new BadRequestException("DOCUMENT_MODEL_REQUIRES_RECOVERY", "评审批次已停止，请从冻结输入恢复");
            result.add(new Work(i, row));
        }
        return result;
    }
    private record Work(int ordinal, io.opencode.loopper.persistence.DocumentTemplateModelRow model) {
        String state() { return model == null ? "PREPARED" : model.state(); }
    }

    private void create(DocumentTemplateRunRow run, int ordinal, int round, DocumentModelInput input) {
        RequirementCodeAssessment.Candidate previous = null; RequirementCodeAssessment.Review feedback = null;
        if (round > 0) {
            previous = json.readValue(models.exact(run.id(), "REQUIREMENT_CODE_ASSESSMENT_V1", ordinal, round - 1).orElseThrow().outputJson(),
                    RequirementCodeAssessment.Candidate.class);
            feedback = json.readValue(models.exact(run.id(), "REQUIREMENT_ASSESSMENT_REVIEW_V1", ordinal, round - 1).orElseThrow().outputJson(),
                    RequirementCodeAssessment.Review.class);
        }
        store.create(run.id(), MachineCandidateKind.REQUIREMENT_CODE_ASSESSMENT_V1, ordinal, round,
                new DocumentModelInput(input.sections(), input.requirements(), null, input.snapshotSha(), previous, feedback));
    }
}
