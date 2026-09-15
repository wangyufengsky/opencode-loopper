package io.opencode.loopper.template;

import java.util.List;

/** Immutable DB-only input identity; text is fetched through explicit scoped section references. */
public record DocumentModelInput(List<SectionRef> sections, DocumentRequirements.Candidate requirements,
        DocumentRequirements.Review requirementFeedback, String snapshotSha,
        RequirementCodeAssessment.Candidate assessment, RequirementCodeAssessment.Review assessmentFeedback,
        List<Clarification> clarifications, DirectDocumentAssessment.Candidate directAssessment,
        DirectDocumentAssessment.Review directFeedback, int sourceRevision, int interactionVersion) {
    public DocumentModelInput(List<SectionRef> sections, DocumentRequirements.Candidate requirements,
            DocumentRequirements.Review requirementFeedback, String snapshotSha,
            RequirementCodeAssessment.Candidate assessment, RequirementCodeAssessment.Review assessmentFeedback,
            List<Clarification> clarifications, DirectDocumentAssessment.Candidate directAssessment,
            DirectDocumentAssessment.Review directFeedback, int sourceRevision) {
        this(sections, requirements, requirementFeedback, snapshotSha, assessment, assessmentFeedback,
                clarifications, directAssessment, directFeedback, sourceRevision, 0);
    }
    public DocumentModelInput { clarifications = clarifications == null ? List.of() : List.copyOf(clarifications); }
    public DocumentModelInput(List<SectionRef> sections, DocumentRequirements.Candidate requirements,
            DocumentRequirements.Review requirementFeedback, String snapshotSha,
            RequirementCodeAssessment.Candidate assessment, RequirementCodeAssessment.Review assessmentFeedback,
            List<Clarification> clarifications) {
        this(sections, requirements, requirementFeedback, snapshotSha, assessment, assessmentFeedback, clarifications, null, null, 0);
    }
    public DocumentModelInput(List<SectionRef> sections, DocumentRequirements.Candidate requirements,
            DocumentRequirements.Review requirementFeedback, String snapshotSha,
            RequirementCodeAssessment.Candidate assessment, RequirementCodeAssessment.Review assessmentFeedback) {
        this(sections, requirements, requirementFeedback, snapshotSha, assessment, assessmentFeedback, List.of());
    }
    public record Clarification(int sourceRevision, String requirementKey, String statement, List<String> issues, String answer) { }
    public record SectionRef(String fileId, int section, String sha256) { }
}
