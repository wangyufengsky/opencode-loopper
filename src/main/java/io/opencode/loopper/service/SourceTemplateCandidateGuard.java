package io.opencode.loopper.service;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.runtime.SourceTemplateProfiles;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Repeated DB-only ownership checks within the candidate acceptance transaction. */
@Component
public final class SourceTemplateCandidateGuard implements CandidateRunGuard {
    private final SourceTemplateModelMapper models;
    private final SourceTemplateMapper runs;
    public SourceTemplateCandidateGuard(SourceTemplateModelMapper models, SourceTemplateMapper runs) {
        this.models = models; this.runs = runs;
    }
    @Override public void validate(MachineCandidateSubmission.RunSnapshot candidate,
            MachineCandidateSubmission.SubmissionChannel channel) {
        if (!SourceTemplateProfiles.supports(candidate.candidateKind())) return;
        var model = models.find(candidate.owner().id()).orElseThrow(SourceTemplateCandidateGuard::stale);
        var owner = runs.find(model.runId()).orElseThrow(SourceTemplateCandidateGuard::stale);
        if (channel != MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP
                || candidate.owner().type() != MachineCandidateSubmission.CandidateOwnerType.SOURCE_TEMPLATE_MODEL_RUN
                || candidate.scope().type() != MachineCandidateSubmission.CandidateScopeType.PROJECT
                || !owner.projectId().equals(candidate.scope().id())
                || !model.candidateKind().equals(candidate.candidateKind().name())
                || !candidate.contractVersion().equals(model.candidateKind())
                || !candidate.workflowStep().equals(model.candidateKind())
                || candidate.sourceRevision() != model.generation()
                || !Objects.equals(candidate.externalSessionId(), model.externalSessionId())
                || !(model.state().equals("DISPATCHING") && model.version() == candidate.ownerVersion()
                    || model.state().equals("RUNNING") && model.version() == candidate.ownerVersion() + 1)
                || !java.util.Set.of("WRITING", "REVIEWING").contains(owner.state())) throw stale();
        var current = models.exact(model.runId(), model.candidateKind(), model.ordinal(), model.generation()).orElseThrow();
        if (!current.id().equals(model.id())
                || models.progress(model.runId()).orElseThrow(SourceTemplateCandidateGuard::stale).generation() != model.generation()) throw stale();
    }
    private static ConflictException stale() {
        return new ConflictException("SOURCE_CANDIDATE_SCOPE_STALE", "源码候选的任务、批次、会话或冻结代次已变化");
    }
}
