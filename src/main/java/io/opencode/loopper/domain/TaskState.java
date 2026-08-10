package io.opencode.loopper.domain;

public enum TaskState implements DescribedEnum {
    QUEUED("等待调度"), PREPARING("正在准备执行环境"), READY("可以开始执行"),
    RUNNING("正在执行"), VERIFYING("正在验证"), RETRY_WAIT("等待重试"),
    PAUSED("已暂停"), WAITING_INPUT("等待人工输入"), JUDGING("正在最终评审"),
    SUCCEEDED("执行成功"), FAILED("执行失败"), CANCELLED("已取消");

    private final String description;

    TaskState(String description) { this.description = description; }

    @Override public String description() { return description; }

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
