package io.opencode.loopper.domain;

public enum DesignerAutoModeState implements DescribedEnum {
    DISABLED("全自动模式已关闭"),
    ACTIVE("全自动模式运行中"),
    BLOCKED("全自动模式已阻断"),
    COMPLETED("全自动模式已完成授权范围");

    private final String description;

    DesignerAutoModeState(String description) { this.description = description; }

    @Override public String description() { return description; }
}
