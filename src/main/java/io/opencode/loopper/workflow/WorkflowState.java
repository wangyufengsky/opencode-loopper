package io.opencode.loopper.workflow;

import io.opencode.loopper.domain.DescribedEnum;

/** Requirement lifecycle, independent of plan revisions, node attempts and external sessions. */
public enum WorkflowState implements DescribedEnum {
    PLANNING("流程规划中"), PENDING_START("等待开始"), RUNNING("执行中"), PAUSED("已暂停"),
    STALLED("流程停滞"), STOPPING("核对停止中"), COMPLETED("已完成"), FAILED("已失败"), CANCELLED("已取消");
    private final String description;
    WorkflowState(String description) { this.description = description; }
    @Override public String description() { return description; }
    public boolean terminal() { return this == COMPLETED || this == FAILED || this == CANCELLED; }
}
