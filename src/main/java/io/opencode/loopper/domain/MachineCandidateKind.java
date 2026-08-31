package io.opencode.loopper.domain;

/** Closed candidate contracts that may use the internal submission protocol. */
public enum MachineCandidateKind implements DescribedEnum {
    DECOMPOSITION_PLAN_V2("任务拆解计划候选", 5),
    ACCEPTANCE_CLOSED_CHOICE_V7("验收闭集选择候选", 2),
    PACKAGE_DESIGN_V1("工作包设计候选", 3);

    private final String description;
    private final int maximumAttempts;

    MachineCandidateKind(String description, int maximumAttempts) {
        this.description = description;
        this.maximumAttempts = maximumAttempts;
    }

    @Override public String description() { return description; }
    public int maximumAttempts() { return maximumAttempts; }
}
