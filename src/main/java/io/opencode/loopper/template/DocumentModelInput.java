package io.opencode.loopper.template;

import java.util.List;
import io.opencode.loopper.workflow.WorkResult;

/** Immutable DB-only input identity; text is fetched through explicit scoped section references. */
public record DocumentModelInput(List<SectionRef> sections, DocumentRequirements.Candidate requirements,
        DocumentRequirements.Review requirementFeedback, String snapshotSha,
        RequirementCodeAssessment.Candidate assessment, RequirementCodeAssessment.Review assessmentFeedback,
        List<Clarification> clarifications, DirectDocumentAssessment.Candidate directAssessment,
        DirectDocumentAssessment.Review directFeedback, int sourceRevision, int interactionVersion,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
        List<WorkResult.Binding> workResults) {
    public DocumentModelInput(List<SectionRef> sections, DocumentRequirements.Candidate requirements,
            DocumentRequirements.Review requirementFeedback, String snapshotSha,
            RequirementCodeAssessment.Candidate assessment, RequirementCodeAssessment.Review assessmentFeedback,
            List<Clarification> clarifications, DirectDocumentAssessment.Candidate directAssessment,
            DirectDocumentAssessment.Review directFeedback, int sourceRevision, int interactionVersion) {
        this(sections, requirements, requirementFeedback, snapshotSha, assessment, assessmentFeedback,
                clarifications, directAssessment, directFeedback, sourceRevision, interactionVersion, List.of());
    }
    public DocumentModelInput(List<SectionRef> sections, DocumentRequirements.Candidate requirements,
            DocumentRequirements.Review requirementFeedback, String snapshotSha,
            RequirementCodeAssessment.Candidate assessment, RequirementCodeAssessment.Review assessmentFeedback,
            List<Clarification> clarifications, DirectDocumentAssessment.Candidate directAssessment,
            DirectDocumentAssessment.Review directFeedback, int sourceRevision) {
        this(sections, requirements, requirementFeedback, snapshotSha, assessment, assessmentFeedback,
                clarifications, directAssessment, directFeedback, sourceRevision, 0);
    }
    public DocumentModelInput {
        clarifications = clarifications == null ? List.of() : List.copyOf(clarifications);
        workResults = workResults == null ? List.of() : List.copyOf(workResults);
    }
    public DocumentModelInput withWorkResults(List<WorkResult.Binding> bindings) {
        return new DocumentModelInput(sections, requirements, requirementFeedback, snapshotSha, assessment,
                assessmentFeedback, clarifications, directAssessment, directFeedback, sourceRevision,
                interactionVersion, bindings);
    }
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
