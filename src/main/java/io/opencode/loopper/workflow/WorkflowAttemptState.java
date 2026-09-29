package io.opencode.loopper.workflow;

import io.opencode.loopper.domain.DescribedEnum;

public enum WorkflowAttemptState implements DescribedEnum {
    PREPARING("准备中"), RUNNING("运行中"), WAITING_INPUT("等待人工处理"), STOPPING("等待停止确认"), SUCCEEDED("已完成"), FAILED("失败"), CANCELLED("已取消");
    private final String description;
    WorkflowAttemptState(String description) { this.description = description; }
    @Override public String description() { return description; }
    public boolean terminal() { return this == SUCCEEDED || this == FAILED || this == CANCELLED; }
}
