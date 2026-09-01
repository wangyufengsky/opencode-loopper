package io.opencode.loopper.domain;

/** Stable purpose of one durable candidate prompt dispatch. */
public enum CandidatePromptDispatchKind implements DescribedEnum {
    INITIAL("首次候选提示"), CORRECTION("候选修正提示");

    private final String description;
    CandidatePromptDispatchKind(String description) { this.description = description; }
    @Override public String description() { return description; }
}
