package io.opencode.loopper.domain;

public enum DesignerSessionState implements DescribedEnum {
    PENDING_HANDOFF("等待设计交接"), RUNNING("设计会话运行中"),
    REVIEWING("设计稿等待人工确认"),
    WAITING_INPUT("设计工作流等待人工输入"),
    COMPLETED("设计会话已完成"), SESSION_ERROR("设计会话异常");

    private final String description;
    DesignerSessionState(String description) { this.description = description; }
    @Override public String description() { return description; }
}
