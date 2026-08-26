package io.opencode.loopper.domain;

public enum TaskState implements DescribedEnum {
    PENDING_START("等待开始执行"), QUEUED("等待调度"), PREPARING("正在准备执行环境"), READY("可以开始执行"),
    RUNNING("正在执行"), VERIFYING("正在验证"), RETRY_WAIT("等待重试"),
    PAUSED("已暂停"), PACKAGE_DESIGNING("正在设计下一工作包"),
    WAITING_INPUT("等待人工输入"), JUDGING("正在最终评审"),
    STOPPING("正在停止远端执行"),
    AWAITING_DECISION("等待用户处置"), COMPLETED("已确认完成"), SUPERSEDED("已由新任务接续"),
    SUCCEEDED("历史执行成功"), FAILED("历史执行失败"), CANCELLED("已取消");

    private final String description;

    TaskState(String description) { this.description = description; }

    @Override public String description() { return description; }

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == COMPLETED || this == SUPERSEDED || this == CANCELLED;
    }

    public boolean cancellationAvailable() {
        return !terminal() && this != AWAITING_DECISION;
    }
}
