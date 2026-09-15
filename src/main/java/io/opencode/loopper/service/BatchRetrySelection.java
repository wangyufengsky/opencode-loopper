package io.opencode.loopper.service;

import java.util.List;

/** A bounded explicit selection; versions bind every retry to the reviewed failure. */
public record BatchRetrySelection(List<Item> batches) {
    public record Item(String id, long expectedVersion) { }
    public void validate() {
        if (batches == null || batches.isEmpty() || batches.size() > 100
                || batches.stream().anyMatch(item -> item == null || item.id() == null || item.id().isBlank()
                    || item.id().length() > 100 || item.expectedVersion() < 0)
                || batches.stream().map(Item::id).distinct().count() != batches.size())
            throw new BadRequestException("BATCH_RETRY_SELECTION_INVALID", "请选择 1 至 100 个不同的失败批次后重试");
    }
}
