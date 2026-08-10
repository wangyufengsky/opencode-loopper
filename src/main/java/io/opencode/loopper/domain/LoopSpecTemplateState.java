package io.opencode.loopper.domain;

public enum LoopSpecTemplateState implements DescribedEnum {
    ACTIVE("模板可用"), ARCHIVED("模板已归档");

    private final String description;
    LoopSpecTemplateState(String description) { this.description = description; }
    @Override public String description() { return description; }
}
