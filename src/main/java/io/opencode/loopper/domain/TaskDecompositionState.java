package io.opencode.loopper.domain;

/** Independent lifecycle of one read-only Task Decomposer invocation. */
public enum TaskDecompositionState implements DescribedEnum {
    PENDING_HANDOFF("等待连接任务规划师"),
    RUNNING("任务规划师正在工作"),
    VALIDATING("服务端正在校验拆解结果"),
    COMPLETED("拆解计划已冻结"),
    NEEDS_INPUT("拆解需要人工补充需求"),
    MULTI_TASK_REQUIRED("需求超出单任务边界"),
    SESSION_ERROR("任务拆解会话失败");

    private final String description;
    TaskDecompositionState(String description) { this.description = description; }
    @Override public String description() { return description; }
}
