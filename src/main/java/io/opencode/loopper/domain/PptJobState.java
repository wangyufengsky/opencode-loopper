package io.opencode.loopper.domain;

public enum PptJobState implements DescribedEnum {
    PREPARED("等待制作"), RUNNING("正在制作"), COMPLETED("已完成"), FAILED("制作失败"), CANCELLED("已取消");
    private final String description;
    PptJobState(String description) { this.description = description; }
    @Override public String description() { return description; }
}
