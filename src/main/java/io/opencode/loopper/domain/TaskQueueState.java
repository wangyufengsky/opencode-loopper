package io.opencode.loopper.domain;

public enum TaskQueueState implements DescribedEnum {
    QUEUED("任务正在排队"), ADMITTED("任务已准入"), CANCELLED("排队任务已取消"), FINISHED("排队任务已结束");

    private final String description;
    TaskQueueState(String description) { this.description = description; }
    @Override public String description() { return description; }
}
