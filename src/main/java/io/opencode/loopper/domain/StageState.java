package io.opencode.loopper.domain;

public enum StageState implements DescribedEnum {
    PENDING("等待执行"), RUNNING("正在执行"), SUCCEEDED("执行成功"),
    FAILED("执行失败"), PAUSED("已暂停");

    private final String description;
    StageState(String description) { this.description = description; }
    @Override public String description() { return description; }
}
