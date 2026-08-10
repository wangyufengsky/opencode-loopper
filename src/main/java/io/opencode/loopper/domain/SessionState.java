package io.opencode.loopper.domain;

public enum SessionState implements DescribedEnum {
    CREATING("正在创建会话"), RUNNING("会话运行中"), COMPLETED("会话已完成"),
    FAILED("会话失败"), TIMED_OUT("会话超时"), DISCONNECTED("会话已断开"), ABORTED("会话已终止");

    private final String description;
    SessionState(String description) { this.description = description; }
    @Override public String description() { return description; }

    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == TIMED_OUT || this == ABORTED;
    }
}
