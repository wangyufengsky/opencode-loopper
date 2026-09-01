package io.opencode.loopper.domain;

/** Durable unopened v7 fallback saga; it owns both old proof and successor cleanup. */
public enum AcceptanceCandidateHandoffState implements DescribedEnum {
    STOPPING_OLD("正在确认旧候选会话停止"),
    OLD_STOPPED("旧候选会话已确认停止"),
    CREATING_LEGACY("正在创建或回查兼容候选会话"),
    LEGACY_CREATED("兼容候选会话已持久化"),
    LEGACY_OPENED("兼容候选运行已原子打开"),
    PROMPTING("兼容候选提示已预约"),
    HANDED_OFF("兼容候选提示已确认送达"),
    SETTLED("兼容候选会话已证明停止并成功收束"),
    STOPPING_LEGACY("正在清理未采用的兼容候选会话"),
    FAILED_STOPPED("兼容候选会话已停止并失败收束"),
    CANCELLED("Designer 停止期间已清理"),
    STALE("所有权漂移后已清理");

    private final String description;
    AcceptanceCandidateHandoffState(String description) { this.description = description; }
    @Override public String description() { return description; }
}
