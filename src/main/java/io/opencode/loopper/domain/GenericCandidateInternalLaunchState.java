package io.opencode.loopper.domain;

/** Durable V57 internal launch state, intentionally distinct from Acceptance V55. */
public enum GenericCandidateInternalLaunchState implements DescribedEnum {
    PREPARED("已冻结本地启动计划"), CREATING("已发出远端创建请求"), CREATED("远端已认证"),
    DISCONNECTED("远端结果待恢复"), STOPPING("正在清理远端"), SETTLED("候选运行已原子接管"),
    COMPLETED("候选运行正常结束且远端已停止"),
    FAILED_STOPPED("失败且远端已停止"), CANCELLED("已取消且远端已停止"), STALE("已替代且远端已停止");

    private final String description;
    GenericCandidateInternalLaunchState(String description) { this.description = description; }
    @Override public String description() { return description; }
    public boolean terminal() {
        return this == COMPLETED || this == FAILED_STOPPED || this == CANCELLED || this == STALE;
    }
}
