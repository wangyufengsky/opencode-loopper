package io.opencode.loopper.domain;

public enum WorkspaceLeaseState implements DescribedEnum {
    HELD("工作区租约已持有"), RELEASE_PENDING("工作区租约等待释放"), RELEASED("工作区租约已释放");

    private final String description;
    WorkspaceLeaseState(String description) { this.description = description; }
    @Override public String description() { return description; }
}
