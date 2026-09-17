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
    private final io.opencode.loopper.persistence.TemplateTaskReadMapper reads;
    private final TemplateBatchAutomaticRetries automatic;

    TemplateBatchRetryService(TemplateBatchStore batches,
            TemplateTaskStateService states, TemplateTaskCoordinator coordinator, org.springframework.transaction.PlatformTransactionManager manager,
            io.opencode.loopper.persistence.TemplateTaskReadMapper reads, TemplateBatchAutomaticRetries automatic) {
        this.batches = batches; this.states = states;
        this.reads = reads; this.coordinator = coordinator; this.transactions = new TransactionTemplate(manager);
        this.automatic = automatic;
    }

    public TemplateTaskBatchRow retry(String taskId, String batchId, long expectedVersion) {
        return retrySelected(taskId, new BatchRetrySelection(java.util.List.of(
                new BatchRetrySelection.Item(batchId, expectedVersion)))).getFirst();
    }

    public java.util.List<TemplateTaskBatchRow> retrySelected(String taskId, BatchRetrySelection selection) {
        selection.validate();
        var next = transactions.execute(ignored -> {
            if (!reads.retrySelectionReady(taskId))
                throw new ConflictException("TEMPLATE_BATCHES_STILL_RUNNING", "后续批次仍在执行，完成后再统一选择重试");
            states.resumeBatch(taskId);
            return selection.batches().stream().map(item -> {
                var row = batches.require(item.id());
                if (!row.taskId().equals(taskId)) throw new NotFoundException("批次不属于当前任务");
                return automatic.manual(row, item.expectedVersion());
            }).toList();
        });
        coordinator.dispatch(taskId);
        return next;
    }
}
