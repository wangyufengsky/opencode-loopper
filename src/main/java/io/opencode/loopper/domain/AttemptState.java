package io.opencode.loopper.domain;

public enum AttemptState implements DescribedEnum {
    RUNNING("正在执行"), SUCCEEDED("执行成功"), VERIFICATION_FAILED("验证失败"),
    SESSION_ERROR("会话异常"), TASK_ERROR("任务异常"), CANCELLED("已取消");

    private final String description;
    AttemptState(String description) { this.description = description; }
    @Override public String description() { return description; }
}
