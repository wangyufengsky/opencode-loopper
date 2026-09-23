package io.opencode.loopper.service;

import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.persistence.SourceTemplateModelMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** Accepted bytes are saved once; the coordinator still requires remote termination and independent review. */
@Component
public final class SourceTemplateCandidateWriter implements AcceptedCandidateWriter {
    private final SourceTemplateCandidatePolicy policy;
    private final SourceTemplateModelMapper models;
    public SourceTemplateCandidateWriter(SourceTemplateCandidatePolicy policy, SourceTemplateModelMapper models) {
        this.policy = policy; this.models = models;
    }
    @Override public boolean supports(MachineCandidateKind kind) { return policy.supports(kind); }
    @Override public void write(CandidatePolicy.Context context, String candidate, String hash) {
        var checked = policy.evaluate(context, candidate);
        if (!checked.accepted() || !candidate.equals(checked.canonicalCandidateJson())
                || !SourceTreeCapture.hash(candidate.getBytes(StandardCharsets.UTF_8)).equals(hash)
                || models.accept(context.owner().id(), candidate, hash, Instant.now().toString()) != 1)
            throw new ConflictException("SOURCE_CANDIDATE_ACCEPT_CONFLICT", "源码候选接受时身份发生变化，请重新查询");
    }
}
