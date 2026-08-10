package io.opencode.loopper.domain;

public enum ProjectConventionState implements DescribedEnum {
    RUNNING("正在生成项目约定"), READY("项目约定可应用"),
    APPLIED("项目约定已应用"), FAILED("项目约定生成失败");

    private final String description;
    ProjectConventionState(String description) { this.description = description; }
    @Override public String description() { return description; }
}
