package io.opencode.loopper.domain;

public enum InteractionState implements DescribedEnum {
    PENDING("等待处理"), RESOLVING("正在处理"), RESOLVED("已解决"),
    REJECTED("已拒绝"), HARD_DENIED("已被安全策略强制拒绝"), STALE("已失效");

    private final String description;
    InteractionState(String description) { this.description = description; }
    @Override public String description() { return description; }
}
