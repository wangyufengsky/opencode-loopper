package io.opencode.loopper.domain;

/** Workspace-scoped availability of OpenCode's implementation-session todowrite tool. */
public enum TodoCapability implements DescribedEnum {
    AVAILABLE("当前工作区可使用 OpenCode Todo"),
    UNAVAILABLE("当前工作区未暴露 OpenCode Todo"),
    UNKNOWN("无法确认当前工作区的 OpenCode Todo 能力");

    private final String description;

    TodoCapability(String description) { this.description = description; }

    @Override public String description() { return description; }
}
