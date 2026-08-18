package io.opencode.loopper.domain;

/** Result of one authorized execution cycle; it is intentionally independent of Task finality. */
public enum ExecutionCycleState implements DescribedEnum {
    RUNNING("执行中"), SUCCEEDED("本轮成功"), FAILED("本轮失败"),
    INTERRUPTED("本轮中断"), AUDIT_COMPLETED("审计完成");

    private final String description;
    ExecutionCycleState(String description) { this.description = description; }
    @Override public String description() { return description; }
    public boolean terminal() { return this != RUNNING; }
}
