package io.opencode.loopper.domain;

/** One generation-safe Requirement/Risk pair; results from different batches never mix. */
public enum JudgeReviewBatchState implements DescribedEnum {
    RUNNING("双评审运行中"), COMPLETED("双评审已通过"),
    WAITING_INPUT("双评审等待人工处理"), CANCELLED("双评审已取消");

    private final String description;
    JudgeReviewBatchState(String description) { this.description = description; }
    @Override public String description() { return description; }
    public boolean terminal() { return this != RUNNING; }
}
