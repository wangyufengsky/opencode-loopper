package io.opencode.loopper.domain;

/** Stable persisted author identity; UI rendering must not infer it from message text. */
public enum DesignerActor implements DescribedEnum {
    USER("用户"), ROUTER("任务画像路由器"), DECOMPOSER("任务拆解器"), DESIGNER("设计师"),
    COMPILER("规范编译器"), REVIEWER("只读评审器"), VALIDATOR("确定性校验器"), SYSTEM("系统");

    private final String description;
    DesignerActor(String description) { this.description = description; }
    @Override public String description() { return description; }
}
