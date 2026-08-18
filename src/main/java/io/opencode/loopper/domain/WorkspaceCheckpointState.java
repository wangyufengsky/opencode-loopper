package io.opencode.loopper.domain;

/** Durable saga state for freezing and later restoring an in-place Git Task workspace. */
public enum WorkspaceCheckpointState implements DescribedEnum {
    CAPTURING("正在冻结"), READY("已冻结"), RESTORING("正在恢复"), RESTORED("已恢复"), BLOCKED("安全阻断");

    private final String description;
    WorkspaceCheckpointState(String description) { this.description = description; }
    @Override public String description() { return description; }
}
