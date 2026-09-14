package io.opencode.loopper.service;

import io.opencode.loopper.domain.DocumentTemplateState;
import io.opencode.loopper.persistence.DocumentTemplateMapper;
import io.opencode.loopper.persistence.DocumentTemplateModelMapper;
import io.opencode.loopper.persistence.DocumentTemplateModelRow;
import io.opencode.loopper.runtime.DocumentTemplateProfiles;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** DB-only owner validation, repeated inside the candidate acceptance transaction. */
@Component
public final class DocumentTemplateCandidateGuard implements CandidateRunGuard {
    private final DocumentTemplateModelMapper models;
    private final DocumentTemplateMapper runs;
    public DocumentTemplateCandidateGuard(DocumentTemplateModelMapper models, DocumentTemplateMapper runs) {
        this.models = models; this.runs = runs;
    }
    @Override public void validate(MachineCandidateSubmission.RunSnapshot candidate,
                                   MachineCandidateSubmission.SubmissionChannel channel) {
        if (!DocumentTemplateProfiles.supports(candidate.candidateKind())) return;
        var model = models.find(candidate.owner().id()).orElseThrow(DocumentTemplateCandidateGuard::stale);
        var owner = runs.find(model.runId()).orElseThrow(DocumentTemplateCandidateGuard::stale);
        if (channel != MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP
                || candidate.owner().type() != MachineCandidateSubmission.CandidateOwnerType.DOCUMENT_TEMPLATE_MODEL_RUN
                || candidate.scope().type() != MachineCandidateSubmission.CandidateScopeType.PROJECT
                || !owner.projectId().equals(candidate.scope().id())
                || !model.candidateKind().equals(candidate.candidateKind().name())
                || !candidate.contractVersion().equals(model.candidateKind())
                || !candidate.workflowStep().equals(model.candidateKind())
                || candidate.sourceRevision() != model.generation()
                || !Objects.equals(candidate.externalSessionId(), model.externalSessionId())
                || !writable(model, candidate.ownerVersion())
                || DocumentTemplateState.valueOf(owner.state()).terminal()
                || owner.state().equals("WAITING_INPUT") || owner.state().equals("STOPPING")) throw stale();
    }
    private static boolean writable(DocumentTemplateModelRow model, long openedVersion) {
        // Opening occurs at DISPATCHING; the one START transition may race the first MCP submission.
        return (model.state().equals("DISPATCHING") && model.version() == openedVersion)
                || (model.state().equals("RUNNING") && model.version() == openedVersion + 1);
    }
    private static ConflictException stale() {
        return new ConflictException("DOCUMENT_CANDIDATE_SCOPE_STALE", "候选的模板、版本、会话或运行状态已变化");
    }
}
