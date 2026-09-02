package io.opencode.loopper.service;

import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.persistence.JudgeCandidateSourceSnapshotRow;
import io.opencode.loopper.persistence.JudgeReviewBatchRow;
import io.opencode.loopper.persistence.JudgeRunRow;
import io.opencode.loopper.persistence.LoopperJudgeCandidateMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

/** Freezes every Judge compilation input before the generic coordinator can perform remote I/O. */
final class JudgeDecisionCandidateSourceSnapshotStore {
    private final LoopperJudgeCandidateMapper mapper;
    private final JudgeDecisionCandidateCodec codec;

    JudgeDecisionCandidateSourceSnapshotStore(
            LoopperJudgeCandidateMapper mapper, JudgeDecisionCandidateCodec codec) {
        this.mapper = mapper;
        this.codec = codec;
    }

    JudgeCandidateSourceSnapshotRow freeze(
            CandidatePolicy.Context context, JudgeRunRow judge, JudgeReviewBatchRow batch,
            TaskEvidenceService.JudgeCandidateSource source) {
        requireExact(context, judge, batch, source);
        String evidenceJson = codec.canonical(source.evidenceCatalog());
        JudgeCandidateSourceSnapshotRow requested = new JudgeCandidateSourceSnapshotRow(
                context.runId(), judge.id(), judge.taskId(), batch.executionCycleId(), judge.attemptId(),
                batch.id(), judge.role(), judge.ordinal(), context.sourceRevision(),
                judge.version(), context.contractVersion(), source.prompt(), sha256(source.prompt()),
                evidenceJson, sha256(evidenceJson), Instant.now().toString());
        JudgeCandidateSourceSnapshotRow existing = mapper
                .findJudgeCandidateSourceSnapshot(context.runId()).orElse(null);
        if (existing != null) {
            if (!same(existing, requested)) throw stale("Frozen Judge source snapshot changed");
            return existing;
        }
        if (mapper.insertJudgeCandidateSourceSnapshot(requested) != 1) {
            throw stale("Frozen Judge source snapshot could not be inserted");
        }
        return mapper.findJudgeCandidateSourceSnapshot(context.runId())
                .orElseThrow(() -> stale("Frozen Judge source snapshot disappeared"));
    }

    private void requireExact(CandidatePolicy.Context context, JudgeRunRow judge,
                              JudgeReviewBatchRow batch, TaskEvidenceService.JudgeCandidateSource source) {
        if (context == null || judge == null || batch == null || source == null
                || context.candidateKind() != MachineCandidateKind.JUDGE_DECISION_V1
                || context.scope().type() != MachineCandidateSubmission.CandidateScopeType.TASK
                || context.owner().type() != MachineCandidateSubmission.CandidateOwnerType.JUDGE_RUN
                || !judge.taskId().equals(context.scope().id()) || !judge.id().equals(context.owner().id())
                || !"CREATING".equals(judge.state()) || judge.externalSessionId() != null
                || judge.version() != context.ownerVersion() - 1
                || judge.sourceRevision() == null || judge.sourceRevision() != context.sourceRevision()
                || judge.reviewBatchId() == null || !judge.reviewBatchId().equals(batch.id())
                || !judge.taskId().equals(batch.taskId())
                || !judge.attemptId().equals(batch.finalAttemptId())
                || !"RUNNING".equals(batch.state()) || source.prompt() == null || source.prompt().isBlank()
                || source.evidenceCatalog() == null || source.evidenceCatalog().items().isEmpty()
                || !JudgeDecisionCompilation.CONTRACT_VERSION.equals(context.contractVersion())
                || context.maxAttempts() != MachineCandidateKind.JUDGE_DECISION_V1.maximumAttempts()) {
            throw stale("Judge owner, batch, source revision, or evidence source is not exact");
        }
    }

    private static boolean same(
            JudgeCandidateSourceSnapshotRow left, JudgeCandidateSourceSnapshotRow right) {
        return left.candidateRunId().equals(right.candidateRunId())
                && left.judgeRunId().equals(right.judgeRunId()) && left.taskId().equals(right.taskId())
                && left.executionCycleId().equals(right.executionCycleId())
                && left.finalAttemptId().equals(right.finalAttemptId())
                && left.reviewBatchId().equals(right.reviewBatchId()) && left.role().equals(right.role())
                && left.ordinal() == right.ordinal() && left.sourceRevision() == right.sourceRevision()
                && left.preparedOwnerVersion() == right.preparedOwnerVersion()
                && left.contractVersion().equals(right.contractVersion())
                && left.sourcePrompt().equals(right.sourcePrompt())
                && left.sourcePromptSha256().equals(right.sourcePromptSha256())
                && left.canonicalEvidenceJson().equals(right.canonicalEvidenceJson())
                && left.evidenceSha256().equals(right.evidenceSha256());
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private static ConflictException stale(String detail) {
        return new ConflictException("JUDGE_SOURCE_SNAPSHOT_STALE", detail);
    }
}
