package io.opencode.loopper.domain;

public enum AutomationRuleState implements DescribedEnum {
    DISABLED("自动化规则已停用"), ENABLED("自动化规则已启用");

    private final String description;
    AutomationRuleState(String description) { this.description = description; }
    @Override public String description() { return description; }
}
