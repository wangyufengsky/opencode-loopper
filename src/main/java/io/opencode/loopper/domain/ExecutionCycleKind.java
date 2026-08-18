package io.opencode.loopper.domain;

/** Why a user-authorized execution cycle exists. */
public enum ExecutionCycleKind implements DescribedEnum {
    INITIAL("首次执行"), CONTINUE_FAILED("失败后继续"), CONTINUE_SUCCESS("成功后继续优化"),
    READ_ONLY_AUDIT("只读审计");

    private final String description;
    ExecutionCycleKind(String description) { this.description = description; }
    @Override public String description() { return description; }
}
