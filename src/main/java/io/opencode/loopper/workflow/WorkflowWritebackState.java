package io.opencode.loopper.workflow;

import io.opencode.loopper.domain.DescribedEnum;

public enum WorkflowWritebackState implements DescribedEnum {
    CONFIRMED("已确认回填"), APPLYING("正在回填"), BLOCKED("回填待处理"), APPLIED("已回填原目录");
    private final String description;
    WorkflowWritebackState(String description){this.description=description;}
    @Override public String description(){return description;}
}
