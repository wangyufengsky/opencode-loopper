package io.opencode.loopper.service;

import io.opencode.loopper.domain.MachineCandidateRunState;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.runtime.SourceTemplateProfiles;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Frozen reads require the same active owner and managed generation as formal submission. */
@Component
public final class SourceModelAccess {
    private final MachineCandidateSubmission submissions;
    private final SourceTemplateCandidateGuard guard;
    private final ObjectProvider<CandidateRuntimeBindingService> runtime;
    private final SourceTemplateModelMapper models;
    public SourceModelAccess(MachineCandidateSubmission submissions, SourceTemplateCandidateGuard guard,
            ObjectProvider<CandidateRuntimeBindingService> runtime, SourceTemplateModelMapper models) {
        this.submissions = submissions; this.guard = guard; this.runtime = runtime; this.models = models;
    }
    public SourceTemplateModelRow require(String id) {
        var candidate = submissions.find(id).orElseThrow(() -> new NotFoundException("源码候选运行不存在"));
        if (!SourceTemplateProfiles.supports(candidate.candidateKind()) || candidate.state() != MachineCandidateRunState.OPEN)
            throw new ConflictException("SOURCE_READ_SCOPE_INVALID", "当前角色没有活动的冻结源码读取许可");
        guard.validate(candidate, MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP);
        var binding = runtime.getIfAvailable();
        if (binding == null) throw new ConflictException("SOURCE_RUNTIME_GUARD_REQUIRED", "源码模板需要托管运行时代际校验");
        binding.validate(candidate, MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP);
        return models.find(candidate.owner().id()).orElseThrow();
    }
}
