package io.opencode.loopper.service;

import io.opencode.loopper.domain.*;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.template.*;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class DocumentAssessmentLedger {
    private final DocumentAssessmentMapper assessments;
    private final DocumentTemplateModelMapper models;
    private final DocumentTemplateAdmission admission;
    private final DocumentAssessmentValidation validation;
    private final ObjectMapper json;
    public DocumentAssessmentLedger(DocumentAssessmentMapper assessments, DocumentTemplateModelMapper models,
            DocumentTemplateAdmission admission, DocumentAssessmentValidation validation, ObjectMapper json) {
        this.assessments = assessments; this.models = models; this.admission = admission; this.validation = validation; this.json = json;
    }
    @Transactional
    public void repair(DocumentTemplateRunRow run, DocumentAssessmentMapper.Progress progress) {
        if (assessments.advanceRound(progress) != 1) throw conflict();
        admission.transition(run, DocumentTemplateState.ASSESSING, LifecycleEvent.ASSESS_REQUIREMENT_CODE, null, null);
    }
    @Transactional
    public void accept(DocumentTemplateRunRow run, DocumentTemplateModelRow model, DocumentTemplateModelRow review) {
        model = models.find(model.id()).orElseThrow(); review = models.find(review.id()).orElseThrow();
        if (!model.runId().equals(run.id()) || !review.runId().equals(run.id()) || model.ordinal() != review.ordinal()
                || model.generation() != review.generation() || !model.state().equals("VALIDATED") || !review.state().equals("VALIDATED")
                || Objects.equals(model.externalSessionId(), review.externalSessionId())) throw conflict();
        var input = json.readValue(review.inputJson(), DocumentModelInput.class);
        if (!json.writeValueAsString(input.assessment()).equals(model.outputJson())) throw conflict();
        var decision = validation.review(review, input, json.readValue(review.outputJson(), RequirementCodeAssessment.Review.class));
        if (!decision.approved()) throw conflict();
        var existing = assessments.batch(run.id(), run.requirementRevision(), model.ordinal());
        if (existing.isPresent()) {
            if (!existing.get().modelId().equals(model.id()) || !existing.get().reviewModelId().equals(review.id())) throw conflict();
            return;
        }
        var candidate = json.readValue(model.outputJson(), RequirementCodeAssessment.Candidate.class);
        for (var item : candidate.items()) {
            String body = json.writeValueAsString(item);
            if (assessments.insertItem(run.id(), run.requirementRevision(), item.requirementKey(), body, DocumentModelStore.hash(body)) != 1) throw conflict();
        }
        if (assessments.insertBatch(new DocumentAssessmentMapper.Batch(run.id(), run.requirementRevision(), model.ordinal(),
                model.generation(), model.id(), review.id(), model.outputJson(), DocumentModelStore.hash(model.outputJson()), Instant.now().toString())) != 1) throw conflict();
    }
    private static ConflictException conflict() { return new ConflictException("DOCUMENT_ASSESSMENT_CONFLICT", "评审冻结结果或独立复核身份发生变化"); }
}
