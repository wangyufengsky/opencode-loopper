package io.opencode.loopper.domain;

public enum GenericCandidateInternalLaunchCleanupState implements DescribedEnum {
    DISCOVERED("已登记待清理远端"), STOPPING("正在停止远端"),
    STOPPED("远端停止已确认"), DISCONNECTED("停止结果待恢复");

    private final String description;
    GenericCandidateInternalLaunchCleanupState(String description) { this.description = description; }
    @Override public String description() { return description; }
}
