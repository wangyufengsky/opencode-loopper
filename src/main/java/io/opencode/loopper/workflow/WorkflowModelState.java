package io.opencode.loopper.workflow;

/** Session delivery phases, independent of the node attempt's business outcome. */
public enum WorkflowModelState implements io.opencode.loopper.domain.DescribedEnum {
    PREPARING("准备请求"), CREATING("创建会话"), DISPATCHING("确认消息送达"), RUNNING("执行中"),
    STOPPING("确认停止"), SUCCEEDED("已完成"), FAILED("失败"), CANCELLED("已取消");
    private final String description;
    WorkflowModelState(String description) { this.description=description; }
    @Override public String description() { return description; }
    public boolean terminal() { return this == SUCCEEDED || this == FAILED || this == CANCELLED; }
}
