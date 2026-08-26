package io.opencode.loopper.domain;

/** Selects the immutable execution contract used by a Task. */
public enum TaskExecutionMode implements DescribedEnum {
    LEGACY_AGGREGATE("传统聚合执行"),
    ROLLING_PACKAGES("逐包闭环执行");

    private final String description;

    TaskExecutionMode(String description) { this.description = description; }

    @Override public String description() { return description; }
}
