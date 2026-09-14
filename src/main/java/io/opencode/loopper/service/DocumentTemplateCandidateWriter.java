package io.opencode.loopper.service;

import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.persistence.DocumentTemplateModelMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** Accepted output is immutable; remote terminal proof and semantic approval are separate settlements. */
@Component
public final class DocumentTemplateCandidateWriter implements AcceptedCandidateWriter {
    private final DocumentTemplateCandidatePolicy policy;
    private final DocumentTemplateModelMapper models;
    public DocumentTemplateCandidateWriter(DocumentTemplateCandidatePolicy policy, DocumentTemplateModelMapper models) {
        this.policy = policy; this.models = models;
    }
    @Override public boolean supports(MachineCandidateKind kind) { return policy.supports(kind); }
    @Override public void write(CandidatePolicy.Context context, String canonical, String hash) {
        var decision = policy.evaluate(context, canonical);
        if (!decision.accepted() || !canonical.equals(decision.canonicalCandidateJson())
                || !DocumentTemplateStorage.hash(canonical.getBytes(StandardCharsets.UTF_8)).equals(hash)
                || models.accept(context.owner().id(), canonical, hash, Instant.now().toString()) != 1)
            throw new ConflictException("DOCUMENT_CANDIDATE_ACCEPT_CONFLICT", "候选接受时冻结身份发生变化");
    }
}
