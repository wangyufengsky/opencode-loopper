package io.opencode.loopper.service;

import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.PackageDesignEvidenceMapper;
import java.util.List;
import tools.jackson.databind.ObjectMapper;

/** DB-read-only PACKAGE_DESIGN_V1 candidate policy with mechanical-only retry/fallback. */
final class PackageDesignCandidatePolicy implements CandidatePolicy {
    static final String WORKFLOW_STEP = PackageDesignCandidateCodec.CONTRACT_VERSION;
    static final int MAX_ATTEMPTS = 3;
    private final PackageDesignCompilationInputLoader inputs;
    private final PackageDesignCompilation compilation;
    private PackageDesignEvidenceMapper evidence;

    PackageDesignCandidatePolicy(LoopperMapper mapper, ObjectMapper json,
                                 PackageDesignCompilation compilation) {
        this(new PackageDesignCompilationInputLoader.MapperLoader(mapper, json), compilation);
        this.evidence = mapper;
    }

    PackageDesignCandidatePolicy(PackageDesignCompilationInputLoader inputs,
                                 PackageDesignCompilation compilation) {
        this.inputs = inputs;
        this.compilation = compilation;
    }

    PackageDesignCandidatePolicy(PackageDesignCompilationInputLoader inputs, PackageDesignCompilation compilation,
            PackageDesignEvidenceMapper evidence) {
        this(inputs, compilation); this.evidence = evidence;
    }

    @Override
    public boolean supports(MachineCandidateKind kind) {
        return kind == MachineCandidateKind.PACKAGE_DESIGN_V1;
    }

    @Override
    public Decision evaluate(Context context, String candidateJson) {
        if (context.candidateKind() != MachineCandidateKind.PACKAGE_DESIGN_V1
                || !(WORKFLOW_STEP.equals(context.workflowStep()) || PackageDesignGapPolicy.WORKFLOW_STEP.equals(context.workflowStep()) || PackageDesignV2Document.VERSION.equals(context.workflowStep()))
                || !(PackageDesignV2Document.VERSION.equals(context.workflowStep())
                    ? PackageDesignV2Document.VERSION.equals(context.contractVersion())
                    : PackageDesignCandidateCodec.CONTRACT_VERSION.equals(context.contractVersion()))
                || context.maxAttempts() != MAX_ATTEMPTS
                || context.owner().type()
                    != MachineCandidateSubmission.CandidateOwnerType.DESIGN_WORK_PACKAGE) {
            return Decision.rejected(false, false, List.of(new MachineCandidateSubmission.Problem(
                    "PACKAGE_DESIGN_RUN_CONTRACT_INVALID", "/candidate",
                    "候选运行不属于 PACKAGE_DESIGN_V1 冻结合同", List.of())));
        }
        try {
            var candidate = new ObjectMapper().readTree(candidateJson);
            String declared = candidate == null ? "" : candidate.path("contractVersion").asText();
            boolean v2Run = PackageDesignV2Document.VERSION.equals(context.contractVersion());
            if (v2Run ? !PackageDesignV2Document.VERSION.equals(declared)
                    : PackageDesignV2Document.VERSION.equals(declared.strip().toUpperCase(java.util.Locale.ROOT)))
                return Decision.rejected(true, false, List.of(new MachineCandidateSubmission.Problem(
                        "PACKAGE_CANDIDATE_CONTRACT_MISMATCH", "/contractVersion", "候选必须匹配运行冻结的合同", List.of(context.contractVersion()))));
        } catch (RuntimeException invalid) { /* Preserve the legacy compiler's original parse diagnostics. */ }
        var input = inputs.load(context).withContract(context.contractVersion());
        if (PackageDesignGapPolicy.WORKFLOW_STEP.equals(context.workflowStep()) || PackageDesignV2Document.VERSION.equals(context.workflowStep())) verifyEvidence(context.runId(), input);
        PackageDesignCompilation.Result result = compilation.compileCandidate(input, candidateJson);
        if (PackageDesignGapPolicy.WORKFLOW_STEP.equals(context.workflowStep())) result = PackageDesignGapPolicy.apply(result);
        if (result.accepted()) return Decision.accepted(result.canonicalCandidateJson());
        boolean fallback = result.retryable() && !result.problems().isEmpty()
                && result.problems().stream().allMatch(PackageDesignCompilation.Problem::fallbackEligible);
        return Decision.rejected(result.retryable(), fallback,
                result.problems().stream().map(PackageDesignCompilation.Problem::submissionProblem).toList());
    }

    private void verifyEvidence(String runId, PackageDesignCompilation.Input input) {
        if (evidence == null) throw new ConflictException("PACKAGE_EVIDENCE_UNAVAILABLE", "缺口判定需要冻结证据");
        var row = evidence.findPackageDesignEvidence(runId)
                .orElseThrow(() -> new ConflictException("PACKAGE_EVIDENCE_NOT_FROZEN", "候选不能在证据冻结之前校验"));
        if (!PackageDesignGapAssessment.VERSION.equals(row.policyVersion())
                || !PackageDesignEvidencePreparation.hash(input.requirementText().getBytes(java.nio.charset.StandardCharsets.UTF_8)).equals(row.requirementSha256())
                || !PackageDesignEvidencePreparation.hash(row.snapshotJson().getBytes(java.nio.charset.StandardCharsets.UTF_8)).equals(row.snapshotSha256())) {
            throw new ConflictException("PACKAGE_EVIDENCE_SOURCE_MISMATCH", "冻结证据版本或内容哈希不匹配");
        }
    }
}
