package io.opencode.loopper.service;

import io.opencode.loopper.domain.MachineCandidateRunState;
import io.opencode.loopper.persistence.DocumentTemplateModelMapper;
import io.opencode.loopper.persistence.DocumentTemplateModelRow;
import io.opencode.loopper.runtime.DocumentTemplateProfiles;
import org.springframework.stereotype.Component;

/** Read tools share exactly the candidate's project, generation, owner and runtime boundary. */
@Component
public final class DocumentModelAccess {
    private final MachineCandidateSubmission submissions;
    private final DocumentTemplateCandidateGuard guard;
    private final org.springframework.beans.factory.ObjectProvider<CandidateRuntimeBindingService> runtime;
    private final DocumentTemplateModelMapper models;
    public DocumentModelAccess(MachineCandidateSubmission submissions, DocumentTemplateCandidateGuard guard,
            org.springframework.beans.factory.ObjectProvider<CandidateRuntimeBindingService> runtime, DocumentTemplateModelMapper models) {
        this.submissions = submissions; this.guard = guard; this.runtime = runtime; this.models = models;
    }
    public DocumentTemplateModelRow require(String id, boolean code) {
        var candidate = submissions.find(id).orElseThrow(() -> new NotFoundException("文档候选运行不存在"));
        if (!DocumentTemplateProfiles.supports(candidate.candidateKind()) || candidate.state() != MachineCandidateRunState.OPEN)
            throw new ConflictException("DOCUMENT_READ_SCOPE_INVALID", "该候选没有活动的冻结读取许可");
        if (code && candidate.candidateKind() != io.opencode.loopper.domain.MachineCandidateKind.REQUIREMENT_CODE_ASSESSMENT_V1
                && candidate.candidateKind() != io.opencode.loopper.domain.MachineCandidateKind.REQUIREMENT_ASSESSMENT_REVIEW_V1
                && candidate.candidateKind() != io.opencode.loopper.domain.MachineCandidateKind.DOCUMENT_CODE_ASSESSMENT_V2
                && candidate.candidateKind() != io.opencode.loopper.domain.MachineCandidateKind.DOCUMENT_CODE_REVIEW_V2)
            throw new ConflictException("DOCUMENT_CODE_PERMISSION_DENIED", "当前角色没有冻结代码读取权限");
        guard.validate(candidate, MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP);
        var binding = runtime.getIfAvailable();
        if (binding == null) throw new ConflictException("DOCUMENT_RUNTIME_GUARD_REQUIRED", "需求模板需要运行时代际校验");
        binding.validate(candidate, MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP);
        return models.find(candidate.owner().id()).orElseThrow();
    }
}
