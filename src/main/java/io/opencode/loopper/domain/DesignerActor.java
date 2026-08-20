package io.opencode.loopper.domain;

/** Stable persisted author identity; UI rendering must not infer it from message text. */
public enum DesignerActor implements DescribedEnum {
    USER("用户"), ROUTER("需求分析师"), DECOMPOSER("任务规划师"), DESIGNER("设计师"),
    COMPILER("规范工程师"), REVIEWER("评审员"), VALIDATOR("验收工程师"), SYSTEM("系统");

    private final String description;
    DesignerActor(String description) { this.description = description; }
    @Override public String description() { return description; }
}
