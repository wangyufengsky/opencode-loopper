package io.opencode.loopper.domain;

/** Crash-recoverable lifecycle of an external internal-launch termination request. */
public enum AcceptanceCandidateInternalTerminationIntentState implements DescribedEnum {
    REQUESTED("终止意图已冻结"),
    DISCONNECTED("远端停止结果未确认"),
    READY("全部写入者已确认停止"),
    COMPLETED("父流程已完成收束");

    private final String description;
    AcceptanceCandidateInternalTerminationIntentState(String description) { this.description = description; }
    @Override public String description() { return description; }
    public boolean terminal() { return this == COMPLETED; }
}
