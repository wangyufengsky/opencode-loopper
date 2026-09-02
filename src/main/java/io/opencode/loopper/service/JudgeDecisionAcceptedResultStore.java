package io.opencode.loopper.service;

import io.opencode.loopper.persistence.JudgeCandidateAcceptedResultRow;
import io.opencode.loopper.persistence.LoopperJudgeCandidateMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Revalidates an accepted Judge result against the immutable source snapshot before settlement. */
final class JudgeDecisionAcceptedResultStore {
    private final LoopperJudgeCandidateMapper mapper;
    private final JudgeDecisionCandidateCodec codec;
    private final JudgeDecisionCompilationInputLoader inputs;
    private final JudgeDecisionCompilation compilation;

    JudgeDecisionAcceptedResultStore(
            LoopperJudgeCandidateMapper mapper, JudgeDecisionCandidateCodec codec,
            JudgeDecisionCompilationInputLoader inputs, JudgeDecisionCompilation compilation) {
        this.mapper = mapper;
        this.codec = codec;
        this.inputs = inputs;
        this.compilation = compilation;
    }

    Optional<Accepted> find(String runId) {
        return mapper.findJudgeCandidateAcceptedResult(runId).map(this::requireValid);
    }

    Accepted require(String runId) {
        return find(runId).orElseThrow(() -> new ConflictException("JUDGE_ACCEPTED_RESULT_MISSING",
                "Judge accepted result is missing"));
    }

    private Accepted requireValid(JudgeCandidateAcceptedResultRow row) {
        var snapshot = mapper.findJudgeCandidateSourceSnapshot(row.candidateRunId())
                .orElseThrow(() -> invalid("Judge source snapshot is missing"));
        CandidatePolicy.Context context = new CandidatePolicy.Context(
                row.candidateRunId(), MachineCandidateSubmission.CandidateScope.task(snapshot.taskId()),
                MachineCandidateSubmission.CandidateOwnerRef.judgeRun(row.judgeRunId()),
                io.opencode.loopper.domain.MachineCandidateKind.JUDGE_DECISION_V1,
                JudgeDecisionCandidatePolicy.WORKFLOW_STEP, row.sourceRevision(), row.ownerVersion(),
                row.contractVersion(), JudgeDecisionCandidatePolicy.MAX_ATTEMPTS, 0);
        JudgeDecisionCompilation.Result result = compilation.compileCandidate(
                inputs.load(context), row.canonicalCandidateJson());
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("contractVersion", JudgeDecisionCompilation.CONTRACT_VERSION);
        decision.put("role", result.accepted() ? result.candidate().role() : null);
        decision.put("verdict", result.accepted() ? result.candidate().verdict() : null);
        decision.put("reason", result.deterministicReason());
        decision.put("evidence", result.selectedEvidence());
        if (!result.accepted() || !row.candidatePayloadSha256().equals(
                    JudgeDecisionCandidateSourceSnapshotStore.sha256(row.canonicalCandidateJson()))
                || !row.canonicalDecisionJson().equals(codec.canonical(decision))
                || !row.canonicalResultSha256().equals(result.canonicalResultSha256())
                || !row.verdict().equals(result.candidate().verdict())
                || !row.reason().equals(result.deterministicReason())
                || !row.evidenceJson().equals(result.canonicalEvidenceJson())
                || !row.role().equals(snapshot.role()) || !row.reviewBatchId().equals(snapshot.reviewBatchId())) {
            throw invalid("Accepted Judge result no longer matches its deterministic compilation");
        }
        return new Accepted(row, result);
    }

    private static ConflictException invalid(String detail) {
        return new ConflictException("JUDGE_ACCEPTED_RESULT_INVALID", detail);
    }

    record Accepted(JudgeCandidateAcceptedResultRow row, JudgeDecisionCompilation.Result compiled) { }
}
