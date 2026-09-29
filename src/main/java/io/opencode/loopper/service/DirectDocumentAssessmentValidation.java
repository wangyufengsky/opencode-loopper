package io.opencode.loopper.service;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.template.*;
import org.springframework.stereotype.Component;

/** Legacy source ownership and per-session receipts remain authoritative for historical template runs. */
@Component
public final class DirectDocumentAssessmentValidation {
    private final DocumentTemplateMapper documents;
    private final DocumentAssessmentValidation code;
    public DirectDocumentAssessmentValidation(DocumentTemplateMapper documents,DocumentAssessmentValidation code){this.documents=documents;this.code=code;}
    public DirectDocumentAssessment.Candidate assessment(DocumentTemplateModelRow model,DocumentModelInput input,DirectDocumentAssessment.Candidate candidate) {
        return rules(model,input).assessment(context(model,input),candidate);
    }
    public DirectDocumentAssessment.Review review(DocumentTemplateModelRow model,DocumentModelInput input,DirectDocumentAssessment.Review review) {
        return rules(model,input).review(context(model,input),input.directAssessment(),review);
    }
    public DocumentRequirements.Candidate requirements(DocumentTemplateModelRow model,DocumentModelInput input,DirectDocumentAssessment.Candidate candidate) {
        return rules(model,input).requirements(context(model,input),candidate);
    }
    public RequirementCodeAssessment.Candidate converted(DirectDocumentAssessment.Candidate candidate) {
        return new RequirementCodeAssessment.Candidate(candidate.snapshotSha(),candidate.entries().stream().map(DirectDocumentAssessment.Entry::assessment).toList(),candidate.findings(),candidate.limitations());
    }
    private DirectDocumentAssessmentRules rules(DocumentTemplateModelRow model,DocumentModelInput input) {
        return new DirectDocumentAssessmentRules((ref,read)->{
            if(input.sourceRevision()<=0)throw invalid("原文版本或位置缺失");
            var page=documents.sourceSections(model.runId(),input.sourceRevision(),ref.fileId(),ref.section(),1);
            if(page.size()!=1||page.getFirst().ordinal()!=ref.section())throw invalid("引用不属于本次冻结原文版本");
            var section=page.getFirst();if(!DocumentModelStore.hash(section.content()).equals(section.sha256()))throw invalid("原文内容哈希不符");
            if(read&&!documents.sourceRead(model.externalSessionId(),model.runId(),input.sourceRevision(),ref.fileId(),ref.section(),section.sha256()))
                throw invalid("须由当前角色实际读取引用原文，其他角色的读取不构成本次证据");
            return new DirectDocumentAssessmentRules.Section(section.content());
        },code.rules(model));
    }
    private static DirectDocumentAssessmentRules.Context context(DocumentTemplateModelRow model,DocumentModelInput input) {
        return new DirectDocumentAssessmentRules.Context(model.ordinal(),input.snapshotSha(),input.interactionVersion(),input.sections()==null?null:
            input.sections().stream().map(ref->new DirectDocumentAssessment.Source(ref.fileId(),ref.section())).toList());
    }
    private static BadRequestException invalid(String message){return new BadRequestException("DOCUMENT_DIRECT_ASSESSMENT_INVALID",message);}
}
