package io.opencode.loopper.domain;

/** Intake state never substitutes for the linked Task, writer, candidate or Judge state. */
public enum SourceTemplateState implements DescribedEnum {
    PENDING_START("等待开始"), PREPARING("冻结源码范围"), DESIGNING("设计测试方案"),
    EXECUTING("开发与验证测试"), WRITING("编写详细设计"), REVIEWING("独立复核"),
    REPORTING("保存交付物"), WAITING_INPUT("等待处理"), STOPPING("正在停止"),
    CANCELLED("已取消"), COMPLETED("已完成");
    private final String description;
    SourceTemplateState(String description) { this.description = description; }
    @Override public String description() { return description; }
    public boolean terminal() { return this == CANCELLED || this == COMPLETED; }
}
