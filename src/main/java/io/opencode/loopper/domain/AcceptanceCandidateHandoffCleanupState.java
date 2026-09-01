package io.opencode.loopper.domain;

/** Durable positive-stop lifecycle for one exact-title recovery match. */
public enum AcceptanceCandidateHandoffCleanupState implements DescribedEnum {
    DISCOVERED("已登记待停止的兼容候选会话"),
    STOPPING("正在停止兼容候选会话"),
    DISCONNECTED("兼容候选会话停止结果未确认"),
    STOPPED("兼容候选会话已证明停止");

    private final String description;

    AcceptanceCandidateHandoffCleanupState(String description) { this.description = description; }

    @Override public String description() { return description; }
}
