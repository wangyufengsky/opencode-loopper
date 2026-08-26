package io.opencode.loopper.domain;

public enum PackagePlanRevisionState implements DescribedEnum {
    GENERATING("AI 正在生成建议"), PROPOSED("等待确认"), ACTIVE("当前计划"),
    FAILED("AI 建议生成失败"), SUPERSEDED("已被新计划替代");

    private final String description;

    PackagePlanRevisionState(String description) { this.description = description; }

    @Override public String description() { return description; }
}
