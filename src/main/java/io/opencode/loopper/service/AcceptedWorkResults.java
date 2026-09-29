package io.opencode.loopper.service;

import static io.opencode.loopper.service.MachineCandidateSubmission.*;

import io.opencode.loopper.persistence.CandidateSubmissionAttemptRow;
import io.opencode.loopper.persistence.CandidateSubmissionRunRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.WorkResultMapper;
import io.opencode.loopper.workflow.WorkResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Shared canonical outputs for existing role protocols; callers still own stop and completion checks. */
@Service
public class AcceptedWorkResults {
    private final WorkResultMapper results;
    private final LoopperMapper candidates;

    public AcceptedWorkResults(WorkResultMapper results, LoopperMapper candidates) {
        this.results = results;
        this.candidates = candidates;
    }

    /** Atomic with the role writer, accepted attempt and candidate transition. Never stores rejected input. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(CandidateSubmissionRunRow run, CandidateSubmissionAttemptRow attempt, String content) {
        if (run.resultStorageVersion() == 0) return;
        if (run.resultStorageVersion() != 1 || !"ACCEPTED".equals(run.state())
                || !"ACCEPTED".equals(attempt.outcome()) || !run.id().equals(attempt.runId())
                || !Objects.equals(run.terminalAttemptId(), attempt.id())
                || content == null || !hash(content).equals(attempt.canonicalResultSha256())) throw inconsistent();
        var row = new WorkResultMapper.Result(attempt.id(), run.id(), content,
                attempt.canonicalResultSha256(), attempt.createdAt());
        if (results.insert(row) != 1) throw inconsistent();
    }

    /** Scope and producer are supplied from a previously authorized business owner, never from model text. */
    public Optional<WorkResult> find(CandidateScope scope, CandidateOwnerRef owner, String runId) {
        Objects.requireNonNull(scope);
        Objects.requireNonNull(owner);
        var run = candidates.findCandidateSubmissionRun(runId)
                .orElseThrow(() -> new NotFoundException("工作结果所属运行不存在"));
        String actualScopeId = switch (scope.type()) {
            case DESIGNER_SESSION -> run.designerSessionId();
            case TASK -> run.taskId();
            case PROJECT -> run.projectId();
        };
        if (!scope.id().equals(actualScopeId) || !owner.type().name().equals(run.ownerType())
                || !owner.id().equals(run.ownerId()))
            throw new NotFoundException("该工作范围内不存在指定结果");
        if (run.resultStorageVersion() == 0) return Optional.empty();
        if (run.resultStorageVersion() != 1) throw inconsistent();
        var stored = results.find(run.id());
        if (!"ACCEPTED".equals(run.state())) {
            if (stored.isPresent()) throw inconsistent();
            return Optional.empty();
        }
        var row = stored.orElseThrow(AcceptedWorkResults::inconsistent);
        if (!row.id().equals(run.terminalAttemptId()) || !row.runId().equals(run.id())
                || !hash(row.content()).equals(row.sha256())) throw inconsistent();
        return Optional.of(new WorkResult(new WorkResult.Reference(row.id(), row.sha256()), run.id(),
                run.ownerType(), run.ownerId(), run.contractVersion(), run.sourceRevision(), run.attemptsUsed(),
                "application/json", row.content(), row.createdAt()));
    }

    /**
     * Existing workflows retain their immutable native output for historical recovery. New runs must
     * agree with the shared result; missing new data cannot silently fall back to the legacy column.
     */
    public void verifyNative(CandidateScope scope, CandidateOwnerRef owner, String runId,
                             String content, String sha256) {
        var accepted = find(scope, owner, runId);
        if (accepted.isEmpty()) {
            if (candidates.findCandidateSubmissionRun(runId).orElseThrow().resultStorageVersion() != 0)
                throw inconsistent();
            return;
        }
        var result = accepted.get();
        if (!result.content().equals(content) || !result.reference().sha256().equals(sha256)) throw inconsistent();
    }

    /** Resolve the pinned identity, never the most recent output of a producer. Completion is caller-owned. */
    public WorkResult resolve(CandidateScope scope, WorkResult.Binding binding) {
        CandidateOwnerType type;
        try { type = CandidateOwnerType.valueOf(binding.producerType()); }
        catch (IllegalArgumentException invalid) { throw inconsistent(); }
        var result = find(scope, new CandidateOwnerRef(type, binding.producerId()), binding.producerRunId())
                .orElseThrow(AcceptedWorkResults::inconsistent);
        if (!result.reference().equals(binding.reference()) || !result.kind().equals(binding.kind())) throw inconsistent();
        return result;
    }

    private static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static ConflictException inconsistent() {
        return new ConflictException("WORK_RESULT_INCONSISTENT", "已接受交付物与执行记录不一致，已保留原现场，请检查后恢复");
    }
}
