package io.opencode.loopper.domain;

/** Durable create-and-bind saga for one Acceptance v7 internal candidate Session. */
public enum AcceptanceCandidateInternalLaunchState implements DescribedEnum {
    PREPARED("验收候选内部会话启动计划已冻结"),
    CREATING("正在创建或精确回查验收候选内部会话"),
    CREATED("验收候选内部会话已完成本地证明"),
    DISCONNECTED("验收候选内部会话创建结果未确认"),
    STOPPING("正在清理未采用的验收候选内部会话"),
    SETTLED("验收候选内部会话已移交候选运行"),
    FAILED_STOPPED("验收候选内部会话已停止并失败收束"),
    CANCELLED("Designer 停止期间已清理"),
    STALE("所有权漂移后已清理");

    private final String description;

    AcceptanceCandidateInternalLaunchState(String description) { this.description = description; }

    @Override public String description() { return description; }

    public boolean terminal() {
        return this == FAILED_STOPPED || this == CANCELLED || this == STALE;
    }
}
