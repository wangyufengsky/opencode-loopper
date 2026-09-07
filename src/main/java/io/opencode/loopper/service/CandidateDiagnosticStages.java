package io.opencode.loopper.service;

import io.opencode.loopper.domain.MachineCandidateKind;
import java.util.function.Supplier;
import tools.jackson.databind.ObjectMapper;

/** Stages feedback while retaining the role policy's frozen-input and security checks. */
final class CandidateDiagnosticStages {
    private CandidateDiagnosticStages() { }

    static CandidatePolicy.Decision evaluate(ObjectMapper json, MachineCandidateKind kind, String candidate,
            Supplier<CandidatePolicy.Decision> evaluateRole) {
        // Role policies inspect authority embedded in values (paths, control characters, frozen IDs).
        // Do not replace them with schema-only checks, even for an invalid shape.
        CandidatePolicy.Decision semantic = evaluateRole.get();
        var shape = CandidateShapeValidator.validate(json, kind, candidate);
        if (!semantic.accepted() && !semantic.retryable() || shape.problems().isEmpty()) {
            return new CandidatePolicy.Decision(semantic.accepted(), semantic.canonicalCandidateJson(),
                    semantic.retryable(), semantic.fallbackEligible(),
                    CandidateDiagnosticEnricher.enrich(json, candidate, semantic.problems()), semantic.diagnosticsComplete());
        }
        var problems = CandidateDiagnosticEnricher.enrich(json, candidate, shape.problems());
        boolean safe = problems.stream().noneMatch(problem ->
                problem.category() == MachineCandidateSubmission.ProblemCategory.AUTHORITY
                        || problem.category() == MachineCandidateSubmission.ProblemCategory.SECURITY);
        return CandidatePolicy.Decision.rejected(safe, false, problems, shape.complete());
    }
}
