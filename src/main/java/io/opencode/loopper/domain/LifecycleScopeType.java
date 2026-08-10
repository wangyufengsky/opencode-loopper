package io.opencode.loopper.domain;

public enum LifecycleScopeType implements DescribedEnum {
    TASK("任务聚合"), PROJECT("项目聚合"), DESIGNER("设计会话聚合"),
    WORKSPACE("工作区聚合"), LOOPSPEC_TEMPLATE("LoopSpec 模板聚合"),
    AUTOMATION_RULE("自动化规则聚合");

    private final String description;
    LifecycleScopeType(String description) { this.description = description; }
    @Override public String description() { return description; }
}
