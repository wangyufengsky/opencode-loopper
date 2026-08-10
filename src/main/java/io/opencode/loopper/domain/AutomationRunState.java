package io.opencode.loopper.domain;

public enum AutomationRunState implements DescribedEnum {
    DETECTED("已检测到自动化触发"), REVIEW_REQUIRED("等待人工评审"), QUEUED("自动化任务已排队"),
    RUNNING("自动化任务运行中"), SUCCEEDED("自动化任务成功"), FAILED("自动化任务失败"), SKIPPED("自动化任务已跳过");

    private final String description;
    AutomationRunState(String description) { this.description = description; }
    @Override public String description() { return description; }

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == SKIPPED;
    }
}
