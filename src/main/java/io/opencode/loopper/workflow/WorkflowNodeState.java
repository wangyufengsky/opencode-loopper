package io.opencode.loopper.workflow;

import io.opencode.loopper.domain.DescribedEnum;

public enum WorkflowNodeState implements DescribedEnum {
    PENDING("未开始"), ACTIVE("执行中"), SUCCEEDED("已完成"), FAILED("失败"), SKIPPED("分支未选中"), CANCELLED("已取消");
    private final String description;
    WorkflowNodeState(String description) { this.description = description; }
    @Override public String description() { return description; }
    public boolean terminal() { return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == SKIPPED; }
}
