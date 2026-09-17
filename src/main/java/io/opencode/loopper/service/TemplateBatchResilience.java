package io.opencode.loopper.service;

import io.opencode.loopper.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Bounded transport observations survive restart without authorizing a new model request. */
@Service
public class TemplateBatchResilience {
    private final TemplateBatchResilienceMapper ledger;
    private final TemplateTaskMapper batches;
    private final LoopperMapper tasks;
    public TemplateBatchResilience(TemplateBatchResilienceMapper ledger, TemplateTaskMapper batches, LoopperMapper tasks) {
        this.ledger = ledger; this.batches = batches; this.tasks = tasks;
    }

    public boolean due(TemplateTaskBatchRow row, String operation) {
        var issue = ledger.issue(row.id()).orElse(null);
        return issue == null || issue.resolvedAt() != null || !issue.operation().equals(operation)
                || !Objects.equals(issue.sessionId(), row.sessionId()) || !Objects.equals(issue.promptSha256(), row.promptSha256())
                || !Instant.parse(issue.nextCheckAt()).isAfter(Instant.now());
    }
    public boolean dispatchBlocked(String taskId) { return ledger.dispatchBlocked(taskId, null); }
    public boolean dispatchBlocked(TemplateTaskBatchRow row) { return ledger.dispatchBlocked(row.taskId(), row.id()); }

    @Transactional
    public void failed(TemplateTaskBatchRow expected, String operation, RuntimeException failure) {
        var row = batches.findBatch(expected.id()).orElse(null);
        if (row == null || row.version() != expected.version()) return;
        var previous = ledger.issue(row.id()).orElse(null);
        String code = TemplateBatchFailurePolicy.code(failure);
        boolean repeated = previous != null && previous.resolvedAt() == null && previous.operation().equals(operation)
                && previous.errorCode().equals(code) && Objects.equals(previous.sessionId(), row.sessionId())
                && Objects.equals(previous.promptSha256(), row.promptSha256());
        int count = repeated ? Math.min(previous.failures() + 1, 1_000_000) : 1;
        Instant now = Instant.now();
        String message = TemplateBatchFailurePolicy.detail(failure);
        ledger.saveIssue(new TemplateBatchResilienceMapper.Issue(row.id(), row.sessionId(), row.promptSha256(), operation,
                code, message, repeated ? previous.firstFailedAt() : now.toString(), now.toString(), count,
                now.plusSeconds(Math.min(60, 5L << Math.min(count - 1, 4))).toString(),
                TemplateBatchFailurePolicy.environmentUnavailable(failure) ? 1 : 0, null));
        if (!repeated) {
            var attempt = tasks.findAttempt(row.attemptId()).orElseThrow();
            tasks.insertError(new ErrorEventRow(UUID.randomUUID().toString(), row.taskId(), attempt.stageId(), row.attemptId(),
                    row.sessionId(), "SESSION", code, "第 " + (row.ordinal() + 1) + " 批：" + message,
                    TemplateBatchFailurePolicy.transport(failure), "{\"operation\":\"" + operation + "\",\"batchId\":\"" + row.id() + "\"}", now.toString()));
        }
    }

    @Transactional
    public void succeeded(TemplateTaskBatchRow expected, String operation) {
        var issue = ledger.issue(expected.id()).orElse(null);
        if (issue != null && issue.operation().equals(operation) && Objects.equals(issue.sessionId(), expected.sessionId())
                && Objects.equals(issue.promptSha256(), expected.promptSha256()))
            ledger.resolve(expected.id(), issue.lastFailedAt(), Instant.now().toString());
    }

    @Transactional
    public void checkNow(String taskId, String batchId, long version) {
        var row = batches.findBatch(batchId).orElseThrow(() -> new NotFoundException("批次不存在"));
        if (!row.taskId().equals(taskId)) throw new NotFoundException("批次不属于当前任务");
        if (row.version() != version) throw new ConflictException("TEMPLATE_BATCH_CONFLICT", "批次已变化，请刷新后重试");
        ledger.expedite(batchId, Instant.now().toString());
    }

    @Transactional
    public void delete(String taskId) { ledger.deleteIssues(taskId); ledger.deletePolicies(taskId); }
}
