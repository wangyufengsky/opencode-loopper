package io.opencode.loopper.service;

import io.opencode.loopper.persistence.DocumentCodeMapper;
import io.opencode.loopper.persistence.DocumentTemplateModelRow;
import io.opencode.loopper.template.DocumentModelInput;
import io.opencode.loopper.template.RequirementCodeAssessment;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Legacy template ownership supplies fixed reads to the shared assessment rules. */
@Component
public final class DocumentAssessmentValidation {
    private final DocumentCodeMapper code;
    public DocumentAssessmentValidation(DocumentCodeMapper code){this.code=code;}
    public RequirementCodeAssessment.Candidate assessment(DocumentTemplateModelRow model,DocumentModelInput input,RequirementCodeAssessment.Candidate candidate) {
        return rules(model).assessment(context(input),candidate);
    }
    public RequirementCodeAssessment.Review review(DocumentTemplateModelRow model,DocumentModelInput input,RequirementCodeAssessment.Review review) {
        return rules(model).review(context(input),review);
    }
    DocumentAssessmentRules rules(DocumentTemplateModelRow model) {
        return new DocumentAssessmentRules(new DocumentAssessmentRules.Evidence() {
            public boolean contains(String path){return code.file(model.runId(),path).isPresent();}
            public Optional<DocumentAssessmentRules.Read> read(String path,int start,int end) {
                return code.evidence(model.id(),path,start,end).map(row->new DocumentAssessmentRules.Read(row.blobSha(),row.startLine(),row.content()));
            }
        });
    }
    private static DocumentAssessmentRules.Context context(DocumentModelInput input) {
        return new DocumentAssessmentRules.Context(input.snapshotSha(),input.requirements(),input.assessment(),input.directAssessment()!=null);
    }
}
