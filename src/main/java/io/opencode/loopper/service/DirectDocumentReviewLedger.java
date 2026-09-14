package io.opencode.loopper.service;

import io.opencode.loopper.domain.*;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.template.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Publishes a reviewed result matrix after analysis; the immutable source revision remains independent. */
@Service
public class DirectDocumentReviewLedger {
    private final DocumentTemplateModelMapper models;
    private final DocumentRequirementMapper requirements;
    private final DocumentAssessmentMapper assessments;
    private final DocumentTemplateAdmission admission;
    private final DirectDocumentAssessmentValidation validation;
    private final ObjectMapper json;
    public DirectDocumentReviewLedger(DocumentTemplateModelMapper models, DocumentRequirementMapper requirements,
            DocumentAssessmentMapper assessments, DocumentTemplateAdmission admission,
            DirectDocumentAssessmentValidation validation, ObjectMapper json) {
        this.models = models; this.requirements = requirements; this.assessments = assessments;
        this.admission = admission; this.validation = validation; this.json = json;
    }
    @Transactional
    public void nextRound(DocumentTemplateRunRow run, DocumentAssessmentMapper.Progress round) {
        if (assessments.advanceRound(round) != 1) throw changed();
        admission.transition(run, DocumentTemplateState.ASSESSING, LifecycleEvent.ASSESS_REQUIREMENT_CODE, null, null);
    }
    @Transactional
    public boolean publish(DocumentTemplateRunRow run, int round, int count) {
        var current = admission.require(run.id());
        if (current.requirementRevision() > 0) return true;
        if (!current.directDocuments() || !current.state().equals("VERIFYING") || current.sourceRevision() != run.sourceRevision()) throw changed();
        String now = Instant.now().toString();
        String source = json.writeValueAsString(Map.of("sourceRevision", run.sourceRevision(), "round", round, "batches", count));
        if (requirements.revision(run.id(), 1).isEmpty()) requirements.insertRevision(new DocumentRequirementMapper.Revision(
                run.id(), 1, DocumentModelStore.hash(source), source, now));
        for (int i = 0; i < count; i++) {
            var model = models.exact(run.id(), MachineCandidateKind.DOCUMENT_CODE_ASSESSMENT_V2.name(), i, round).orElseThrow();
            var review = models.exact(run.id(), MachineCandidateKind.DOCUMENT_CODE_REVIEW_V2.name(), i, round).orElseThrow();
            if (!model.state().equals("VALIDATED") || !review.state().equals("VALIDATED")
                    || Objects.equals(model.externalSessionId(), review.externalSessionId())) throw changed();
            var input = json.readValue(review.inputJson(), DocumentModelInput.class);
            if (input.sourceRevision() != run.sourceRevision() || !json.writeValueAsString(input.directAssessment()).equals(model.outputJson())) throw changed();
            if (!validation.review(review, input, json.readValue(review.outputJson(), DirectDocumentAssessment.Review.class)).approved()) throw changed();
            var previous = assessments.batch(run.id(), 1, i);
            if (previous.isPresent()) {
                if (!previous.get().modelId().equals(model.id()) || !previous.get().reviewModelId().equals(review.id())) throw changed();
                continue;
            }
            var candidate = input.directAssessment();
            var items = validation.requirements(model, input, candidate).requirements();
            for (int n = 0; n < items.size(); n++) {
                var item = items.get(n); int ordinal = i * 256 + n;
                if (requirements.insert(new DocumentRequirementMapper.Requirement(run.id(), 1, item.key(), ordinal,
                        item.title(), item.group(), item.kind().name(), item.statement(), json.writeValueAsString(item.sources()),
                        "[]", json.writeValueAsString(item.issues()))) != 1) throw changed();
                var assessment = candidate.entries().get(n).assessment(); String body = json.writeValueAsString(assessment);
                if (assessments.insertItem(run.id(), 1, item.key(), body, DocumentModelStore.hash(body)) != 1) throw changed();
            }
            String normalized = json.writeValueAsString(validation.converted(candidate));
            if (assessments.insertBatch(new DocumentAssessmentMapper.Batch(run.id(), 1, i, round, model.id(), review.id(),
                    normalized, DocumentModelStore.hash(normalized), now)) != 1) throw changed();
            return false;
        }
        if (requirements.bindRevision(run.id(), current.version(), 1, 0, now) != 1) throw changed();
        return true;
    }
    private static ConflictException changed() { return new ConflictException("DOCUMENT_DIRECT_RESULT_CHANGED", "原文评审结果或复核身份已变化，不能发布不完整报告"); }
}
