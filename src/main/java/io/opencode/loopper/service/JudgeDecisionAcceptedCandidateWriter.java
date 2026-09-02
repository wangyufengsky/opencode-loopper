package io.opencode.loopper.service;

import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.persistence.JudgeCandidateAcceptedResultRow;
import io.opencode.loopper.persistence.LoopperJudgeCandidateMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Recompiles from frozen V63 facts and inserts the immutable Judge result in the ACCEPTED transaction. */
final class JudgeDecisionAcceptedCandidateWriter implements AcceptedCandidateWriter {
    private final LoopperJudgeCandidateMapper mapper;
    private final JudgeDecisionCandidateCodec codec;
    private final JudgeDecisionCompilationInputLoader inputs;
    private final JudgeDecisionCompilation compilation;

    JudgeDecisionAcceptedCandidateWriter(
            LoopperJudgeCandidateMapper mapper, JudgeDecisionCandidateCodec codec,
            JudgeDecisionCompilationInputLoader inputs, JudgeDecisionCompilation compilation) {
        this.mapper = mapper;
        this.codec = codec;
        this.inputs = inputs;
        this.compilation = compilation;
    }

    @Override
    public boolean supports(MachineCandidateKind kind) {
        return kind == MachineCandidateKind.JUDGE_DECISION_V1;
    }

    @Override
    public void write(CandidatePolicy.Context context, String canonicalCandidateJson,
                      String candidatePayloadSha256) {
        JudgeDecisionCompilation.Result result = compilation.compileCandidate(
                inputs.load(context), canonicalCandidateJson);
        String actualPayloadSha = JudgeDecisionCandidateSourceSnapshotStore.sha256(canonicalCandidateJson);
        if (!result.accepted() || !canonicalCandidateJson.equals(result.canonicalCandidateJson())
                || candidatePayloadSha256 == null || !candidatePayloadSha256.equals(actualPayloadSha)
                || result.canonicalResultSha256() == null || result.deterministicReason() == null) {
            throw new ConflictException("JUDGE_ACCEPTED_RESULT_INVALID",
                    "Accepted Judge decision no longer compiles from frozen SQLite facts");
        }
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("contractVersion", JudgeDecisionCompilation.CONTRACT_VERSION);
        decision.put("role", result.candidate().role());
        decision.put("verdict", result.candidate().verdict());
        decision.put("reason", result.deterministicReason());
        decision.put("evidence", result.selectedEvidence());
        String canonicalDecision = codec.canonical(decision);
        var snapshot = mapper.findJudgeCandidateSourceSnapshot(context.runId())
                .orElseThrow(() -> new ConflictException("JUDGE_SOURCE_SNAPSHOT_INVALID",
                        "Frozen Judge source snapshot is missing"));
        String now = Instant.now().toString();
        JudgeCandidateAcceptedResultRow row = new JudgeCandidateAcceptedResultRow(
                context.runId(), context.owner().id(), snapshot.reviewBatchId(), snapshot.role(),
                context.sourceRevision(), context.ownerVersion(), context.contractVersion(),
                result.canonicalCandidateJson(), candidatePayloadSha256, canonicalDecision,
                result.canonicalResultSha256(), result.candidate().verdict(),
                result.deterministicReason(), result.canonicalEvidenceJson(), null, now, now, 0);
        if (mapper.insertJudgeCandidateAcceptedResult(row) != 1) {
            throw new ConflictException("JUDGE_ACCEPTED_RESULT_CONFLICT",
                    "Accepted Judge result could not be inserted");
        }
    }
}
