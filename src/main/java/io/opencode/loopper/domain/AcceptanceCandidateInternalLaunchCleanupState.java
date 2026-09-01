package io.opencode.loopper.domain;

/** Positive-stop lifecycle for one exact-title match from an uncertain internal launch. */
public enum AcceptanceCandidateInternalLaunchCleanupState implements DescribedEnum {
    DISCOVERED("已登记待停止的验收候选内部会话"),
    STOPPING("正在停止验收候选内部会话"),
    DISCONNECTED("验收候选内部会话停止结果未确认"),
    STOPPED("验收候选内部会话已证明停止");

    private final String description;

    AcceptanceCandidateInternalLaunchCleanupState(String description) { this.description = description; }

    @Override public String description() { return description; }
}
