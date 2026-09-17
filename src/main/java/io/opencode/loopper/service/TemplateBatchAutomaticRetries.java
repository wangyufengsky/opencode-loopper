package io.opencode.loopper.service;

import io.opencode.loopper.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Each manual round has a separate frozen budget; total generation never doubles as its retry counter. */
@Service
public class TemplateBatchAutomaticRetries {
    private final TemplateBatchResilienceMapper ledger;
    private final TemplateBatchStore batches;
    public TemplateBatchAutomaticRetries(TemplateBatchResilienceMapper ledger, TemplateBatchStore batches) {
        this.ledger = ledger; this.batches = batches;
    }

    @Transactional
    public Window prepare(List<TemplateTaskBatchRow> rows) {
        var result = new ArrayList<TemplateTaskBatchRow>();
        boolean pending = false;
        for (var row : rows) {
            if (!TemplateBatchFailurePolicy.retryable(row.state(), row.errorCode())) { result.add(row); continue; }
            var policy = ledger.retryPolicy(row.id()).orElseThrow();
            if (policy.automaticRetries() >= policy.retryLimit()) { result.add(row); continue; }
            pending = true;
            if (Instant.parse(row.updatedAt()).plusSeconds(TemplateBatchFailurePolicy.retryDelay(policy.automaticRetries())).isAfter(Instant.now())) {
                result.add(row); continue;
            }
            var next = batches.retry(row, row.version(), false);
            if (!next.id().equals(row.id()) && ledger.retryPolicy(next.id()).orElseThrow().automaticRetries() == 0)
                ledger.insertRetryPolicy(new TemplateBatchResilienceMapper.RetryPolicy(next.id(), policy.automaticRetries() + 1, policy.retryLimit()));
            result.add(next);
        }
        return new Window(List.copyOf(result), pending);
    }

    @Transactional
    public TemplateTaskBatchRow manual(TemplateTaskBatchRow row, long version) {
        var next = batches.retry(row, version, true);
        if (next.generation() != row.generation() + 1 || !next.state().equals("PREPARED"))
            throw new ConflictException("TEMPLATE_BATCH_CONFLICT", "所选批次已有新的执行记录，请刷新并选择当前批次");
        ledger.insertRetryPolicy(new TemplateBatchResilienceMapper.RetryPolicy(next.id(), 0, TemplateBatchFailurePolicy.MAX_RETRIES));
        return next;
    }
    public record Window(List<TemplateTaskBatchRow> rows, boolean retryPending) { }
}
