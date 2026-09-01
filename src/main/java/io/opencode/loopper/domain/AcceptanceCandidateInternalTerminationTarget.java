package io.opencode.loopper.domain;

/** Requested terminal projection after every remote writer is positively stopped. */
public enum AcceptanceCandidateInternalTerminationTarget implements DescribedEnum {
    CANCELLED("取消收束"),
    STALE("替换失效"),
    FAILED_STOPPED("失败并已停止");

    private final String description;
    AcceptanceCandidateInternalTerminationTarget(String description) { this.description = description; }
    @Override public String description() { return description; }
}
