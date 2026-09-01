package io.opencode.loopper.domain;

/** Why an attested remote was registered in the shared stop ledger. */
public enum AcceptanceCandidateInternalLaunchCleanupPurpose implements DescribedEnum {
    LAUNCH_AMBIGUITY("创建结果歧义清理"),
    TERMINATION_INTENT("外部终止意图清理");

    private final String description;
    AcceptanceCandidateInternalLaunchCleanupPurpose(String description) { this.description = description; }
    @Override public String description() { return description; }
}
