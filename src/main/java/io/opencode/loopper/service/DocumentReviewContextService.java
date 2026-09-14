package io.opencode.loopper.service;

import io.opencode.loopper.api.CursorPage;
import io.opencode.loopper.persistence.DocumentTemplateModelMapper;
import io.opencode.loopper.template.RequirementCodeAssessment;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Cross-batch evidence is scoped to the current immutable assessment generation. */
@Service
public final class DocumentReviewContextService {
    private final DocumentModelAccess access;
    private final DocumentTemplateModelMapper models;
    private final ObjectMapper json;
    public DocumentReviewContextService(DocumentModelAccess access, DocumentTemplateModelMapper models, ObjectMapper json) {
        this.access = access; this.models = models; this.json = json;
    }
    public CursorPage<DocumentTemplateModelMapper.AssessmentSummary> list(String id, int after, int limit) {
        var model = access.require(id, true);
        if (after < -1 || limit < 1 || limit > 100) throw invalid();
        var rows = models.assessmentSummaries(model.runId(), model.generation(), after, limit + 1);
        var items = rows.stream().limit(limit).toList();
        return new CursorPage<>(items, rows.size() > limit ? String.valueOf(items.getLast().ordinal()) : null);
    }
    public Object read(String id, int ordinal, String expectedSha256) {
        var model = access.require(id, true);
        boolean direct = model.candidateKind().equals("DOCUMENT_CODE_ASSESSMENT_V2") || model.candidateKind().equals("DOCUMENT_CODE_REVIEW_V2");
        var target = models.exact(model.runId(), direct ? "DOCUMENT_CODE_ASSESSMENT_V2" : "REQUIREMENT_CODE_ASSESSMENT_V1", ordinal, model.generation()).orElseThrow(DocumentReviewContextService::invalid);
        if (!target.state().equals("VALIDATED") || target.outputJson() == null || target.outputJson().length() > 128 * 1024
                || !DocumentModelStore.hash(target.outputJson()).equals(expectedSha256)) throw invalid();
        return direct ? json.readValue(target.outputJson(), io.opencode.loopper.template.DirectDocumentAssessment.Candidate.class)
                : json.readValue(target.outputJson(), RequirementCodeAssessment.Candidate.class);
    }
    private static BadRequestException invalid() { return new BadRequestException("DOCUMENT_REVIEW_CONTEXT_INVALID", "跨批次引用不属于本次已冻结的评审结果"); }
}
