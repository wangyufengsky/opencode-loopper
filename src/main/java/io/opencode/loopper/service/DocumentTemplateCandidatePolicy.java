package io.opencode.loopper.service;

import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.persistence.DocumentTemplateMapper;
import io.opencode.loopper.persistence.DocumentTemplateModelMapper;
import io.opencode.loopper.persistence.DocumentTemplateModelRow;
import io.opencode.loopper.runtime.DocumentTemplateProfiles;
import io.opencode.loopper.template.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Frozen DB inputs and role semantics; no source files or provider calls inside candidate acceptance. */
@Component
public final class DocumentTemplateCandidatePolicy implements CandidatePolicy {
    private final DocumentTemplateModelMapper models;
    private final DocumentTemplateMapper documents;
    private final DocumentAssessmentValidation assessments;
    private final ObjectMapper json;
    private final DirectDocumentAssessmentValidation direct;
    public DocumentTemplateCandidatePolicy(DocumentTemplateModelMapper models, DocumentTemplateMapper documents,
            DocumentAssessmentValidation assessments, ObjectMapper json, DirectDocumentAssessmentValidation direct) {
        this.models = models; this.documents = documents; this.assessments = assessments; this.json = json; this.direct = direct;
    }
    @Override public boolean supports(MachineCandidateKind kind) { return DocumentTemplateProfiles.supports(kind); }
    @Override public Decision evaluate(Context context, String candidateJson) {
        try {
            var model = models.find(context.owner().id()).orElseThrow(() ->
                    new ConflictException("DOCUMENT_CANDIDATE_OWNER_MISSING", "文档候选运行不存在"));
            if (!context.candidateKind().name().equals(model.candidateKind())
                    || !context.contractVersion().equals(model.candidateKind())
                    || context.sourceRevision() != model.generation()) {
                throw new ConflictException("DOCUMENT_CANDIDATE_SCOPE_STALE", "文档候选冻结身份已变化");
            }
            var input = input(model);
            Object candidate = switch (context.candidateKind()) {
                case DOCUMENT_CODE_ASSESSMENT_V2 -> direct.assessment(model, input, json.readValue(candidateJson, DirectDocumentAssessment.Candidate.class));
                case DOCUMENT_CODE_REVIEW_V2 -> direct.review(model, input, json.readValue(candidateJson, DirectDocumentAssessment.Review.class));
                case DOCUMENT_REQUIREMENTS_V1 -> {
                    var proposed = json.readValue(candidateJson, DocumentRequirements.Candidate.class);
                    yield DocumentRequirementValidation.extraction(sections(model, input, proposed), proposed);
                }
                case DOCUMENT_REQUIREMENT_REVIEW_V1 -> DocumentRequirementValidation.review(sections(model, input),
                        input.requirements(), json.readValue(candidateJson, DocumentRequirements.Review.class));
                case REQUIREMENT_CODE_ASSESSMENT_V1 -> assessments.assessment(model, input,
                        json.readValue(candidateJson, RequirementCodeAssessment.Candidate.class));
                case REQUIREMENT_ASSESSMENT_REVIEW_V1 -> assessments.review(model, input,
                        json.readValue(candidateJson, RequirementCodeAssessment.Review.class));
                default -> throw new IllegalArgumentException("Unsupported document candidate");
            };
            return Decision.accepted(json.writeValueAsString(candidate));
        } catch (BadRequestException invalid) {
            return Decision.rejected(true, List.of(new MachineCandidateSubmission.Problem(invalid.code(), "/candidate", invalid.getMessage())));
        } catch (JacksonException invalid) {
            return Decision.rejected(true, List.of(new MachineCandidateSubmission.Problem(
                    "DOCUMENT_CANDIDATE_JSON_INVALID", "/candidate", "候选不符合角色专属完整结构")));
        }
    }
    DocumentModelInput input(DocumentTemplateModelRow model) {
        if (!DocumentTemplateStorage.hash(model.inputJson().getBytes(StandardCharsets.UTF_8)).equals(model.inputSha256()))
            throw new ConflictException("DOCUMENT_INPUT_CHANGED", "冻结候选输入校验失败");
        return json.readValue(model.inputJson(), DocumentModelInput.class);
    }
    List<DocumentRequirements.SourceSection> sections(DocumentTemplateModelRow model, DocumentModelInput input) {
        return sections(model, input, input.requirements());
    }
    private List<DocumentRequirements.SourceSection> sections(DocumentTemplateModelRow model, DocumentModelInput input,
            DocumentRequirements.Candidate candidate) {
        if (input.sections() == null || input.sections().isEmpty() || input.sections().size() > 2048)
            throw new ConflictException("DOCUMENT_INPUT_CHANGED", "冻结文档分段清单缺失或超限");
        var selected = new java.util.LinkedHashMap<String, DocumentModelInput.SectionRef>();
        input.sections().forEach(ref -> selected.put(ref.fileId() + ":" + ref.section(), ref));
        if (candidate != null && candidate.requirements() != null) {
            for (var requirement : candidate.requirements()) {
                if (requirement == null || requirement.sources() == null) continue;
                for (var ref : requirement.sources()) {
                    if (ref == null) continue;
                    documents.file(model.runId(), ref.fileId()).orElseThrow(() -> new BadRequestException(
                            "DOCUMENT_REFERENCE_SCOPE", "补充原文引用不属于本次上传文档"));
                    var section = documents.section(ref.fileId(), ref.section()).orElseThrow(() -> new BadRequestException(
                            "DOCUMENT_REFERENCE_MISSING", "补充原文分段不存在"));
                    selected.putIfAbsent(ref.fileId() + ":" + ref.section(), new DocumentModelInput.SectionRef(ref.fileId(), ref.section(), section.sha256()));
                }
            }
        }
        if (selected.size() > 2048) throw new BadRequestException("DOCUMENT_REFERENCE_LIMIT", "单批补充引用超过分段上限");
        return selected.values().stream().map(ref -> {
            documents.file(model.runId(), ref.fileId()).orElseThrow(() ->
                    new ConflictException("DOCUMENT_INPUT_SCOPE", "文档不属于当前模板"));
            var section = documents.section(ref.fileId(), ref.section()).orElseThrow(() ->
                    new ConflictException("DOCUMENT_INPUT_CHANGED", "冻结分段缺失"));
            if (!ref.sha256().equals(section.sha256()) || !section.sha256().equals(
                    DocumentTemplateStorage.hash(section.content().getBytes(StandardCharsets.UTF_8))))
                throw new ConflictException("DOCUMENT_INPUT_CHANGED", "冻结文档分段身份校验失败");
            return new DocumentRequirements.SourceSection(ref.fileId(), ref.section(), section.title(), section.content(), section.sha256());
        }).toList();
    }
}
