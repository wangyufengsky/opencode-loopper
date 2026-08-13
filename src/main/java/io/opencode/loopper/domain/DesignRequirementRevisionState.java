package io.opencode.loopper.domain;

/** Lifecycle of one immutable, complete user requirement snapshot. */
public enum DesignRequirementRevisionState implements DescribedEnum {
    ACTIVE("当前需求版本正在处理"),
    COMPLETED("当前需求版本已完成聚合"),
    WAITING_INPUT("当前需求版本等待人工补充"),
    SUPERSEDED("需求版本已被后续完整版本替代");

    private final String description;
    DesignRequirementRevisionState(String description) { this.description = description; }
    @Override public String description() { return description; }
}
