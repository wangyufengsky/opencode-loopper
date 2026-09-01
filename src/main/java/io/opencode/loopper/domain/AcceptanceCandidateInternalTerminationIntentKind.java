package io.opencode.loopper.domain;

/** External reason that requires durable cleanup of one internal candidate launch. */
public enum AcceptanceCandidateInternalTerminationIntentKind implements DescribedEnum {
    DESIGNER_CANCEL("Designer 取消"),
    OWNER_REPLACEMENT("需求所有权替换"),
    INITIAL_PROMPT_FAILURE("INITIAL 提示失败");

    private final String description;
    AcceptanceCandidateInternalTerminationIntentKind(String description) { this.description = description; }
    @Override public String description() { return description; }
}
