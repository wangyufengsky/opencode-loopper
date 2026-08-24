package io.opencode.loopper.domain;

public enum ProjectConventionState implements DescribedEnum {
    RUNNING("正在生成项目约定"), STOPPING("正在停止项目约定生成"), CANCELLED("项目约定生成已取消"), READY("项目约定可应用"),
    APPLYING("正在应用项目约定"), APPLIED("项目约定已应用"), FAILED("项目约定生成或应用失败");

    private final String description;
    ProjectConventionState(String description) { this.description = description; }
    @Override public String description() { return description; }
}
