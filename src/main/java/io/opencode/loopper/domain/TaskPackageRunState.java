package io.opencode.loopper.domain;

/** Independent lifecycle for one package inside a rolling Task. */
public enum TaskPackageRunState implements DescribedEnum {
    PLANNED("已规划"), DESIGNING("正在设计"), DESIGN_REVIEW("等待设计确认"),
    EXECUTION_READY("等待开始执行"), QUEUED("等待调度"), RUNNING("正在执行"),
    VERIFYING("正在机器验收"), CHECKPOINTING("正在冻结事实"), FACT_FROZEN("事实已冻结"),
    WAITING_INPUT("等待人工处理"), SUPERSEDED("已被新计划替代"), CANCELLED("已取消");

    private final String description;

    TaskPackageRunState(String description) { this.description = description; }

    @Override public String description() { return description; }

    public boolean terminal() {
        return this == FACT_FROZEN || this == SUPERSEDED || this == CANCELLED;
    }
}
