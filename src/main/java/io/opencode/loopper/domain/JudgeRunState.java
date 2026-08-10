package io.opencode.loopper.domain;

public enum JudgeRunState implements DescribedEnum {
    CREATING("正在创建评审会话"), RUNNING("评审运行中"), COMPLETED("评审已完成"),
    SESSION_ERROR("评审会话异常"), ABORTED("评审已终止"),
    FAILED("历史评审失败状态"), TIMED_OUT("历史评审超时状态");

    private final String description;
    JudgeRunState(String description) { this.description = description; }
    @Override public String description() { return description; }

    public boolean terminal() {
        return this == COMPLETED || this == SESSION_ERROR || this == ABORTED
                || this == FAILED || this == TIMED_OUT;
    }
}
