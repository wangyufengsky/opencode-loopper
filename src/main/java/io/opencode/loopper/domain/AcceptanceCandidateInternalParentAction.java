package io.opencode.loopper.domain;

/** Durable parent continuation selected after an internal writer stop request. */
public enum AcceptanceCandidateInternalParentAction implements DescribedEnum {
    NONE("按原始提示失败结果收束"),
    DESIGNER_CANCEL("取消 Designer"),
    OWNER_REPLACEMENT("替换当前需求");

    private final String description;
    AcceptanceCandidateInternalParentAction(String description) { this.description = description; }
    @Override public String description() { return description; }
}
