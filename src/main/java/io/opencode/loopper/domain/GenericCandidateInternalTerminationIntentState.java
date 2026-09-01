package io.opencode.loopper.domain;

public enum GenericCandidateInternalTerminationIntentState implements DescribedEnum {
    REQUESTED("已请求终止"), DISCONNECTED("终止结果待恢复"),
    READY("writer 已安全收束"), COMPLETED("拥有者动作已完成");

    private final String description;
    GenericCandidateInternalTerminationIntentState(String description) { this.description = description; }
    @Override public String description() { return description; }
}
