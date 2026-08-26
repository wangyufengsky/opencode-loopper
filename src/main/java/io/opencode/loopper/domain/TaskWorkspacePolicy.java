package io.opencode.loopper.domain;

/** How a rolling Task owns its registered workspace between packages. */
public enum TaskWorkspacePolicy implements DescribedEnum {
    RELEASE_BETWEEN_PACKAGES("包间释放 Git 工作区"),
    PINNED_DIRECT("Direct 目录全程持锁");

    private final String description;

    TaskWorkspacePolicy(String description) { this.description = description; }

    @Override public String description() { return description; }
}
