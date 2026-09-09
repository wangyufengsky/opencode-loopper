package io.opencode.loopper.service;

import tools.jackson.databind.ObjectMapper;

/** Root shape diagnostics precede dependent semantic checks for the typed package channel. */
final class PackageDesignCandidateEvaluation {
    private PackageDesignCandidateEvaluation() { }
    static CandidatePolicy.Decision evaluate(ObjectMapper json, CandidatePolicy policy,
            CandidatePolicy.Context context, String candidateJson) {
        try {
            var root = json.readTree(candidateJson);
            var authority = root == null ? null : PackageDesignCandidateCodec.securityBoundary(root);
            if (authority != null) return CandidatePolicy.Decision.rejected(false, false,
                    CandidateDiagnosticEnricher.enrich(json, candidateJson, java.util.List.of(authority.submissionProblem())));
        } catch (tools.jackson.core.JacksonException invalid) { /* Shape validation supplies the parse diagnostic. */ }
        var shape = "PACKAGE_DESIGN_V2".equals(context.contractVersion())
                ? CandidateShapeValidator.validate(json, io.opencode.loopper.runtime.InternalMcpContractCatalog.packageDesignV2InputSchema(), candidateJson)
                : CandidateShapeValidator.validate(json, context.candidateKind(), candidateJson);
        if (!shape.problems().isEmpty()) {
            var problems = CandidateDiagnosticEnricher.enrich(json, candidateJson, shape.problems());
            return CandidatePolicy.Decision.rejected(true, false, problems, shape.complete());
        }
        var decision = policy.evaluate(context, candidateJson);
        return new CandidatePolicy.Decision(decision.accepted(), decision.canonicalCandidateJson(), decision.retryable(),
                decision.fallbackEligible(), CandidateDiagnosticEnricher.enrich(json, candidateJson, decision.problems()),
                decision.diagnosticsComplete());
    }
}
