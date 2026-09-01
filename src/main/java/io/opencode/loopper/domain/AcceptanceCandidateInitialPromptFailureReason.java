package io.opencode.loopper.domain;

/** Closed failure evidence that may authorize termination before INITIAL acknowledgement. */
public enum AcceptanceCandidateInitialPromptFailureReason implements DescribedEnum {
    BUDGET_EXHAUSTED("模型调用预算耗尽"),
    LOOKUP_UNSUPPORTED("精确提示查找不可用"),
    RESULT_UNKNOWN("提示派发结果未知");

    private final String description;
    AcceptanceCandidateInitialPromptFailureReason(String description) { this.description = description; }
    @Override public String description() { return description; }
}
