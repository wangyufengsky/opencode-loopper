package io.opencode.loopper.service;

import io.opencode.loopper.persistence.TemplateTaskBatchRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Manual authority grants one fresh batch generation after precise stop confirmation. */
@Service
public class TemplateBatchRetryService {
    private final TemplateBatchStore batches;
    private final TemplateTaskStateService states;
    private final TemplateTaskCoordinator coordinator;
    private final TransactionTemplate transactions;

    TemplateBatchRetryService(TemplateBatchStore batches,
            TemplateTaskStateService states, TemplateTaskCoordinator coordinator, org.springframework.transaction.PlatformTransactionManager manager) {
        this.batches = batches; this.states = states;
        this.coordinator = coordinator; this.transactions = new TransactionTemplate(manager);
    }

    public TemplateTaskBatchRow retry(String taskId, String batchId, long expectedVersion) {
        var row = batches.require(batchId);
        if (!row.taskId().equals(taskId)) throw new NotFoundException("批次不属于当前任务");
        if (row.version() != expectedVersion) throw new ConflictException("TEMPLATE_BATCH_CONFLICT", "批次已变化，请刷新后重试");
        if (!java.util.Set.of("FAILED", "STOPPED").contains(row.state()))
            throw new ConflictException("TEMPLATE_BATCH_STOP_UNCONFIRMED", "请等待该批次会话停止确认后再重试");
        var next = transactions.execute(ignored -> {
            states.resumeBatch(taskId);
            return batches.retry(row, expectedVersion, true);
        });
        coordinator.dispatch(taskId);
        return next;
    }
}
